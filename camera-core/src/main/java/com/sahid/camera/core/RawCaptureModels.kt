package com.sahid.camera.core

import android.graphics.ImageFormat
import android.media.Image
import java.io.File

data class RawImagePacket(
    val width: Int,
    val height: Int,
    val imageFormat: Int,
    val timestampNs: Long,
    val rowStride: Int,
    val pixelStride: Int,
    val bytes: ByteArray,
) {
    init {
        require(imageFormat == ImageFormat.RAW_SENSOR) { "Aurora Phase 02 accepts RAW_SENSOR only" }
        require(width > 0 && height > 0)
        require(rowStride > 0 && pixelStride > 0)
        require(bytes.isNotEmpty())
    }

    companion object {
        fun copyFrom(image: Image): RawImagePacket {
            require(image.format == ImageFormat.RAW_SENSOR) { "Expected RAW_SENSOR, got ${image.format}" }
            require(image.planes.size == 1) { "RAW_SENSOR must expose exactly one plane" }
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate()
            val payload = ByteArray(buffer.remaining())
            buffer.get(payload)
            return RawImagePacket(
                width = image.width,
                height = image.height,
                imageFormat = image.format,
                timestampNs = image.timestamp,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                bytes = payload,
            )
        }
    }
}

data class RawCaptureRecord(
    val file: File,
    val cameraId: String,
    val accessPath: CameraAccessPath,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val payloadSha256: String,
)

sealed interface RawCaptureOutcome {
    data class Success(val record: RawCaptureRecord) : RawCaptureOutcome
    data class Unsupported(val reason: String) : RawCaptureOutcome
    data class Failure(val reason: String, val cause: Throwable? = null) : RawCaptureOutcome
}
