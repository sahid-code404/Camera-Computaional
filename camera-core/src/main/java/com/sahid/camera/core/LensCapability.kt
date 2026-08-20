package com.sahid.camera.core

import android.hardware.camera2.CameraCharacteristics
import android.util.Size

data class LensCapability(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val facing: Int?,
    val displayName: String,
    val focalLengthMm: Float?,
    val rawSupported: Boolean,
    val rawSizes: List<Size>,
    val previewSizes: List<Size>,
    val manualSensor: Boolean,
    val burstCapture: Boolean,
    val maxResolutionSensor: Boolean,
    val isLogicalMultiCamera: Boolean,
    val usableForPreview: Boolean,
) {
    val stableId: String = buildString {
        append(logicalCameraId)
        append(':')
        append(physicalCameraId ?: "logical")
    }

    val isFrontFacing: Boolean
        get() = facing == CameraCharacteristics.LENS_FACING_FRONT
}
