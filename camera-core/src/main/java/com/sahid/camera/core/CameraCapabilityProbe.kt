package com.sahid.camera.core

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

/**
 * Foundation capability probe.
 *
 * Important: this intentionally does not expose CameraManager IDs directly to the UI.
 * It expands logical cameras into physical members when the framework exposes them and
 * only returns candidates that advertise a preview-compatible stream.
 *
 * A later phase must add explicit session-combination validation and persistent device
 * fingerprint caching before a lens is considered fully qualified for RAW capture.
 */
class CameraCapabilityProbe(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)

    fun probeUsableLenses(): List<LensCapability> {
        val candidates = mutableListOf<LensCapability>()

        for (cameraId in manager.cameraIdList) {
            val logicalChars = safeCharacteristics(cameraId) ?: continue
            val physicalIds = logicalChars.physicalCameraIds
                .toList()
                .sorted()

            if (physicalIds.isNotEmpty()) {
                val physicalCandidates = physicalIds.mapNotNull { physicalId ->
                    val physicalChars = safeCharacteristics(physicalId) ?: return@mapNotNull null
                    buildCapability(
                        logicalCameraId = cameraId,
                        physicalCameraId = physicalId,
                        chars = physicalChars,
                        isLogicalMultiCamera = true,
                    )
                }.filter { it.usableForPreview }

                if (physicalCandidates.isNotEmpty()) {
                    candidates += physicalCandidates
                } else {
                    buildCapability(
                        logicalCameraId = cameraId,
                        physicalCameraId = null,
                        chars = logicalChars,
                        isLogicalMultiCamera = true,
                    )?.let(candidates::add)
                }
            } else {
                buildCapability(
                    logicalCameraId = cameraId,
                    physicalCameraId = null,
                    chars = logicalChars,
                    isLogicalMultiCamera = false,
                )?.let(candidates::add)
            }
        }

        val deduped = candidates
            .filter { it.usableForPreview }
            .distinctBy { candidate ->
                Triple(
                    candidate.facing,
                    candidate.physicalCameraId ?: candidate.logicalCameraId,
                    candidate.focalLengthMm?.let { (it * 100f).toInt() },
                )
            }

        return assignUserFacingNames(deduped)
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
