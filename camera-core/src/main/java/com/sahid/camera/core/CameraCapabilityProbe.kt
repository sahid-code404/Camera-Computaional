package com.sahid.camera.core

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import com.sahid.camera.aurora.HiddenCameraProbeResult
import com.sahid.camera.aurora.NativeCameraEnumerator
import com.sahid.camera.aurora.NativeCameraInfo
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Universal Camera/Aurora discovery with a MotionCam-style learned fast path.
 *
 * Normal launch is intentionally cheap:
 *  1. reuse routes that already produced a usable preview on this Build.FINGERPRINT;
 *  2. merge normal Java/NDK advertised cameras and Java logical/physical topology;
 *  3. never scan 0..255 during normal startup;
 *  4. never run RAW session probing as part of Phase-01 discovery.
 *
 * Deep hidden-lens discovery is explicit and optimized around one batched native scan. Metadata
 * rejected IDs are direct-open probed with one ACameraManager instance, avoiding hundreds of
 * serial Java callback waits. No phone-specific camera IDs are compiled into this class.
 */
class CameraCapabilityProbe(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val qualificationStore = LensQualificationStore(appContext)
    private val learnedStore = LearnedLensStore(appContext)

    fun hasCompletedDeepScanForCurrentBuild(): Boolean = learnedStore.load().deepScanCompleted

    fun clearLearnedRoutesForCurrentBuild() = learnedStore.clearCurrentBuild()

    /** Fast metadata/cache view. No bounded hidden-ID scan is performed here. */
    fun probeUsableLenses(): List<LensCapability> {
        val discovery = discoverFastCandidates()
        val preferred = discovery.candidates
            .groupBy { it.cameraId }
            .values
            .mapNotNull { paths -> paths.minByOrNull { accessPriority(it.accessPath) } }
            .sortedWith(compareBy(::cameraIdSortKey))
        return if (CAMERA_LAB_MODE) {
            preferred.map { it.copy(displayName = "ID ${it.cameraId}") }
        } else {
            assignUserFacingNames(preferred)
        }
    }

    fun probeQualifiedLenses(
        onProgress: ((completed: Int, total: Int, lens: LensCapability) -> Unit)? = null,
    ): List<LensCapability> = probeFastQualificationReport(onProgress).visibleLenses

    fun probeQualificationReport(
        onProgress: ((completed: Int, total: Int, lens: LensCapability) -> Unit)? = null,
    ): CameraQualificationReport = probeFastQualificationReport(onProgress)

    /**
     * Startup path: cached proven routes are reused immediately and only previously unseen public
     * routes get a quick preview/YUV qualification. RAW validation is deferred to Phase 02.
     */
    fun probeFastQualificationReport(
        onProgress: ((completed: Int, total: Int, lens: LensCapability) -> Unit)? = null,
    ): CameraQualificationReport {
        val discovery = discoverFastCandidates()
        if (discovery.candidates.isEmpty()) {
            return CameraQualificationReport(
                discovery = discovery.snapshot,
                candidates = emptyList(),
                visibleLenses = emptyList(),
            ).also(qualificationStore::save)
        }

        val toQualify = discovery.candidates.count { !it.learnedFromCache }
        var completed = 0
        val qualified = CameraSessionQualifier(appContext).use { qualifier ->
            discovery.candidates.map { lens ->
                if (lens.learnedFromCache && lens.userVisible) {
                    lens
                } else {
                    qualifier.qualifyPreviewOnly(lens).also {
                        completed += 1
                        onProgress?.invoke(completed, toQualify, it)
                    }
                }
            }
        }
        return finalizeReport(discovery, qualified, deepScanCompleted = false)
    }

    /**
     * Generic hidden-lens compatibility pass. Existing learned cameras are never re-qualified;
     * only newly discovered camera IDs pay the quick preview/YUV validation cost.
     */
    fun probeDeepQualificationReport(
        onProgress: ((completed: Int, total: Int, lens: LensCapability) -> Unit)? = null,
    ): CameraQualificationReport {
        val learnedSnapshot = learnedStore.load()
        val learnedByCameraId = learnedSnapshot.routes
            .filter { it.userVisible }
            .associateBy { it.cameraId }
        val discovery = discoverDeepCandidates()

        if (discovery.candidates.isEmpty() && learnedByCameraId.isEmpty()) {
            val empty = CameraQualificationReport(
                discovery = discovery.snapshot,
                candidates = emptyList(),
                visibleLenses = emptyList(),
            )
            qualificationStore.save(empty)
            learnedStore.saveDeepScan(empty)
            return empty
        }

        val newCandidates = discovery.candidates.filter { it.cameraId !in learnedByCameraId }
        val qualifiedNew = CameraSessionQualifier(appContext).use { qualifier ->
            newCandidates.mapIndexed { index, lens ->
                qualifier.qualify(lens).also {
                    onProgress?.invoke(index + 1, newCandidates.size, it)
                }
            }
        }
        val qualified = learnedByCameraId.values.toList() + qualifiedNew
        return finalizeReport(discovery, qualified, deepScanCompleted = true)
    }

    private fun finalizeReport(
        discovery: DiscoveryResult,
        qualified: List<LensCapability>,
        deepScanCompleted: Boolean,
    ): CameraQualificationReport {
        val bestPerCameraId = qualified
            .filter { it.userVisible }
            .groupBy { it.cameraId }
            .values
            .mapNotNull { paths -> paths.minByOrNull(::qualifiedPathScore) }

        val identityFiltered = if (CAMERA_LAB_MODE) {
            bestPerCameraId.sortedWith(compareBy(::cameraIdSortKey))
        } else {
            collapseUserLensIdentities(bestPerCameraId, discovery.snapshot.logicalTopology)
        }

        val namedVisible = if (CAMERA_LAB_MODE) {
            identityFiltered.map { it.copy(displayName = "ID ${it.cameraId}") }
        } else {
            assignUserFacingNames(identityFiltered.map { it.copy(displayName = "Lens") })
        }
        val visibleNamesByCameraId = namedVisible.associate { it.cameraId to it.displayName }
        val namedCandidates = qualified.map { lens ->
            lens.copy(displayName = visibleNamesByCameraId[lens.cameraId] ?: "ID ${lens.cameraId}")
        }
        val report = CameraQualificationReport(
            discovery = discovery.snapshot,
            candidates = namedCandidates,
            visibleLenses = namedVisible,
        )
        qualificationStore.save(report)
        if (deepScanCompleted) {
            learnedStore.saveDeepScan(report)
        } else {
            learnedStore.mergeProvenRoutes(report.visibleLenses)
        }
        return report
    }

    /** Cheap discovery: advertised APIs + physical topology + learned preview routes. */
    private fun discoverFastCandidates(): DiscoveryResult {
        val learned = learnedStore.load()
        val learnedRoutes = learned.routes
        val learnedByCameraId = learnedRoutes.associateBy { it.cameraId }

        val javaDirectIds = runCatching { manager.cameraIdList.toList() }
            .getOrDefault(emptyList())
            .distinct()
            .sortedWith(::cameraIdComparator)
        val javaDirectSet = javaDirectIds.toSet()

        val ndkListedInfos = runCatching { NativeCameraEnumerator.enumerate() }
            .getOrDefault(emptyList())
        val ndkDirectIds = ndkListedInfos.map { it.id }
            .distinct()
            .sortedWith(::cameraIdComparator)
        val ndkDirectSet = ndkDirectIds.toSet()
        val ndkById = ndkListedInfos.associateBy { it.id }

        val javaMetadataById = javaDirectIds.mapNotNull { cameraId ->
            safeCharacteristics(cameraId)?.let { cameraId to it }
        }.toMap()
        val javaTopology = javaMetadataById.mapNotNull { (logicalId, chars) ->
            val children = chars.physicalCameraIds
                .toList()
                .distinct()
                .sortedWith(::cameraIdComparator)
            if (children.isEmpty()) null else logicalId to children
        }.toMap()
        val learnedTopology = learnedRoutes
            .filter { it.physicalCameraId != null }
            .groupBy { it.logicalCameraId }
            .mapValues { (_, routes) -> routes.map { it.cameraId }.distinct() }
        val logicalTopology = mergeTopology(javaTopology, learnedTopology)

        val candidates = linkedMapOf<String, LensCapability>()
        learnedRoutes.forEach { candidates[it.stableId] = it }

        javaDirectIds.forEach { cameraId ->
            if (cameraId in learnedByCameraId) return@forEach
            buildCapability(
                cameraId = cameraId,
                logicalCameraId = cameraId,
                physicalCameraId = null,
                chars = javaMetadataById[cameraId],
                native = ndkById[cameraId],
                accessPath = CameraAccessPath.JAVA_DIRECT,
                discoverySources = buildSet {
                    add(CameraDiscoverySource.JAVA_DIRECT)
                    if (cameraId in ndkDirectSet) add(CameraDiscoverySource.NDK_DIRECT)
                },
                isLogicalMultiCamera = logicalTopology[cameraId].orEmpty().isNotEmpty(),
            )?.let { candidates[it.stableId] = it }
        }

        ndkDirectIds.forEach { cameraId ->
            if (cameraId in learnedByCameraId || cameraId in javaDirectSet) return@forEach
            buildCapability(
                cameraId = cameraId,
                logicalCameraId = cameraId,
                physicalCameraId = null,
                chars = null,
                native = ndkById[cameraId],
                accessPath = CameraAccessPath.NDK_DIRECT,
                discoverySources = setOf(CameraDiscoverySource.NDK_DIRECT),
                isLogicalMultiCamera = false,
            )?.let { candidates[it.stableId] = it }
        }

        javaTopology.forEach { (logicalId, physicalIds) ->
            physicalIds.forEach { physicalId ->
                if (physicalId in learnedByCameraId) return@forEach
                buildCapability(
                    cameraId = physicalId,
                    logicalCameraId = logicalId,
                    physicalCameraId = physicalId,
                    chars = safeCharacteristics(physicalId),
                    native = ndkById[physicalId],
                    accessPath = CameraAccessPath.PHYSICAL_VIA_LOGICAL,
                    discoverySources = buildSet {
                        add(CameraDiscoverySource.LOGICAL_PHYSICAL)
                        if (physicalId in javaDirectSet) add(CameraDiscoverySource.JAVA_DIRECT)
                        if (physicalId in ndkDirectSet) add(CameraDiscoverySource.NDK_DIRECT)
                    },
                    isLogicalMultiCamera = true,
                )?.let { candidates[it.stableId] = it }
            }
        }

        val cachedHiddenIds = learnedRoutes.map { it.cameraId }
            .filterNot { it in javaDirectSet || it in ndkDirectSet }
            .distinct()
            .sortedWith(::cameraIdComparator)
        return DiscoveryResult(
            snapshot = CameraDiscoverySnapshot(
                javaDirectIds = javaDirectIds,
                ndkDirectIds = ndkDirectIds,
                logicalTopology = logicalTopology,
                hiddenDiscoveredIds = cachedHiddenIds,
                deepOpenDiscoveredIds = learnedRoutes
                    .filter { it.deepOpenDiscovered }
                    .map { it.cameraId }
                    .distinct()
                    .sortedWith(::cameraIdComparator),
                cameraLabMode = CAMERA_LAB_MODE,
            ),
            candidates = candidates.values.toList(),
        )
    }

    /** Full generic compatibility scan. No device-specific IDs and no serial Java open sweep. */
    private fun discoverDeepCandidates(): DiscoveryResult {
        val learnedIds = learnedStore.load().routes.map { it.cameraId }.distinct()
        val javaDirectIds = runCatching { manager.cameraIdList.toList() }
            .getOrDefault(emptyList())
            .distinct()
            .sortedWith(::cameraIdComparator)
        val javaDirectSet = javaDirectIds.toSet()

        val ndkListedInfos = runCatching { NativeCameraEnumerator.enumerate() }
            .getOrDefault(emptyList())
        val ndkDirectIds = ndkListedInfos.map { it.id }
            .distinct()
            .sortedWith(::cameraIdComparator)
        val ndkDirectSet = ndkDirectIds.toSet()

        val deepScanOrder = deepNumericScanOrder(HIDDEN_SCAN_MAX_ID, learnedIds)

        // Java characteristics remain independent evidence, but this is metadata-only and has no
        // camera-open callback timeout for missing IDs.
        val javaMetadataById = linkedMapOf<String, CameraCharacteristics>()
        val javaMetadataFailures = linkedMapOf<String, String>()
        deepScanOrder.forEach { cameraId ->
            try {
                javaMetadataById[cameraId] = manager.getCameraCharacteristics(cameraId)
            } catch (t: Throwable) {
                javaMetadataFailures[cameraId] =
                    "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            }
        }
        val deepJavaMetadataIds = javaMetadataById.keys.toList()
            .sortedWith(::cameraIdComparator)

        // One native call performs both the NDK metadata scan and direct-open fallback using a
        // single ACameraManager. This replaces the old per-ID manager allocation + Java wait loop.
        val hiddenProbe = runCatching {
            NativeCameraEnumerator.searchHiddenNumericIds(HIDDEN_SCAN_MAX_ID)
        }.getOrElse {
            HiddenCameraProbeResult.empty(HIDDEN_SCAN_MAX_ID)
        }
        val hiddenMetadataIds = hiddenProbe.validIds
            .distinct()
            .sortedWith(::cameraIdComparator)
        val hiddenDiscoveredIds = hiddenMetadataIds
            .filterNot { it in javaDirectSet || it in ndkDirectSet }
            .distinct()
            .sortedWith(::cameraIdComparator)

        val ndkById = buildMap<String, NativeCameraInfo> {
            ndkListedInfos.forEach { put(it.id, it) }
            hiddenProbe.validCameras.forEach { put(it.id, it) }
        }

        val ndkOpenStatuses = hiddenProbe.directOpenStatuses
        val ndkOpenSucceeded = hiddenProbe.directOpenSucceededIds.toSet()
        val javaOpenResults = emptyMap<String, String>()
        val javaOpenSucceeded = emptySet<String>()
        val deepOpenDiscoveredIds = ndkOpenSucceeded
            .filterNot { it in javaDirectSet || it in ndkDirectSet }
            .distinct()
            .sortedWith(::cameraIdComparator)
        val deepOpenDiscoveredSet = deepOpenDiscoveredIds.toSet()

        val javaTopology = javaMetadataById.mapNotNull { (logicalId, chars) ->
            val children = chars.physicalCameraIds
                .toList()
                .distinct()
                .sortedWith(::cameraIdComparator)
            if (children.isEmpty()) null else logicalId to children
        }.toMap()
        val hiddenTopology = hiddenProbe.logicalTopology.mapValues { (_, children) ->
            children.distinct().sortedWith(::cameraIdComparator)
        }
        val logicalTopology = mergeTopology(javaTopology, hiddenTopology)

        val candidates = mutableListOf<LensCapability>()

        javaDirectIds.forEach { cameraId ->
            buildCapability(
                cameraId = cameraId,
                logicalCameraId = cameraId,
                physicalCameraId = null,
                chars = javaMetadataById[cameraId] ?: safeCharacteristics(cameraId),
                native = ndkById[cameraId],
                accessPath = CameraAccessPath.JAVA_DIRECT,
                discoverySources = buildSet {
                    add(CameraDiscoverySource.JAVA_DIRECT)
                    if (cameraId in ndkDirectSet) add(CameraDiscoverySource.NDK_DIRECT)
                },
                isLogicalMultiCamera = logicalTopology[cameraId].orEmpty().isNotEmpty(),
            )?.let(candidates::add)
        }
        ndkDirectIds.forEach { cameraId ->
            buildCapability(
                cameraId = cameraId,
                logicalCameraId = cameraId,
                physicalCameraId = null,
                chars = javaMetadataById[cameraId],
                native = ndkById[cameraId],
                accessPath = CameraAccessPath.NDK_DIRECT,
                discoverySources = buildSet {
                    add(CameraDiscoverySource.NDK_DIRECT)
                    if (cameraId in javaDirectSet) add(CameraDiscoverySource.JAVA_DIRECT)
                },
                isLogicalMultiCamera = logicalTopology[cameraId].orEmpty().isNotEmpty(),
            )?.let(candidates::add)
        }

        val deepMetadataIds = (deepJavaMetadataIds + hiddenMetadataIds)
            .filterNot { it in javaDirectSet || it in ndkDirectSet }
            .distinct()
            .sortedWith(::cameraIdComparator)
        deepMetadataIds.forEach { cameraId ->
            val javaChars = javaMetadataById[cameraId]
            val native = ndkById[cameraId]
            val commonSources = setOf(CameraDiscoverySource.HIDDEN_ID_PROBE)
            if (javaChars != null) {
                buildCapability(
                    cameraId = cameraId,
                    logicalCameraId = cameraId,
                    physicalCameraId = null,
                    chars = javaChars,
                    native = native,
                    accessPath = CameraAccessPath.JAVA_DIRECT,
                    discoverySources = commonSources + CameraDiscoverySource.JAVA_DIRECT,
                    isLogicalMultiCamera = logicalTopology[cameraId].orEmpty().isNotEmpty(),
                )?.let(candidates::add)
            }
            if (native != null) {
                buildCapability(
                    cameraId = cameraId,
                    logicalCameraId = cameraId,
                    physicalCameraId = null,
                    chars = javaChars,
                    native = native,
                    accessPath = CameraAccessPath.NDK_DIRECT,
                    discoverySources = commonSources + CameraDiscoverySource.NDK_DIRECT,
                    isLogicalMultiCamera = logicalTopology[cameraId].orEmpty().isNotEmpty(),
                )?.let(candidates::add)
            }
        }

        // Metadata-hidden IDs that native-open successfully get a conservative preview/YUV
        // candidate. Real renderer qualification is still required before they reach the UI.
        deepOpenDiscoveredIds.forEach { cameraId ->
            val sources = setOf(
                CameraDiscoverySource.HIDDEN_ID_PROBE,
                CameraDiscoverySource.DEEP_OPEN_PROBE,
                CameraDiscoverySource.NDK_DIRECT,
            )
            buildCapability(
                cameraId = cameraId,
                logicalCameraId = cameraId,
                physicalCameraId = null,
                chars = null,
                native = null,
                accessPath = CameraAccessPath.NDK_DIRECT,
                discoverySources = sources,
                isLogicalMultiCamera = false,
                allowMetadataLess = true,
            )?.let(candidates::add)
        }

        logicalTopology.forEach { (logicalId, physicalIds) ->
            val logicalChars = javaMetadataById[logicalId] ?: safeCharacteristics(logicalId)
                ?: return@forEach
            physicalIds.forEach { physicalId ->
                val sources = buildSet {
                    add(CameraDiscoverySource.LOGICAL_PHYSICAL)
                    if (physicalId in javaDirectSet) add(CameraDiscoverySource.JAVA_DIRECT)
                    if (physicalId in ndkDirectSet) add(CameraDiscoverySource.NDK_DIRECT)
                    if (
                        physicalId in hiddenDiscoveredIds ||
                        logicalId in hiddenDiscoveredIds ||
                        physicalId in deepOpenDiscoveredSet ||
                        logicalId in deepOpenDiscoveredSet
                    ) {
                        add(CameraDiscoverySource.HIDDEN_ID_PROBE)
                    }
                }
                buildCapability(
                    cameraId = physicalId,
                    logicalCameraId = logicalId,
                    physicalCameraId = physicalId,
                    chars = javaMetadataById[physicalId] ?: safeCharacteristics(physicalId),
                    native = ndkById[physicalId],
                    accessPath = CameraAccessPath.PHYSICAL_VIA_LOGICAL,
                    discoverySources = sources,
                    isLogicalMultiCamera = true,
                )?.let(candidates::add)
            }
            @Suppress("UNUSED_VARIABLE")
            val javaAddressableLogical = logicalChars
        }

        return DiscoveryResult(
            snapshot = CameraDiscoverySnapshot(
                javaDirectIds = javaDirectIds,
                ndkDirectIds = ndkDirectIds,
                logicalTopology = logicalTopology,
                hiddenProbeMaxNumericId = hiddenProbe.maxNumericId,
                hiddenProbeAttemptedCount = hiddenProbe.attemptedCount,
                hiddenMetadataIds = hiddenMetadataIds,
                hiddenDiscoveredIds = hiddenDiscoveredIds,
                hiddenLogicalTopology = hiddenTopology,
                hiddenRejectedStatuses = hiddenProbe.rejectedStatuses,
                deepJavaMetadataIds = deepJavaMetadataIds,
                deepJavaMetadataFailures = javaMetadataFailures,
                deepJavaOpenResults = javaOpenResults,
                deepJavaOpenSucceededIds = javaOpenSucceeded.toList(),
                deepNdkOpenStatuses = ndkOpenStatuses,
                deepNdkOpenSucceededIds = ndkOpenSucceeded.toList()
                    .sortedWith(::cameraIdComparator),
                deepOpenDiscoveredIds = deepOpenDiscoveredIds,
                cameraLabMode = CAMERA_LAB_MODE,
            ),
            candidates = candidates.distinctBy { it.stableId },
        )
    }

    private fun buildCapability(
        cameraId: String,
        logicalCameraId: String,
        physicalCameraId: String?,
        chars: CameraCharacteristics?,
        native: NativeCameraInfo?,
        accessPath: CameraAccessPath,
        discoverySources: Set<CameraDiscoverySource>,
        isLogicalMultiCamera: Boolean,
        allowMetadataLess: Boolean = false,
    ): LensCapability? {
        if (chars == null && native == null && !allowMetadataLess) return null

        val streamMap = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val javaPreviewSizes = streamMap
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.toList()
            .orEmpty()
        val javaYuvSizes = streamMap
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.toList()
            .orEmpty()
        val javaRawSizes = mergeSizes(
            streamMap?.getOutputSizes(ImageFormat.RAW10)?.toList().orEmpty(),
            streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty(),
            streamMap?.getOutputSizes(ImageFormat.RAW12)?.toList().orEmpty(),
        )

        val fallbackPreviewSizes = if (allowMetadataLess) DEEP_FALLBACK_PREVIEW_SIZES else emptyList()
        val fallbackYuvSizes = if (allowMetadataLess) DEEP_FALLBACK_YUV_SIZES else emptyList()
        val previewSizes = mergeSizes(
            javaPreviewSizes,
            native?.privateOutputSizes.orEmpty().map { Size(it.width, it.height) },
            fallbackPreviewSizes,
        )
        val yuvSizes = mergeSizes(
            javaYuvSizes,
            native?.yuvOutputSizes.orEmpty().map { Size(it.width, it.height) },
            fallbackYuvSizes,
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
        val rawSupported = rawAdvertisedByJava || native?.rawCapability == true || rawSizes.isNotEmpty()

        val physicalSize = chars?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorWidthMm = physicalSize?.width ?: native?.sensorWidthMm
        val sensorHeightMm = physicalSize?.height ?: native?.sensorHeightMm
        val focalLengthMm = chars
            ?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull()
            ?: native?.focalLengthMm
        val horizontalFov = calculateHorizontalFov(sensorWidthMm, focalLengthMm)

        return LensCapability(
            cameraId = cameraId,
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            accessPath = accessPath,
            discoverySources = discoverySources,
            facing = chars?.get(CameraCharacteristics.LENS_FACING) ?: native?.facing,
            displayName = "ID $cameraId",
            focalLengthMm = focalLengthMm,
            sensorWidthMm = sensorWidthMm,
            sensorHeightMm = sensorHeightMm,
            horizontalFovDegrees = horizontalFov,
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
            isLogicalMultiCamera = isLogicalMultiCamera || native?.logicalMultiCamera == true,
            usableForPreview = previewSizes.isNotEmpty() || yuvSizes.isNotEmpty(),
            nativeHardwareLevel = native?.hardwareLevel,
            nativeCharacteristicsStatus = native?.characteristicsStatus,
        )
    }

    private fun mergeTopology(
        first: Map<String, List<String>>,
        second: Map<String, List<String>>,
    ): Map<String, List<String>> = buildMap {
        (first.keys + second.keys).forEach { logicalId ->
            val children = (first[logicalId].orEmpty() + second[logicalId].orEmpty())
                .distinct()
                .sortedWith(::cameraIdComparator)
            if (children.isNotEmpty()) put(logicalId, children)
        }
    }

    private fun collapseUserLensIdentities(
        items: List<LensCapability>,
        logicalTopology: Map<String, List<String>>,
    ): List<LensCapability> {
        if (items.size <= 1) return items
        val physicalIds = logicalTopology.values.flatten().toSet()
        val withoutEquivalentLogical = items.filterNot { lens ->
            val children = logicalTopology[lens.cameraId].orEmpty()
            children.isNotEmpty() && items.any { child ->
                child.cameraId in children && equivalentOptics(lens, child)
            }
        }
        val clusters = mutableListOf<MutableList<LensCapability>>()
        withoutEquivalentLogical
            .sortedWith(compareBy<LensCapability> { it.facing ?: Int.MAX_VALUE }
                .thenByDescending { it.horizontalFovDegrees ?: Float.NEGATIVE_INFINITY })
            .forEach { lens ->
                val cluster = clusters.firstOrNull { existing ->
                    existing.firstOrNull()?.let { representative ->
                        representative.facing == lens.facing && equivalentOptics(representative, lens)
                    } == true
                }
                if (cluster != null) cluster += lens else clusters += mutableListOf(lens)
            }
        return clusters.mapNotNull { cluster ->
            cluster.minByOrNull { lens ->
                val physicalIdentityPenalty = if (lens.cameraId in physicalIds) 0 else 100
                physicalIdentityPenalty + qualifiedPathScore(lens)
            }
        }
    }

    private fun equivalentOptics(left: LensCapability, right: LensCapability): Boolean {
        if (left.cameraId == right.cameraId) return true
        val leftFov = left.horizontalFovDegrees
        val rightFov = right.horizontalFovDegrees
        if (leftFov != null && rightFov != null && leftFov > 0f && rightFov > 0f) {
            val delta = abs(leftFov - rightFov)
            val relative = delta / maxOf(leftFov, rightFov)
            return delta <= 3.5f || relative <= 0.055f
        }
        val leftFocal = left.focalLengthMm
        val rightFocal = right.focalLengthMm
        if (leftFocal != null && rightFocal != null && leftFocal > 0f && rightFocal > 0f) {
            return abs(leftFocal - rightFocal) / maxOf(leftFocal, rightFocal) <= 0.06f
        }
        return false
    }

    private fun qualifiedPathScore(lens: LensCapability): Int {
        val rendererScore = when {
            lens.qualification.previewSessionQualified -> 0
            lens.qualification.yuvSessionQualified -> 20
            lens.qualification.rawSessionQualified -> 40
            else -> 1000
        }
        val learnedBonus = if (lens.learnedFromCache) -2 else 0
        return rendererScore + accessPriority(lens.accessPath) + learnedBonus
    }

    private fun accessPriority(path: CameraAccessPath): Int = when (path) {
        CameraAccessPath.JAVA_DIRECT -> 0
        CameraAccessPath.NDK_DIRECT -> 1
        CameraAccessPath.PHYSICAL_VIA_LOGICAL -> 2
    }

    private fun mergeSizes(vararg groups: List<Size>): List<Size> = groups
        .flatMap { it }
        .distinct()
        .sortedByDescending { it.width.toLong() * it.height.toLong() }

    private fun calculateHorizontalFov(sensorWidthMm: Float?, focalLengthMm: Float?): Float? {
        if (sensorWidthMm == null || focalLengthMm == null || sensorWidthMm <= 0f || focalLengthMm <= 0f) {
            return null
        }
        return (2.0 * atan(sensorWidthMm / (2.0 * focalLengthMm)) * 180.0 / PI).toFloat()
    }

    private fun fullFrameEquivalentFocal(lens: LensCapability): Double? {
        val fov = lens.horizontalFovDegrees?.toDouble() ?: return null
        if (fov <= 0.0 || fov >= 179.0) return null
        return 18.0 / tan(Math.toRadians(fov / 2.0))
    }

    private fun assignUserFacingNames(items: List<LensCapability>): List<LensCapability> {
        val rear = items.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val front = items.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
        val other = items - rear.toSet() - front.toSet()
        val rearWithEq = rear.mapNotNull { lens ->
            fullFrameEquivalentFocal(lens)?.let { lens to it }
        }
        val rearNamed = if (rear.isNotEmpty() && rearWithEq.size == rear.size) {
            val mainEq = rearWithEq.minByOrNull { (_, eq) -> abs(eq - 26.0) }?.second ?: 26.0
            rearWithEq
                .sortedBy { (_, eq) -> eq }
                .map { (lens, eq) -> lens.copy(displayName = zoomLabel(eq / mainEq)) }
        } else {
            val sorted = rear.sortedBy { it.focalLengthMm ?: Float.MAX_VALUE }
            sorted.mapIndexed { index, lens ->
                val label = when {
                    sorted.size == 1 -> "1×"
                    index == 0 -> "Ultra"
                    index == sorted.lastIndex -> "Tele"
                    else -> "Main"
                }
                lens.copy(displayName = label)
            }
        }
        val frontNamed = front
            .sortedByDescending { it.horizontalFovDegrees ?: Float.NEGATIVE_INFINITY }
            .mapIndexed { index, lens ->
                lens.copy(displayName = if (front.size == 1) "Front" else "Front ${index + 1}")
            }
        return rearNamed + frontNamed + other.mapIndexed { index, lens ->
            lens.copy(displayName = "Lens ${index + 1}")
        }
    }

    private fun zoomLabel(zoom: Double): String {
        val rounded = (zoom * 10.0).roundToInt() / 10.0
        return when {
            abs(rounded - 1.0) < 0.05 -> "1×"
            abs(rounded - rounded.roundToInt()) < 0.05 -> "${rounded.roundToInt()}×"
            else -> String.format(Locale.US, "%.1f×", rounded)
        }
    }

    private fun safeCharacteristics(cameraId: String): CameraCharacteristics? =
        runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()

    /** Learned IDs are dynamically tried first on a deliberate rescan; no IDs are hardcoded. */
    private fun deepNumericScanOrder(maxId: Int, learnedIds: List<String>): List<String> {
        val priority = learnedIds
            .mapNotNull { it.toIntOrNull() }
            .filter { it in 0..maxId }
            .distinct()
        val rest = (0..maxId).filterNot(priority::contains)
        return (priority + rest).map(Int::toString)
    }

    private fun cameraIdSortKey(lens: LensCapability): Int =
        lens.cameraId.toIntOrNull() ?: Int.MAX_VALUE

    private fun cameraIdComparator(left: String, right: String): Int {
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }

    private data class DiscoveryResult(
        val snapshot: CameraDiscoverySnapshot,
        val candidates: List<LensCapability>,
    )

    private companion object {
        const val HIDDEN_SCAN_MAX_ID = NativeCameraEnumerator.DEFAULT_HIDDEN_SCAN_MAX_ID
        const val CAMERA_LAB_MODE = true

        val DEEP_FALLBACK_PREVIEW_SIZES = listOf(Size(640, 480))
        val DEEP_FALLBACK_YUV_SIZES = listOf(Size(640, 480))
    }
}
