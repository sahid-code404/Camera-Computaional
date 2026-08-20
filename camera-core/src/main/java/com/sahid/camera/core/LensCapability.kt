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
    /** Cheap background metadata candidate; first live frame still proves the route. */
    AUTO_METADATA,
    /** Persisted metadata candidate. It is remembered across launches but is not frame-proven. */
    CANDIDATE_CACHE,
    /** Per-build route learned from a previous real-frame qualification. */
    LEARNED_CACHE,
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
    /**
     * Physical children explicitly reported by this logical camera endpoint. Keeping this on the
     * route lets the selector build MotionCam-style lens families after a restart without having
     * to enumerate Camera2 again.
     */
    val logicalPhysicalIds: Set<String> = emptySet(),
    /** Metadata hint only. It is never sufficient to persist a learned route. */
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

    val learnedFromCache: Boolean
        get() = CameraDiscoverySource.LEARNED_CACHE in discoverySources

    val automaticMetadataCandidate: Boolean
        get() = CameraDiscoverySource.AUTO_METADATA in discoverySources

    val persistedCandidate: Boolean
        get() = CameraDiscoverySource.CANDIDATE_CACHE in discoverySources

    /**
     * The live controller can render PRIVATE or YUV routes. For automatic metadata candidates,
     * these flags are optimistic stream hints only; the candidate is persisted as proven only
     * after an actual TextureView/ImageReader frame is observed.
     */
    val userVisible: Boolean
        get() = qualification.previewSessionQualified || qualification.yuvSessionQualified

    val rawUsable: Boolean
        get() = rawSupported && qualification.rawSessionQualified
}
