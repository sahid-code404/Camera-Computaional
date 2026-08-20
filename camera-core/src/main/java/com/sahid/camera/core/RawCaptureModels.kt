package com.sahid.camera.core

import java.io.File

data class RawCaptureRecord(
    val file: File,
    val cameraId: String,
    val accessPath: CameraAccessPath,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
)

sealed interface RawCaptureOutcome {
    data class Success(val record: RawCaptureRecord) : RawCaptureOutcome
    data class Unsupported(val reason: String) : RawCaptureOutcome
    data class Failure(val reason: String, val cause: Throwable? = null) : RawCaptureOutcome
}
