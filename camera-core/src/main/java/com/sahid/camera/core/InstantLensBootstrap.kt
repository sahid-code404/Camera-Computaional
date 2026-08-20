package com.sahid.camera.core

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Zero-scan startup path.
 *
 * If Camera already learned working routes on this ROM, this class only reads SharedPreferences
 * and returns those routes. It does not touch CameraManager, NDK enumeration, hidden-ID scanning,
 * or session qualification.
 *
 * On a brand-new install there is no learned map yet. In that case we perform only the standard
 * Java Camera2 advertised-ID metadata lookup so the first public camera can be opened immediately.
 * These cold-start entries are hints, never persisted as proven routes until the live preview
 * actually produces a frame.
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
        val hints = ids.mapNotNull { cameraId ->
            val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
                ?: return@mapNotNull null
            val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSizes = streamMap
                ?.getOutputSizes(SurfaceTexture::class.java)
                ?.toList()
                .orEmpty()
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
            val yuvSizes = streamMap
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.toList()
                .orEmpty()
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
            if (previewSizes.isEmpty() && yuvSizes.isEmpty()) return@mapNotNull null

            val rawSizes = streamMap
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
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

            LensCapability(
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
                    detail = "Cold-start public hint; live frame validates and caches this route",
                ),
            )
        }.sortedWith(compareBy(::cameraIdSortKey))

        return InstantLensBootstrapResult(hints, learned = false)
    }

    private fun cameraIdSortKey(lens: LensCapability): Int =
        lens.cameraId.toIntOrNull() ?: Int.MAX_VALUE
}
