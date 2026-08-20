package com.sahid.camera.core

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Zero-scan startup path.
 *
 * With a learned map this reads SharedPreferences only. On a brand-new ROM/install it does the
 * minimum public Camera2 work necessary to start one usable camera: walk advertised IDs until a
 * rear-facing preview route is found, then stop. ProgressiveLensDiscovery fills every other public,
 * NDK and metadata-hidden candidate after the preview has already started.
 */
data class InstantLensBootstrapResult(
    val lenses: List<LensCapability>,
    val learned: Boolean,
)

object InstantLensBootstrap {
    fun load(context: Context): InstantLensBootstrapResult {
        val appContext = context.applicationContext
        val learned = LearnedLensStore(appContext).load().routes
        if (learned.isNotEmpty()) {
            return InstantLensBootstrapResult(
                lenses = learned.sortedWith(compareBy(::cameraIdSortKey)),
                learned = true,
            )
        }

        val manager = appContext.getSystemService(CameraManager::class.java)
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        var firstUsable: LensCapability? = null

        for (cameraId in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
                ?: continue
            val hint = buildHint(cameraId, chars) ?: continue
            if (firstUsable == null) firstUsable = hint
            if (!hint.isFrontFacing) {
                return InstantLensBootstrapResult(listOf(hint), learned = false)
            }
        }

        return InstantLensBootstrapResult(
            lenses = listOfNotNull(firstUsable),
            learned = false,
        )
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
            horizontalFovDegrees = null,
            rawSupported = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities,
            rawSizes = rawSizes,
            previewSizes = previewSizes,
            yuvSizes = yuvSizes,
            manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities,
            burstCapture = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities,
            maxResolutionSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in capabilities,
            isLogicalMultiCamera = chars.physicalCameraIds.isNotEmpty(),
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

    private fun cameraIdSortKey(lens: LensCapability): Int =
        lens.cameraId.toIntOrNull() ?: Int.MAX_VALUE
}
