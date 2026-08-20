package com.sahid.camera.core

import android.content.Context
import android.graphics.ImageFormat
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aurora's canonical Phase-02 source record.
 *
 * The RAW payload is copied byte-for-byte from the single RAW_SENSOR Image plane. Aurora does not
 * demosaic, tone-map, compress, reinterpret, or convert the sensor samples before persistence.
 * Row/pixel stride are stored in the JSON header so padding remains unambiguous.
 *
 * Container v1 layout (big-endian scalar fields):
 *   8 bytes  magic = AURAW\0\1\0
 *   int32    JSON metadata byte length
 *   int64    RAW payload byte length
 *   N bytes  UTF-8 JSON metadata
 *   M bytes  exact RAW plane payload
 */
object AuroraRawWriter {
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
        val metadata = JSONObject()
            .put("container", "AURAW")
            .put("containerVersion", 1)
            .put("canonicalSource", true)
            .put("rawPayloadEncoding", "ANDROID_RAW_SENSOR_PLANE_BYTES")
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
            .put("staticMetadata", staticMetadata)
            .put("captureMetadata", dynamicMetadata)

        val metadataBytes = metadata.toString().toByteArray(Charsets.UTF_8)
        val directory = rawDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create ${directory.absolutePath}")
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val lensId = lens.cameraId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(directory, "AURORA_${stamp}_ID-${lensId}.auraw")
        val temporary = File(directory, ".${destination.name}.tmp")

        DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
            output.write(magic)
            output.writeInt(metadataBytes.size)
            output.writeLong(packet.bytes.size.toLong())
            output.write(metadataBytes)
            output.write(packet.bytes)
            output.flush()
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IllegalStateException("Unable to replace ${destination.absolutePath}")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("Unable to finalize ${destination.absolutePath}")
        }

        return RawCaptureRecord(
            file = destination,
            cameraId = lens.cameraId,
            accessPath = lens.accessPath,
            width = packet.width,
            height = packet.height,
            timestampNs = packet.timestampNs,
            payloadSha256 = digest,
        )
    }

    private fun rawDirectory(context: Context): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File(external ?: context.filesDir, "Aurora/RAW")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
