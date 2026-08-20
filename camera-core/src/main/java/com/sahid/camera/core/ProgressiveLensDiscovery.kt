package com.sahid.camera.core

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import com.sahid.camera.aurora.AutoHiddenCameraInfo
import com.sahid.camera.aurora.AutoHiddenMetadataEnumerator
import com.sahid.camera.aurora.NativeCameraEnumerator
import com.sahid.camera.aurora.NativeCameraInfo
import kotlin.math.PI
import kotlin.math.atan

/**
 * MotionCam-style progressive discovery.
 *
 * This pass runs after the primary preview has already started. It never opens a camera and never
 * configures a capture session. Java/NDK advertised metadata plus the bounded native numeric
 * metadata view are merged into route profiles. [LensFamilyResolver] then exposes one default route
 * per physical lens family while [CandidateLensStore] retains every alias/profile internally.
 *
 * The expensive metadata pass is performed only once per Build.FINGERPRINT. Later launches restore
 * the complete learned + candidate family map from SharedPreferences and return immediately.
 */
class ProgressiveLensDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val learnedStore = LearnedLensStore(appContext)
    private val candidateStore = CandidateLensStore(appContext)

    fun discover(maxNumericId: Int = AUTO_METADATA_MAX_ID): List<LensCapability> {
        val cachedLearned = learnedStore.load().routes.filter { it.userVisible }
        if (candidateStore.hasCompletedAutoScan()) {
            return LensFamilyResolver.defaultsForSelector(cachedLearned + candidateStore.load())
        }

        val javaIds = runCatching { manager.cameraIdList.toList() }
            .getOrDefault(emptyList())
            .distinct()
        val javaSet = javaIds.toSet()
        val javaChars = javaIds.mapNotNull { id ->
            runCatching { manager.getCameraCharacteristics(id) }.getOrNull()?.let { id to it }
        }.toMap()

        val ndkAdvertised = runCatching { NativeCameraEnumerator.enumerate() }
            .getOrDefault(emptyList())
        val ndkAdvertisedSet = ndkAdvertised.map { it.id }.toSet()
        val autoNative = AutoHiddenMetadataEnumerator.scan(maxNumericId)

        val candidates = mutableListOf<LensCapability>()

        javaIds.forEach javaLoop@ { id ->
            val chars = javaChars[id] ?: return@javaLoop
            javaCandidate(
                id = id,
                chars = chars,
                alsoNdk = id in ndkAdvertisedSet,
            )?.let(candidates::add)
        }

        ndkAdvertised.forEach { native ->
            if (native.id !in javaSet) nativeCandidate(native, hidden = false)?.let(candidates::add)
        }

        autoNative.forEach autoLoop@ { info ->
            if (info.id in javaSet || info.id in ndkAdvertisedSet) return@autoLoop
            autoNativeCandidate(info)?.let(candidates::add)
        }

        // Java logical/physical topology is metadata-only. A child becomes another access profile
        // for the child's family, never another user-facing lens when the child also has a direct
        // route.
        javaChars.forEach { (logicalId, logicalChars) ->
            logicalChars.physicalCameraIds.forEach physicalLoop@ { physicalId ->
                val childChars = runCatching { manager.getCameraCharacteristics(physicalId) }.getOrNull()
                    ?: return@physicalLoop
                javaPhysicalCandidate(logicalId, physicalId, childChars)?.let(candidates::add)
            }
        }

        // Keep a preferred metadata route per exact endpoint for the automatic selector. Alternate
        // access routes for the same camera ID are still retained when they are frame-proven by the
        // learned store or discovered through explicit physical routing above.
        val preferredMetadataRoutes = candidates
            .filter { it.userVisible }
            .groupBy { it.cameraId }
            .values
            .mapNotNull { routes -> routes.minByOrNull(::routeScore) }

        val learnedRoutes = cachedLearned
        val learnedIds = learnedRoutes.mapTo(mutableSetOf()) { it.cameraId }

        // Persist ALL unproven endpoint profiles before family collapsing. This is the critical
        // difference from the old dedup cache: aliases survive restart and remain available for
        // fallback/debug even though the normal selector shows only each family's default route.
        candidateStore.replace(
            preferredMetadataRoutes.filter { route -> route.cameraId !in learnedIds },
            autoScanCompleted = true,
        )

        return LensFamilyResolver.defaultsForSelector(learnedRoutes + preferredMetadataRoutes)
    }

    private fun javaCandidate(
        id: String,
        chars: CameraCharacteristics,
        alsoNdk: Boolean,
    ): LensCapability? = javaCapability(
        cameraId = id,
        logicalCameraId = id,
        physicalCameraId = null,
        chars = chars,
        accessPath = CameraAccessPath.JAVA_DIRECT,
        sources = buildSet {
            add(CameraDiscoverySource.JAVA_DIRECT)
            add(CameraDiscoverySource.AUTO_METADATA)
            if (alsoNdk) add(CameraDiscoverySource.NDK_DIRECT)
        },
        logical = chars.physicalCameraIds.isNotEmpty(),
        logicalPhysicalIds = chars.physicalCameraIds,
    )

    private fun javaPhysicalCandidate(
        logicalId: String,
        physicalId: String,
        chars: CameraCharacteristics,
    ): LensCapability? = javaCapability(
        cameraId = physicalId,
        logicalCameraId = logicalId,
        physicalCameraId = physicalId,
        chars = chars,
        accessPath = CameraAccessPath.PHYSICAL_VIA_LOGICAL,
        sources = setOf(
            CameraDiscoverySource.LOGICAL_PHYSICAL,
            CameraDiscoverySource.AUTO_METADATA,
        ),
        logical = chars.physicalCameraIds.isNotEmpty(),
        logicalPhysicalIds = emptySet(),
    )

    private fun javaCapability(
        cameraId: String,
        logicalCameraId: String,
        physicalCameraId: String?,
        chars: CameraCharacteristics,
        accessPath: CameraAccessPath,
        sources: Set<CameraDiscoverySource>,
        logical: Boolean,
        logicalPhysicalIds: Set<String>,
    ): LensCapability? {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val preview = map.getOutputSizes(SurfaceTexture::class.java)
            ?.toList().orEmpty().sortedByDescending(::area)
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.toList().orEmpty().sortedByDescending(::area)
        if (preview.isEmpty() && yuv.isEmpty()) return null
        val raw = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.toList().orEmpty().sortedByDescending(::area)
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet().orEmpty()
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
        return LensCapability(
            cameraId = cameraId,
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            accessPath = accessPath,
            discoverySources = sources,
            facing = chars.get(CameraCharacteristics.LENS_FACING),
            displayName = "ID $cameraId",
            focalLengthMm = focal,
            sensorWidthMm = sensor?.width,
            sensorHeightMm = sensor?.height,
            horizontalFovDegrees = horizontalFov(sensor?.width, focal),
            rawSupported = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities,
            rawSizes = raw,
            previewSizes = preview,
            yuvSizes = yuv,
            manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities,
            burstCapture = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities,
            maxResolutionSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in capabilities,
            isLogicalMultiCamera = logical,
            logicalPhysicalIds = logicalPhysicalIds,
            usableForPreview = true,
            qualification = optimisticQualification(preview, yuv),
        )
    }

    private fun nativeCandidate(native: NativeCameraInfo, hidden: Boolean): LensCapability? {
        val preview = native.privateOutputSizes.map { Size(it.width, it.height) }
        val yuv = native.yuvOutputSizes.map { Size(it.width, it.height) }
        if (preview.isEmpty() && yuv.isEmpty()) return null
        return LensCapability(
            cameraId = native.id,
            logicalCameraId = native.id,
            physicalCameraId = null,
            accessPath = CameraAccessPath.NDK_DIRECT,
            discoverySources = buildSet {
                add(CameraDiscoverySource.NDK_DIRECT)
                add(CameraDiscoverySource.AUTO_METADATA)
                if (hidden) add(CameraDiscoverySource.HIDDEN_ID_PROBE)
            },
            facing = native.facing,
            displayName = "ID ${native.id}",
            focalLengthMm = native.focalLengthMm,
            sensorWidthMm = native.sensorWidthMm,
            sensorHeightMm = native.sensorHeightMm,
            horizontalFovDegrees = horizontalFov(native.sensorWidthMm, native.focalLengthMm),
            rawSupported = native.rawCapability,
            rawSizes = native.rawOutputSizes.map { Size(it.width, it.height) },
            previewSizes = preview,
            yuvSizes = yuv,
            manualSensor = false,
            burstCapture = false,
            maxResolutionSensor = false,
            isLogicalMultiCamera = native.logicalMultiCamera,
            logicalPhysicalIds = native.physicalIds.toSet(),
            usableForPreview = true,
            nativeHardwareLevel = native.hardwareLevel,
            nativeCharacteristicsStatus = native.characteristicsStatus,
            qualification = optimisticQualification(preview, yuv),
        )
    }

    private fun autoNativeCandidate(info: AutoHiddenCameraInfo): LensCapability? {
        val preview = info.privateOutputSizes.map { Size(it.width, it.height) }
        val yuv = info.yuvOutputSizes.map { Size(it.width, it.height) }
        if (preview.isEmpty() && yuv.isEmpty()) return null
        return LensCapability(
            cameraId = info.id,
            logicalCameraId = info.id,
            physicalCameraId = null,
            accessPath = CameraAccessPath.NDK_DIRECT,
            discoverySources = buildSet {
                add(CameraDiscoverySource.NDK_DIRECT)
                add(CameraDiscoverySource.AUTO_METADATA)
                if (!info.advertised) add(CameraDiscoverySource.HIDDEN_ID_PROBE)
            },
            facing = info.facing,
            displayName = "ID ${info.id}",
            focalLengthMm = info.focalLengthMm,
            sensorWidthMm = info.sensorWidthMm,
            sensorHeightMm = info.sensorHeightMm,
            horizontalFovDegrees = horizontalFov(info.sensorWidthMm, info.focalLengthMm),
            rawSupported = info.rawCapability,
            rawSizes = info.rawOutputSizes.map { Size(it.width, it.height) },
            previewSizes = preview,
            yuvSizes = yuv,
            manualSensor = false,
            burstCapture = false,
            maxResolutionSensor = false,
            isLogicalMultiCamera = info.logicalMultiCamera,
            logicalPhysicalIds = info.physicalIds.toSet(),
            usableForPreview = true,
            qualification = optimisticQualification(preview, yuv),
        )
    }

    private fun optimisticQualification(preview: List<Size>, yuv: List<Size>) = LensQualification(
        accessPathOpenQualified = false,
        previewSessionQualified = preview.isNotEmpty(),
        yuvSessionQualified = preview.isEmpty() && yuv.isNotEmpty(),
        rawSessionQualified = false,
        qualifiedRawSize = null,
        detail = "Automatic metadata profile; first live frame proves route",
    )

    private fun horizontalFov(sensorWidthMm: Float?, focalMm: Float?): Float? {
        if (sensorWidthMm == null || focalMm == null || sensorWidthMm <= 0f || focalMm <= 0f) return null
        return (2.0 * atan(sensorWidthMm / (2.0 * focalMm)) * 180.0 / PI).toFloat()
    }

    private fun routeScore(lens: LensCapability): Int = when (lens.accessPath) {
        CameraAccessPath.JAVA_DIRECT -> 0
        CameraAccessPath.NDK_DIRECT -> 10
        CameraAccessPath.PHYSICAL_VIA_LOGICAL -> 20
    }

    private fun area(size: Size): Long = size.width.toLong() * size.height.toLong()

    private companion object {
        const val AUTO_METADATA_MAX_ID = 255
    }
}
