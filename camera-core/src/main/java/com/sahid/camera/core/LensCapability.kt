package com.sahid.camera.core

import android.hardware.camera2.CameraCharacteristics
import android.util.Size

enum class CameraAccessPath {
    JAVA_DIRECT,
    NDK_DIRECT,
    PHYSICAL_VIA_LOGICAL,
}

enum class CameraDiscoverySource {
    JAVA_DIRECT,
    NDK_DIRECT,
    LOGICAL_PHYSICAL,
    HIDDEN_ID_PROBE,
    DEEP_OPEN_PROBE,
}

data class LensCapability(
    /** The lens/camera identity the user ultimately wants to address. */
    val cameraId: String,
    /** CameraDevice ID that must be opened for this access path. */
    val logicalCameraId: String,
    /** Physical child ID routed with OutputConfiguration when required. */
    val physicalCameraId: String?,
    val accessPath: CameraAccessPath,
    val discoverySources: Set<CameraDiscoverySource>,
    val facing: Int?,
    val displayName: String,
    val focalLengthMm: Float?,
    val sensorWidthMm: Float?,
    val sensorHeightMm: Float?,
    val horizontalFovDegrees: Float?,
    val rawSupported: Boolean,
    val rawSizes: List<Size>,
    val previewSizes: List<Size>,
    val yuvSizes: List<Size>,
    val manualSensor: Boolean,
    val burstCapture: Boolean,
    val maxResolutionSensor: Boolean,
    val isLogicalMultiCamera: Boolean,
    /** Metadata hint only. It is never sufficient to expose a lens. */
    val usableForPreview: Boolean,
    val nativeHardwareLevel: Int? = null,
    val nativeCharacteristicsStatus: Int? = null,
    val qualification: LensQualification = LensQualification.unqualified(),
) {
    val stableId: String = buildString {
        append(cameraId)
        append('@')
        append(accessPath.name)
        if (physicalCameraId != null) {
            append(':')
            append(logicalCameraId)
        }
    }

    val openCameraId: String
        get() = logicalCameraId

    val isFrontFacing: Boolean
        get() = facing == CameraCharacteristics.LENS_FACING_FRONT

    val deepOpenDiscovered: Boolean
        get() = CameraDiscoverySource.DEEP_OPEN_PROBE in discoverySources

    /**
     * A lens is visible only when Camera has a renderer for the qualified output.
     * Surface/PRIVATE preview is preferred; YUV has an explicit CPU fallback renderer.
     * RAW-only evidence remains diagnostic until Aurora's RAW preview renderer lands.
     */
    val userVisible: Boolean
        get() = qualification.previewSessionQualified || qualification.yuvSessionQualified

    val rawUsable: Boolean
        get() = rawSupported && qualification.rawSessionQualified
}
