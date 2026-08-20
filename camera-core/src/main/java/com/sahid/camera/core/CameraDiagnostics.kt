package com.sahid.camera.core

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Produces a portable, privacy-light Phase-01 capability/session report for device testing. */
object CameraDiagnostics {
    fun toJson(report: CameraQualificationReport): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("generatedAtUnixMs", System.currentTimeMillis())
        put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("release", Build.VERSION.RELEASE)
            put("buildFingerprint", Build.FINGERPRINT)
        })
        put("summary", JSONObject().apply {
            put("candidateCount", report.candidates.size)
            put("visibleLensCount", report.visibleLenses.size)
            put("verifiedRawLensCount", report.visibleLenses.count { it.rawUsable })
        })
        put("visibleLensIds", JSONArray().apply {
            report.visibleLenses.forEach { put(it.stableId) }
        })
        put("candidates", JSONArray().apply {
            report.candidates.forEach { lens -> put(lensToJson(lens)) }
        })
    }.toString(2)

    private fun lensToJson(lens: LensCapability): JSONObject = JSONObject().apply {
        put("stableId", lens.stableId)
        put("logicalCameraId", lens.logicalCameraId)
        put("physicalCameraId", lens.physicalCameraId ?: JSONObject.NULL)
        put("displayName", lens.displayName)
        put("facing", facingLabel(lens.facing))
        put("focalLengthMm", lens.focalLengthMm ?: JSONObject.NULL)
        put("logicalMultiCamera", lens.isLogicalMultiCamera)
        put("metadataPreviewUsable", lens.usableForPreview)
        put("previewSizeCount", lens.previewSizes.size)
        put("largestPreviewSize", lens.previewSizes.firstOrNull()?.let(::sizeJson) ?: JSONObject.NULL)
        put("rawAdvertised", lens.rawSupported)
        put("rawSizeCount", lens.rawSizes.size)
        put("largestRawSize", lens.rawSizes.firstOrNull()?.let(::sizeJson) ?: JSONObject.NULL)
        put("manualSensor", lens.manualSensor)
        put("burstCapture", lens.burstCapture)
        put("ultraHighResolutionSensor", lens.maxResolutionSensor)
        put("qualification", JSONObject().apply {
            put("previewSessionQualified", lens.qualification.previewSessionQualified)
            put("rawSessionQualified", lens.qualification.rawSessionQualified)
            put("qualifiedRawSize", lens.qualification.qualifiedRawSize?.let(::sizeJson) ?: JSONObject.NULL)
            put("detail", lens.qualification.detail)
            put("checkedAtElapsedRealtimeMs", lens.qualification.checkedAtElapsedRealtimeMs)
        })
    }

    private fun sizeJson(size: android.util.Size): JSONObject = JSONObject()
        .put("width", size.width)
        .put("height", size.height)

    private fun facingLabel(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
        else -> "unknown"
    }
}
