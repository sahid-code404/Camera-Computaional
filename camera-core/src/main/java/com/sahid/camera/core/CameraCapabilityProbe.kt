package com.sahid.camera.core

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import com.sahid.camera.aurora.NativeCameraEnumerator
import com.sahid.camera.aurora.NativeCameraInfo
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Phase-01 universal camera discovery and runtime qualification.
 *
 * Candidate discovery combines four views:
 *  1. Java CameraManager.cameraIdList
 *  2. NDK ACameraManager_getCameraIdList
 *  3. logical-camera physicalCameraIds topology
 *  4. bounded numeric hidden-ID metadata probing through ACameraManager_getCameraCharacteristics
 *
 * Advertised lists are evidence, not an authority boundary. A metadata-valid hidden ID is
 * allowed to reach the same real frame qualification used by normal direct IDs.
 */
class CameraCapabilityProbe(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val qualificationStore = LensQualificationStore(appContext)

    /** Metadata-only view for diagnostics. It is not allowed to drive final UI exposure. */
    fun probeUsableLenses(): List<LensCapability> {
        val discovery = discoverCandidates()
        val preferred = discovery.candidates
            .groupBy { it.cameraId }
            .values
            .mapNotNull { paths -> paths.minByOrNull { accessPriority(it.accessPath) } }
        return assignUserFacingNames(preferred)
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

        // First collapse multiple access routes to one proven route per actual camera ID.
        // Renderer quality wins before API preference, so a native PRIVATE preview beats a
        // Java YUV-only fallback for the same sensor.
        val bestPerCameraId = qualified
            .filter { it.userVisible }
            .groupBy { it.cameraId }
            .values
            .mapNotNull { paths -> paths.minByOrNull(::qualifiedPathScore) }

        // Then collapse logical/physical/helper identities by optical equivalence. A logical
        // stream is kept when it represents a genuinely different FOV; an equivalent physical
        // member wins when both represent the same lens.
        val identityFiltered = collapseUserLensIdentities(
            bestPerCameraId,
            discovery.snapshot.logicalTopology,
        )

        val namedVisible = assignUserFacingNames(identityFiltered.map { it.copy(displayName = "Lens") })
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

        val ndkListedInfos = runCatching { NativeCameraEnumerator.enumerate() }
            .getOrDefault(emptyList())
        val ndkDirectIds = ndkListedInfos.map { it.id }.distinct().sorted()
        val ndkDirectSet = ndkDirectIds.toSet()

        // Metadata-only bounded scan. Invalid IDs are never opened. Only metadata-valid IDs
        // below proceed into Java/NDK runtime qualification.
        val hiddenProbe = runCatching {
            NativeCameraEnumerator.searchHiddenNumericIds(HIDDEN_SCAN_MAX_ID)
        }.getOrElse {
            com.sahid.camera.aurora.HiddenCameraProbeResult.empty(HIDDEN_SCAN_MAX_ID)
        }
        val hiddenMetadataIds = hiddenProbe.validIds.distinct().sortedWith(::cameraIdComparator)
        val hiddenDiscoveredIds = hiddenMetadataIds
            .filterNot { it in javaDirectSet || it in ndkDirectSet }
            .distinct()
            .sortedWith(::cameraIdComparator)
        val hiddenDiscoveredSet = hiddenDiscoveredIds.toSet()

        // The hidden probe reads the same NDK metadata but additionally extracts logical
        // physical IDs. Prefer that richer record when both sources describe the same ID.
        val ndkById = buildMap<String, NativeCameraInfo> {
            ndkListedInfos.forEach { put(it.id, it) }
            hiddenProbe.validCameras.forEach { put(it.id, it) }
        }

        val javaTopology = javaDirectIds.mapNotNull { logicalId ->
            val children = safeCharacteristics(logicalId)
                ?.physicalCameraIds
                ?.toList()
                ?.distinct()
                ?.sortedWith(::cameraIdComparator)
                .orEmpty()
            if (children.isEmpty()) null else logicalId to children
        }.toMap()
        val hiddenTopology = hiddenProbe.logicalTopology.mapValues { (_, children) ->
            children.distinct().sortedWith(::cameraIdComparator)
        }
        val logicalTopology = mergeTopology(javaTopology, hiddenTopology)

        val candidates = mutableListOf<LensCapability>()

        // Java and NDK listed paths remain independent. Do not collapse a shared ID before
        // runtime qualification: OEMs can expose different behavior through each API route.
        javaDirectIds.forEach { cameraId ->
            val sources = buildSet {
                add(CameraDiscoverySource.JAVA_DIRECT)
                if (cameraId in ndkDirectSet) add(CameraDiscoverySource.NDK_DIRECT)
            }
            buildCapability(
                cameraId = cameraId,
                logicalCameraId = cameraId,
                physicalCameraId = null,
                chars = safeCharacteristics(cameraId),
                native = ndkById[cameraId],
                accessPath = CameraAccessPath.JAVA_DIRECT,
                discoverySources = sources,
                isLogicalMultiCamera = logicalTopology[cameraId].orEmpty().isNotEmpty(),
            )?.let(candidates::add)
        }

        ndkDirectIds.forEach { cameraId ->
            val sources = buildSet {
                add(CameraDiscoverySource.NDK_DIRECT)
                if (cameraId in javaDirectSet) add(CameraDiscoverySource.JAVA_DIRECT)
            }
            buildCapability(
                cameraId = cameraId,
                logicalCameraId = cameraId,
                physicalCameraId = null,
                chars = safeCharacteristics(cameraId),
                native = ndkById[cameraId],
                accessPath = CameraAccessPath.NDK_DIRECT,
                discoverySources = sources,
                isLogicalMultiCamera = logicalTopology[cameraId].orEmpty().isNotEmpty(),
            )?.let(candidates::add)
        }

        // Hidden IDs are not required to appear in either advertised list. The NDK metadata
        // scan itself is enough to create a direct native candidate; actual frames remain the
        // final gate. If Java characteristics are also retrievable, test that route separately.
        hiddenDiscoveredIds.forEach { cameraId ->
            val native = ndkById[cameraId] ?: return@forEach
            val commonSources = buildSet {
                add(CameraDiscoverySource.HIDDEN_ID_PROBE)
                if (cameraId in ndkDirectSet) add(CameraDiscoverySource.NDK_DIRECT)
                if (cameraId in javaDirectSet) add(CameraDiscoverySource.JAVA_DIRECT)
            }
            buildCapability(
                cameraId = cameraId,
                logicalCameraId = cameraId,
                physicalCameraId = null,
                chars = safeCharacteristics(cameraId),
                native = native,
                accessPath = CameraAccessPath.NDK_DIRECT,
                discoverySources = commonSources,
                isLogicalMultiCamera = logicalTopology[cameraId].orEmpty().isNotEmpty(),
            )?.let(candidates::add)

            val hiddenJavaChars = safeCharacteristics(cameraId)
            if (hiddenJavaChars != null) {
                buildCapability(
                    cameraId = cameraId,
                    logicalCameraId = cameraId,
                    physicalCameraId = null,
                    chars = hiddenJavaChars,
                    native = native,
                    accessPath = CameraAccessPath.JAVA_DIRECT,
                    discoverySources = commonSources + CameraDiscoverySource.JAVA_DIRECT,
                    isLogicalMultiCamera = logicalTopology[cameraId].orEmpty().isNotEmpty(),
                )?.let(candidates::add)
            }
        }

        // Keep physical-via-logical candidates where the logical parent is accessible through
        // Java Camera2. Hidden logical topology is still retained even when Java cannot open the
        // parent; that diagnostic tells us whether an NDK physical-output route is needed next.
        logicalTopology.forEach { (logicalId, physicalIds) ->
            val logicalChars = safeCharacteristics(logicalId) ?: return@forEach
            physicalIds.forEach { physicalId ->
                val sources = buildSet {
                    add(CameraDiscoverySource.LOGICAL_PHYSICAL)
                    if (physicalId in javaDirectSet) add(CameraDiscoverySource.JAVA_DIRECT)
                    if (physicalId in ndkDirectSet) add(CameraDiscoverySource.NDK_DIRECT)
                    if (physicalId in hiddenDiscoveredSet || logicalId in hiddenDiscoveredSet) {
                        add(CameraDiscoverySource.HIDDEN_ID_PROBE)
                    }
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
            // Keep the read alive as evidence that this parent is Java-addressable even when
            // no child candidate can be constructed from independently readable metadata.
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
            displayName = "Lens $cameraId",
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

        // Remove a logical stream only when a qualified physical member is optically
        // equivalent. This avoids losing a genuine main lens when only aux children expose IDs.
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
        return rendererScore + accessPriority(lens.accessPath)
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
                .map { (lens, eq) ->
                    lens.copy(displayName = zoomLabel(eq / mainEq))
                }
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
    }
}
