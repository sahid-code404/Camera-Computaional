package com.sahid.camera.core

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import com.sahid.camera.aurora.NativeCameraEnumerator
import com.sahid.camera.aurora.NativeCameraInfo

/**
 * Phase-01 universal camera discovery and runtime qualification.
 *
 * Candidate discovery intentionally combines three independent views:
 *  1. Java CameraManager.cameraIdList
 *  2. NDK ACameraManager_getCameraIdList
 *  3. logical-camera physicalCameraIds topology
 *
 * No candidate is discarded simply because SurfaceTexture metadata is absent. Static
 * metadata is evidence only; actual open/session tests decide what can be exposed.
 */
class CameraCapabilityProbe(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val qualificationStore = LensQualificationStore(appContext)

    /** Metadata-only view for diagnostics. It is not allowed to drive final UI exposure. */
    fun probeUsableLenses(): List<LensCapability> {
        val discovery = discoverCandidates()
        return assignUserFacingNames(selectPreferredAccessPaths(discovery.candidates))
    }

    fun probeQualifiedLenses(
        onProgress: ((completed: Int, total: Int, lens: LensCapability) -> Unit)? = null,
    ): List<LensCapability> = probeQualificationReport(onProgress).visibleLenses

    fun probeQualificationReport(
        onProgress: ((completed: Int, total: Int, lens: LensCapability) -> Unit)? = null,
    ): CameraQualificationReport {
        val discovery = discoverCandidates()
        if (discovery.candidates.isEmpty()) {
            return CameraQualificationReport(
                discovery = discovery.snapshot,
                candidates = emptyList(),
                visibleLenses = emptyList(),
            ).also(qualificationStore::save)
        }

        val qualified = CameraSessionQualifier(appContext).use { qualifier ->
            discovery.candidates.mapIndexed { index, lens ->
                qualifier.qualify(lens).also {
                    onProgress?.invoke(index + 1, discovery.candidates.size, it)
                }
            }
        }

        // The same physical lens can be reachable through more than one path. Prefer a
        // successful direct path, but preserve all paths in diagnostics for evidence.
        val preferredVisible = qualified
            .filter { it.userVisible }
            .groupBy { it.cameraId }
            .values
            .mapNotNull { paths -> paths.minByOrNull { accessPriority(it.accessPath) } }

        val namedVisible = assignUserFacingNames(preferredVisible.map { it.copy(displayName = "Lens") })
        val visibleNamesByCameraId = namedVisible.associate { it.cameraId to it.displayName }
        val namedCandidates = qualified.map { lens ->
            lens.copy(displayName = visibleNamesByCameraId[lens.cameraId] ?: lens.displayName)
        }

        return CameraQualificationReport(
            discovery = discovery.snapshot,
            candidates = namedCandidates,
            visibleLenses = namedVisible,
        ).also(qualificationStore::save)
    }

    private fun discoverCandidates(): DiscoveryResult {
        val javaDirectIds = runCatching { manager.cameraIdList.toList() }
            .getOrDefault(emptyList())
            .distinct()
            .sorted()
        val javaDirectSet = javaDirectIds.toSet()

        val ndkInfos = runCatching { NativeCameraEnumerator.enumerate() }
            .getOrDefault(emptyList())
        val ndkById = ndkInfos.associateBy { it.id }
        val ndkDirectIds = ndkInfos.map { it.id }.distinct().sorted()
        val ndkDirectSet = ndkDirectIds.toSet()

        val logicalTopology = javaDirectIds.mapNotNull { logicalId ->
            val children = safeCharacteristics(logicalId)
                ?.physicalCameraIds
                ?.toList()
                ?.distinct()
                ?.sorted()
                .orEmpty()
            if (children.isEmpty()) null else logicalId to children
        }.toMap()

        val candidates = mutableListOf<LensCapability>()
        val allDirectIds = (javaDirectIds + ndkDirectIds).distinct().sorted()

        // Direct IDs are always represented as direct candidates. If an ID is visible to
        // both APIs, Java is the normal session path and NDK remains recorded as a source.
        allDirectIds.forEach { cameraId ->
            val chars = safeCharacteristics(cameraId)
            val native = ndkById[cameraId]
            val sources = buildSet {
                if (cameraId in javaDirectSet) add(CameraDiscoverySource.JAVA_DIRECT)
                if (cameraId in ndkDirectSet) add(CameraDiscoverySource.NDK_DIRECT)
            }
            buildCapability(
                cameraId = cameraId,
                logicalCameraId = cameraId,
                physicalCameraId = null,
                chars = chars,
                native = native,
                accessPath = if (cameraId in javaDirectSet) {
                    CameraAccessPath.JAVA_DIRECT
                } else {
                    CameraAccessPath.NDK_DIRECT
                },
                discoverySources = sources,
                isLogicalMultiCamera = logicalTopology[cameraId].orEmpty().isNotEmpty(),
            )?.let(candidates::add)
        }

        // Keep a physical-via-logical candidate even when the same child is also directly
        // enumerated. Runtime qualification can then prove which access route actually works.
        logicalTopology.forEach { (logicalId, physicalIds) ->
            physicalIds.forEach { physicalId ->
                val sources = buildSet {
                    add(CameraDiscoverySource.LOGICAL_PHYSICAL)
                    if (physicalId in javaDirectSet) add(CameraDiscoverySource.JAVA_DIRECT)
                    if (physicalId in ndkDirectSet) add(CameraDiscoverySource.NDK_DIRECT)
                }
                buildCapability(
                    cameraId = physicalId,
                    logicalCameraId = logicalId,
                    physicalCameraId = physicalId,
                    chars = safeCharacteristics(physicalId),
                    native = ndkById[physicalId],
                    accessPath = CameraAccessPath.PHYSICAL_VIA_LOGICAL,
                    discoverySources = sources,
                    isLogicalMultiCamera = true,
                )?.let(candidates::add)
            }
        }

        return DiscoveryResult(
            snapshot = CameraDiscoverySnapshot(
                javaDirectIds = javaDirectIds,
                ndkDirectIds = ndkDirectIds,
                logicalTopology = logicalTopology,
            ),
            candidates = candidates.distinctBy { it.stableId },
        )
    }

    private fun selectPreferredAccessPaths(items: List<LensCapability>): List<LensCapability> =
        items.groupBy { it.cameraId }
            .values
            .mapNotNull { paths -> paths.minByOrNull { accessPriority(it.accessPath) } }

    private fun buildCapability(
        cameraId: String,
        logicalCameraId: String,
        physicalCameraId: String?,
        chars: CameraCharacteristics?,
        native: NativeCameraInfo?,
        accessPath: CameraAccessPath,
        discoverySources: Set<CameraDiscoverySource>,
        isLogicalMultiCamera: Boolean,
    ): LensCapability? {
        if (chars == null && native == null) return null

        val streamMap = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val javaPreviewSizes = streamMap
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.toList()
            .orEmpty()
        val javaYuvSizes = streamMap
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.toList()
            .orEmpty()
        val javaRawSizes = streamMap
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.toList()
            .orEmpty()

        // NDK PRIVATE outputs are implementation-defined camera outputs and are useful as
        // candidate preview sizes when OEM Java metadata is incomplete or oddly filtered.
        val previewSizes = mergeSizes(
            javaPreviewSizes,
            native?.privateOutputSizes.orEmpty().map { Size(it.width, it.height) },
        )
        val yuvSizes = mergeSizes(
            javaYuvSizes,
            native?.yuvOutputSizes.orEmpty().map { Size(it.width, it.height) },
        )
        val rawSizes = mergeSizes(
            javaRawSizes,
            native?.rawOutputSizes.orEmpty().map { Size(it.width, it.height) },
        )

        val capabilities = chars
            ?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            .orEmpty()
        val rawAdvertisedByJava =
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
        val rawSupported = rawAdvertisedByJava || native?.rawCapability == true

        return LensCapability(
            cameraId = cameraId,
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            accessPath = accessPath,
            discoverySources = discoverySources,
            facing = chars?.get(CameraCharacteristics.LENS_FACING) ?: native?.facing,
            displayName = "Lens $cameraId",
            focalLengthMm = chars
                ?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull()
                ?: native?.focalLengthMm,
            rawSupported = rawSupported,
            rawSizes = rawSizes,
            previewSizes = previewSizes,
            yuvSizes = yuvSizes,
            manualSensor =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities,
            burstCapture =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities,
            maxResolutionSensor =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in capabilities,
            isLogicalMultiCamera = isLogicalMultiCamera,
            usableForPreview = previewSizes.isNotEmpty(),
            nativeHardwareLevel = native?.hardwareLevel,
            nativeCharacteristicsStatus = native?.characteristicsStatus,
        )
    }

    private fun mergeSizes(vararg groups: List<Size>): List<Size> = groups
        .flatMap { it }
        .distinct()
        .sortedByDescending { it.width.toLong() * it.height.toLong() }

    private fun assignUserFacingNames(items: List<LensCapability>): List<LensCapability> {
        val rear = items.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            .sortedBy { it.focalLengthMm ?: Float.MAX_VALUE }
        val front = items.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
            .sortedBy { it.focalLengthMm ?: Float.MAX_VALUE }
        val other = items - rear.toSet() - front.toSet()

        val rearNamed = rear.mapIndexed { index, lens ->
            val label = when {
                rear.size == 1 -> "1×"
                index == 0 -> "Ultra"
                index == rear.lastIndex -> "Tele"
                else -> "Main"
            }
            lens.copy(displayName = label)
        }

        val frontNamed = front.mapIndexed { index, lens ->
            lens.copy(displayName = if (front.size == 1) "Front" else "Front ${index + 1}")
        }

        return rearNamed + frontNamed + other.mapIndexed { index, lens ->
            lens.copy(displayName = "Lens ${index + 1}")
        }
    }

    private fun safeCharacteristics(cameraId: String): CameraCharacteristics? =
        runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()

    private fun accessPriority(path: CameraAccessPath): Int = when (path) {
        CameraAccessPath.JAVA_DIRECT -> 0
        CameraAccessPath.NDK_DIRECT -> 1
        CameraAccessPath.PHYSICAL_VIA_LOGICAL -> 2
    }

    private data class DiscoveryResult(
        val snapshot: CameraDiscoverySnapshot,
        val candidates: List<LensCapability>,
    )
}
