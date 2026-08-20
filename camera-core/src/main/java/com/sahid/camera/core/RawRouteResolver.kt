package com.sahid.camera.core

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

/**
 * Resolves the best standards-correct DNG capture profile for a selected user-facing lens family.
 *
 * Preview and DNG are intentionally allowed to use different access routes. In particular, an aux
 * lens may preview through NDK_DIRECT while DNG capture is routed through a logical Camera2 parent
 * using OutputConfiguration#setPhysicalCameraId. RAW-only physical profiles are valid even when
 * they cannot render preview and therefore never appear as selector buttons.
 */
class RawRouteResolver(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)

    fun resolve(selected: LensCapability): LensCapability? {
        val cachedRoutes = buildList {
            add(selected)
            addAll(LearnedLensStore(appContext).load().routes)
            addAll(CandidateLensStore(appContext).load())
        }

        val visibleFamily = LensFamilyResolver.resolve(cachedRoutes)
            .firstOrNull { family ->
                family.routes.any { route ->
                    route.stableId == selected.stableId || route.cameraId == selected.cameraId
                }
            }
        val familyTargetIds = buildSet {
            add(selected.cameraId)
            visibleFamily?.routes?.forEach { route ->
                add(route.cameraId)
                route.physicalCameraId?.let(::add)
            }
        }

        // Metadata caches from older builds may not contain RAW-only parent/physical profiles. Find
        // them on demand from public and cached logical topology. This is metadata-only; no camera is
        // opened until the user actually presses the shutter.
        val physicalProfiles = discoverPhysicalRawProfiles(selected, cachedRoutes)
        val allRoutes = (cachedRoutes + physicalProfiles)
            .distinctBy { it.stableId to it.discoverySources }

        val candidates = allRoutes.filter { route ->
            route.rawSupported &&
                route.rawSizes.isNotEmpty() &&
                route.accessPath != CameraAccessPath.NDK_DIRECT &&
                (route.cameraId in familyTargetIds || route.physicalCameraId in familyTargetIds)
        }
        if (candidates.isEmpty()) return null

        return candidates.minWithOrNull(
            compareBy<LensCapability> { route ->
                // A physical-via-logical profile is preferred for an NDK-only aux because it targets
                // the actual child while still providing Camera2 CaptureResult/Characteristics for DNG.
                when {
                    selected.accessPath == CameraAccessPath.NDK_DIRECT &&
                        route.accessPath == CameraAccessPath.PHYSICAL_VIA_LOGICAL -> 0
                    route.accessPath == CameraAccessPath.JAVA_DIRECT -> 1
                    route.accessPath == CameraAccessPath.PHYSICAL_VIA_LOGICAL -> 2
                    else -> 3
                }
            }.thenBy { route -> if (route.learnedFromCache) 0 else 1 }
                .thenByDescending { route ->
                    route.rawSizes.maxOfOrNull { it.width.toLong() * it.height.toLong() } ?: 0L
                }
        )
    }

    private fun discoverPhysicalRawProfiles(
        selected: LensCapability,
        cachedRoutes: List<LensCapability>,
    ): List<LensCapability> {
        val childId = selected.cameraId
        val parentIds = linkedSetOf<String>()

        cachedRoutes.forEach { route ->
            if (childId in route.logicalPhysicalIds) parentIds += route.cameraId
            if (route.physicalCameraId == childId) parentIds += route.logicalCameraId
        }

        runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList()).forEach { parentId ->
            val chars = runCatching { manager.getCameraCharacteristics(parentId) }.getOrNull()
                ?: return@forEach
            if (childId in chars.physicalCameraIds) parentIds += parentId
        }

        if (parentIds.isEmpty()) return emptyList()
        val childChars = runCatching { manager.getCameraCharacteristics(childId) }.getOrNull()
            ?: return emptyList()
        val map = childChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        val raw = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
            .sortedByDescending(::area)
        if (raw.isEmpty()) return emptyList()
        val preview = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            .sortedByDescending(::area)
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            .sortedByDescending(::area)
        val capabilities = childChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet().orEmpty()
        val sensor = childChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = childChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()

        return parentIds.mapNotNull { parentId ->
            // Parent metadata may be hidden from CameraManager even when NDK reports it. Keep only
            // parents for which Java Camera2 can obtain characteristics; SingleRawCaptureEngine will
            // perform the real open/session proof at shutter time.
            runCatching { manager.getCameraCharacteristics(parentId) }.getOrNull() ?: return@mapNotNull null
            LensCapability(
                cameraId = childId,
                logicalCameraId = parentId,
                physicalCameraId = childId,
                accessPath = CameraAccessPath.PHYSICAL_VIA_LOGICAL,
                discoverySources = setOf(
                    CameraDiscoverySource.LOGICAL_PHYSICAL,
                    CameraDiscoverySource.AUTO_METADATA,
                ),
                facing = childChars.get(CameraCharacteristics.LENS_FACING) ?: selected.facing,
                displayName = "ID $childId",
                focalLengthMm = focal ?: selected.focalLengthMm,
                sensorWidthMm = sensor?.width ?: selected.sensorWidthMm,
                sensorHeightMm = sensor?.height ?: selected.sensorHeightMm,
                horizontalFovDegrees = selected.horizontalFovDegrees,
                // Some physical characteristics omit the RAW capability bit while still publishing
                // an actual RAW_SENSOR stream table. The stream table is the concrete evidence used.
                rawSupported = raw.isNotEmpty(),
                rawSizes = raw,
                previewSizes = preview,
                yuvSizes = yuv,
                manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities,
                burstCapture = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities,
                maxResolutionSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in capabilities,
                isLogicalMultiCamera = false,
                logicalPhysicalIds = emptySet(),
                usableForPreview = preview.isNotEmpty() || yuv.isNotEmpty(),
                qualification = LensQualification(
                    accessPathOpenQualified = false,
                    previewSessionQualified = preview.isNotEmpty(),
                    yuvSessionQualified = preview.isEmpty() && yuv.isNotEmpty(),
                    rawSessionQualified = false,
                    qualifiedRawSize = null,
                    detail = "RAW-only physical family profile resolved on demand",
                ),
            )
        }
    }

    private fun area(size: Size): Long = size.width.toLong() * size.height.toLong()
}
