package com.sahid.camera.core

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.PI
import kotlin.math.atan

/**
 * Zero-scan startup path.
 *
 * With a learned map this reads SharedPreferences only. Useful metadata profiles discovered on a
 * previous launch are restored from a separate candidate cache and collapsed into lens families;
 * aliases remain in the cache but only each family's default route reaches the normal selector.
 *
 * On a brand-new ROM/install it does the minimum public Camera2 work necessary to start one usable
 * rear camera; ProgressiveLensDiscovery fills the remaining family map after preview startup.
 */
data class InstantLensBootstrapResult(
    val lenses: List<LensCapability>,
    val learned: Boolean,
)

object InstantLensBootstrap {
    fun load(context: Context): InstantLensBootstrapResult {
        val appContext = context.applicationContext
        val learned = LearnedLensStore(appContext).load().routes
        val readyCandidates = CandidateLensStore(appContext).load()

        if (learned.isNotEmpty()) {
            return InstantLensBootstrapResult(
                lenses = LensFamilyResolver.defaultsForSelector(learned + readyCandidates),
                learned = true,
            )
        }

        val primaryHint = findPrimaryPublicHint(appContext)
        if (readyCandidates.isNotEmpty()) {
            return InstantLensBootstrapResult(
                lenses = LensFamilyResolver.defaultsForSelector(listOfNotNull(primaryHint) + readyCandidates),
                learned = false,
            )
        }

        return InstantLensBootstrapResult(
            lenses = listOfNotNull(primaryHint),
            learned = false,
        )
    }

    private fun findPrimaryPublicHint(context: Context): LensCapability? {
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        var firstUsable: LensCapability? = null

        for (cameraId in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
                ?: continue
            val hint = buildHint(cameraId, chars) ?: continue
            if (firstUsable == null) firstUsable = hint
            if (!hint.isFrontFacing) return hint
        }

        return firstUsable
    }

    private fun buildHint(
        cameraId: String,
        chars: CameraCharacteristics,
    ): LensCapability? {
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val previewSizes = streamMap
            .getOutputSizes(SurfaceTexture::class.java)
            ?.toList()
            .orEmpty()
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
        val yuvSizes = streamMap
            .getOutputSizes(ImageFormat.YUV_420_888)
            ?.toList()
            .orEmpty()
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
        if (previewSizes.isEmpty() && yuvSizes.isEmpty()) return null

        val rawSizes = streamMap
            .getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.toList()
            .orEmpty()
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
        val capabilities = chars
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            .orEmpty()
        val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalLength = chars
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull()

        return LensCapability(
            cameraId = cameraId,
            logicalCameraId = cameraId,
            physicalCameraId = null,
            accessPath = CameraAccessPath.JAVA_DIRECT,
            discoverySources = setOf(CameraDiscoverySource.JAVA_DIRECT),
            facing = chars.get(CameraCharacteristics.LENS_FACING),
            displayName = "ID $cameraId",
            focalLengthMm = focalLength,
            sensorWidthMm = physicalSize?.width,
            sensorHeightMm = physicalSize?.height,
            horizontalFovDegrees = horizontalFov(physicalSize?.width, focalLength),
            rawSupported = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities,
            rawSizes = rawSizes,
            previewSizes = previewSizes,
            yuvSizes = yuvSizes,
            manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities,
            burstCapture = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities,
            maxResolutionSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in capabilities,
            isLogicalMultiCamera = chars.physicalCameraIds.isNotEmpty(),
            logicalPhysicalIds = chars.physicalCameraIds,
            usableForPreview = true,
            qualification = LensQualification(
                accessPathOpenQualified = false,
                previewSessionQualified = previewSizes.isNotEmpty(),
                yuvSessionQualified = previewSizes.isEmpty() && yuvSizes.isNotEmpty(),
                rawSessionQualified = false,
                qualifiedRawSize = null,
                detail = "Cold-start primary hint; first live frame validates and caches route",
            ),
        )
    }

    private fun horizontalFov(sensorWidthMm: Float?, focalMm: Float?): Float? {
        if (sensorWidthMm == null || focalMm == null || sensorWidthMm <= 0f || focalMm <= 0f) return null
        return (2.0 * atan(sensorWidthMm / (2.0 * focalMm)) * 180.0 / PI).toFloat()
    }
}
