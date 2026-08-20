package com.sahid.camera.core

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 02 canonical output writer.
 *
 * One shutter press produces one standards-compatible DNG and no companion JPEG/HEIF/AURAW file.
 * The source is the original RAW_SENSOR Image plus the timestamp-matched Camera2 CaptureResult.
 */
object AuroraDngWriter {
    private const val MIME_TYPE_DNG = "image/x-adobe-dng"

    fun write(
        context: Context,
        lens: LensCapability,
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult,
        displayRotation: Int,
    ): RawCaptureRecord {
        require(image.format == ImageFormat.RAW_SENSOR) { "DNG requires RAW_SENSOR" }
        val resultTimestamp = captureResult.get(CaptureResult.SENSOR_TIMESTAMP)
            ?: error("CaptureResult.SENSOR_TIMESTAMP missing")
        check(resultTimestamp == image.timestamp) {
            "RAW/DNG metadata mismatch: image=${image.timestamp}, result=$resultTimestamp"
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "IMG_${stamp}_AURORA.dng"
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val uprightDegrees = CameraOrientation.sensorToDeviceDegrees(
            sensorOrientation = sensorOrientation,
            isFrontFacing = lens.isFrontFacing,
            displayRotation = displayRotation,
        )
        val orientation = CameraOrientation.exifOrientationForDegrees(uprightDegrees)
        val relativePath = CameraStoragePolicy.DNG_RELATIVE_PATH

        val displayFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeMediaStore(context, fileName, relativePath, image, characteristics, captureResult, orientation)
            File("/$relativePath", fileName)
        } else {
            writeLegacy(context, fileName, image, characteristics, captureResult, orientation)
        }

        return RawCaptureRecord(
            file = displayFile,
            cameraId = lens.cameraId,
            accessPath = lens.accessPath,
            width = image.width,
            height = image.height,
            timestampNs = image.timestamp,
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun writeMediaStore(
        context: Context,
        fileName: String,
        relativePath: String,
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult,
        orientation: Int,
    ) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE_DNG)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore refused DNG destination")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                DngCreator(characteristics, captureResult).use { creator ->
                    creator.setOrientation(orientation)
                    creator.writeImage(output, image)
                }
            } ?: throw IllegalStateException("Unable to open DNG destination")

            val updated = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            check(updated == 1) { "Unable to publish DNG" }
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(
        context: Context,
        fileName: String,
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult,
        orientation: Int,
    ): File {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val directory = File(dcim, "Camera")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create ${directory.absolutePath}")
        }
        val destination = File(directory, fileName)
        FileOutputStream(destination).use { output ->
            DngCreator(characteristics, captureResult).use { creator ->
                creator.setOrientation(orientation)
                creator.writeImage(output, image)
            }
        }
        return destination
    }
}
