package com.sahid.camera.core

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aurora's canonical Phase-02 source record.
 *
 * The RAW payload is copied byte-for-byte from the single RAW_SENSOR Image plane. Aurora does not
 * demosaic, tone-map, compress, reinterpret, or convert the sensor samples before persistence.
 * Row/pixel stride and byte order are stored in the JSON header so padding/layout stay unambiguous.
 *
 * Container v1 layout (big-endian scalar fields):
 *   8 bytes  magic = AURAW\0\1\0
 *   int32    JSON metadata byte length
 *   int64    RAW payload byte length
 *   N bytes  UTF-8 JSON metadata
 *   M bytes  exact RAW plane payload
 *
 * Storage policy intentionally separates representation from source truth:
 * - DCIM/Camera contains the normal user-facing rendition that Gallery/Photos apps index.
 * - Documents/Camera/RAW contains the canonical AURAW master because AURAW is a generic binary
 *   document, not an image MIME type. Aurora's own gallery will present both as one capture.
 */
object AuroraRawWriter {
    private const val MIME_TYPE_AURAW = "application/octet-stream"

    private val magic = byteArrayOf(
        'A'.code.toByte(), 'U'.code.toByte(), 'R'.code.toByte(), 'A'.code.toByte(),
        'W'.code.toByte(), 0, 1, 0,
    )

    fun write(
        context: Context,
        lens: LensCapability,
        packet: RawImagePacket,
        captureApi: String,
        staticMetadata: JSONObject,
        dynamicMetadata: JSONObject,
    ): RawCaptureRecord {
        require(packet.imageFormat == ImageFormat.RAW_SENSOR)
        val digest = sha256(packet.bytes)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val lensId = lens.cameraId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val fileName = "AURORA_${stamp}_ID-${lensId}.auraw"
        val previewFileName = "IMG_${stamp}_AURORA.jpg"

        val metadata = JSONObject()
            .put("container", "AURAW")
            .put("containerVersion", 1)
            .put("canonicalSource", true)
            .put("rawPayloadEncoding", "ANDROID_RAW_SENSOR_PLANE_BYTES")
            .put("rawByteOrder", ByteOrder.nativeOrder().toString())
            .put("cameraId", lens.cameraId)
            .put("logicalCameraId", lens.logicalCameraId)
            .put("physicalCameraId", lens.physicalCameraId ?: JSONObject.NULL)
            .put("accessPath", lens.accessPath.name)
            .put("captureApi", captureApi)
            .put("width", packet.width)
            .put("height", packet.height)
            .put("imageFormat", packet.imageFormat)
            .put("imageTimestampNs", packet.timestampNs)
            .put("rowStride", packet.rowStride)
            .put("pixelStride", packet.pixelStride)
            .put("payloadBytes", packet.bytes.size)
            .put("payloadSha256", digest)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("canonicalRelativePath", CameraStoragePolicy.CANONICAL_RAW_RELATIVE_PATH)
            .put(
                "derivedPreview",
                JSONObject()
                    .put("canonical", false)
                    .put("feedsComputationalPipeline", false)
                    .put("fileName", previewFileName)
                    .put("relativePath", CameraStoragePolicy.VISIBLE_ALBUM_RELATIVE_PATH)
                    .put("renderer", "AURORA_PHASE02_BAYER_QUALIFICATION_PREVIEW"),
            )
            .put("staticMetadata", staticMetadata)
            .put("captureMetadata", dynamicMetadata)

        val metadataBytes = metadata.toString().toByteArray(Charsets.UTF_8)
        val canonicalFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeMediaStore(context, fileName, metadataBytes, packet.bytes)
            File("/${CameraStoragePolicy.CANONICAL_RAW_RELATIVE_PATH}", fileName)
        } else {
            writeLegacyAppSpecific(context, fileName, metadataBytes, packet.bytes)
        }

        // RAW persistence is the transaction that matters. Preview rendering happens only after the
        // immutable source record has been safely finalized, and a renderer failure never deletes or
        // invalidates the canonical AURAW file.
        val previewFile = runCatching {
            AuroraRawPreviewRenderer.renderAndPublish(
                context = context,
                fileName = previewFileName,
                packet = packet,
                staticMetadata = staticMetadata,
                dynamicMetadata = dynamicMetadata,
            )
        }.getOrNull()

        return RawCaptureRecord(
            file = previewFile ?: canonicalFile,
            canonicalFile = canonicalFile,
            previewFile = previewFile,
            cameraId = lens.cameraId,
            accessPath = lens.accessPath,
            width = packet.width,
            height = packet.height,
            timestampNs = packet.timestampNs,
            payloadSha256 = digest,
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun writeMediaStore(
        context: Context,
        fileName: String,
        metadataBytes: ByteArray,
        payload: ByteArray,
    ) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE_AURAW)
            put(MediaStore.MediaColumns.RELATIVE_PATH, CameraStoragePolicy.CANONICAL_RAW_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore Files refused canonical RAW destination")

        try {
            val stream = resolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("Unable to open canonical RAW destination")
            DataOutputStream(BufferedOutputStream(stream)).use { output ->
                writeContainer(output, metadataBytes, payload)
            }

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val updated = resolver.update(uri, publishValues, null, null)
            check(updated == 1) { "Unable to publish completed RAW master" }
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun writeLegacyAppSpecific(
        context: Context,
        fileName: String,
        metadataBytes: ByteArray,
        payload: ByteArray,
    ): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val directory = File(external ?: context.filesDir, "Camera/RAW")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create ${directory.absolutePath}")
        }
        val destination = File(directory, fileName)
        val temporary = File(directory, ".${destination.name}.tmp")

        DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
            writeContainer(output, metadataBytes, payload)
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IllegalStateException("Unable to replace ${destination.absolutePath}")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("Unable to finalize ${destination.absolutePath}")
        }
        return destination
    }

    private fun writeContainer(
        output: DataOutputStream,
        metadataBytes: ByteArray,
        payload: ByteArray,
    ) {
        output.write(magic)
        output.writeInt(metadataBytes.size)
        output.writeLong(payload.size.toLong())
        output.write(metadataBytes)
        output.write(payload)
        output.flush()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
