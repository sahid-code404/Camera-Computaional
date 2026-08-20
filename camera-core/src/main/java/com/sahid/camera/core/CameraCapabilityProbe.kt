package com.sahid.camera.core

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Camera2 capability discovery plus Phase-01 runtime session qualification.
 *
 * Static metadata is used to discover candidates. User-visible results must come from
 * [probeQualifiedLenses], which verifies real preview sessions and preview+RAW session
 * combinations before exposing a lens or RAW badge.
 */
class CameraCapabilityProbe(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)

    /** Metadata-only discovery. Useful for diagnostics; not sufficient for final UI filtering. */
    fun probeUsableLenses(): List<LensCapability> =
        assignUserFacingNames(selectMetadataPreferredCandidates(probeMetadataCandidates()))

    /**
     * Runtime-qualify all candidates on a worker thread.
     *
     * Physical members are preferred when at least one of them configures successfully.
     * If every physical member of a logical camera is rejected, the logical stream is
     * retained as an honest fallback when it can create a real preview session.
     */
    fun probeQualifiedLenses(
        onProgress: ((completed: Int, total: Int, lens: LensCapability) -> Unit)? = null,
    ): List<LensCapability> {
        val candidates = probeMetadataCandidates()
        if (candidates.isEmpty()) return emptyList()

        val qualified = CameraSessionQualifier(appContext).use { qualifier ->
            candidates.mapIndexed { index, lens ->
                qualifier.qualify(lens).also {
                    onProgress?.invoke(index + 1, candidates.size, it)
                }
            }
        }

        val preferred = qualified
            .groupBy { it.logicalCameraId }
            .values
            .flatMap { group ->
                val qualifiedPhysical = group.filter {
                    it.physicalCameraId != null && it.userVisible
                }
                if (qualifiedPhysical.isNotEmpty()) {
                    qualifiedPhysical
                } else {
                    group.filter { it.physicalCameraId == null && it.userVisible }
                }
            }
            .distinctBy { it.stableId }

        return assignUserFacingNames(preferred.map { it.copy(displayName = "Lens") })
    }

    private fun probeMetadataCandidates(): List<LensCapability> {
        val candidates = mutableListOf<LensCapability>()

        for (cameraId in manager.cameraIdList) {
            val logicalChars = safeCharacteristics(cameraId) ?: continue
            val physicalIds = logicalChars.physicalCameraIds
                .toList()
                .sorted()

            if (physicalIds.isNotEmpty()) {
                physicalIds.mapNotNullTo(candidates) { physicalId ->
                    val physicalChars = safeCharacteristics(physicalId) ?: return@mapNotNullTo null
                    buildCapability(
                        logicalCameraId = cameraId,
                        physicalCameraId = physicalId,
                        chars = physicalChars,
                        isLogicalMultiCamera = true,
                    )
                }

                // Always keep a logical fallback candidate for runtime qualification.
                // It is hidden whenever one or more physical members qualify successfully.
                buildCapability(
                    logicalCameraId = cameraId,
                    physicalCameraId = null,
                    chars = logicalChars,
                    isLogicalMultiCamera = true,
                )?.let(candidates::add)
            } else {
                buildCapability(
                    logicalCameraId = cameraId,
                    physicalCameraId = null,
                    chars = logicalChars,
                    isLogicalMultiCamera = false,
                )?.let(candidates::add)
            }
        }

        return candidates
            .filter { it.usableForPreview }
            .distinctBy { it.stableId }
    }

    private fun selectMetadataPreferredCandidates(items: List<LensCapability>): List<LensCapability> =
        items.groupBy { it.logicalCameraId }
            .values
            .flatMap { group ->
                val physical = group.filter { it.physicalCameraId != null && it.usableForPreview }
                if (physical.isNotEmpty()) physical else group.filter { it.physicalCameraId == null }
            }

    private fun buildCapability(
        logicalCameraId: String,
        physicalCameraId: String?,
        chars: CameraCharacteristics,
        isLogicalMultiCamera: Boolean,
    ): LensCapability? {
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val previewSizes = streamMap.getOutputSizes(SurfaceTexture::class.java)
            ?.toList()
            .orEmpty()
            .sortedByDescending { it.width.toLong() * it.height.toLong() }

        if (previewSizes.isEmpty()) return null

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            .orEmpty()

        val rawSupported = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
        val rawSizes = if (rawSupported) {
            streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.toList()
                .orEmpty()
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
        } else {
            emptyList()
        }

        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val focalLength = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull()

        return LensCapability(
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            facing = facing,
            displayName = "Lens",
            focalLengthMm = focalLength,
            rawSupported = rawSupported,
            rawSizes = rawSizes,
            previewSizes = previewSizes,
            manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities,
            burstCapture = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities,
            maxResolutionSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in capabilities,
            isLogicalMultiCamera = isLogicalMultiCamera,
            usableForPreview = previewSizes.isNotEmpty(),
        )
    }

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
}
