package com.sahid.camera.core

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Produces a portable Phase-01 discovery/session report for physical-device testing. */
object CameraDiagnostics {
    fun toJson(report: CameraQualificationReport): String = JSONObject().apply {
        put("schemaVersion", 2)
        put("generatedAtUnixMs", System.currentTimeMillis())
        put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("release", Build.VERSION.RELEASE)
            put("buildFingerprint", Build.FINGERPRINT)
        })
        put("discovery", JSONObject().apply {
            put("javaDirectIds", stringArray(report.discovery.javaDirectIds))
            put("ndkDirectIds", stringArray(report.discovery.ndkDirectIds))
            put("logicalTopology", JSONObject().apply {
                report.discovery.logicalTopology.forEach { (logicalId, physicalIds) ->
                    put(logicalId, stringArray(physicalIds))
                }
            })
            put("javaOnlyIds", stringArray(
                report.discovery.javaDirectIds.filterNot(report.discovery.ndkDirectIds::contains)
            ))
            put("ndkOnlyIds", stringArray(
                report.discovery.ndkDirectIds.filterNot(report.discovery.javaDirectIds::contains)
            ))
        })
        put("summary", JSONObject().apply {
            put("candidatePathCount", report.candidates.size)
            put("uniqueCandidateCameraCount", report.candidates.map { it.cameraId }.distinct().size)
            put("visibleLensCount", report.visibleLenses.size)
            put("verifiedRawLensCount", report.visibleLenses.count { it.rawUsable })
            put("ndkOnlyDirectCount", report.discovery.ndkDirectIds.count {
                it !in report.discovery.javaDirectIds
            })
        })
        put("visibleLensIds", stringArray(report.visibleLenses.map { it.cameraId }))
        put("candidates", JSONArray().apply {
            report.candidates.forEach { lens -> put(lensToJson(lens)) }
        })
    }.toString(2)

    private fun lensToJson(lens: LensCapability): JSONObject = JSONObject().apply {
        put("stableId", lens.stableId)
        put("cameraId", lens.cameraId)
        put("logicalCameraId", lens.logicalCameraId)
        put("physicalCameraId", lens.physicalCameraId ?: JSONObject.NULL)
        put("accessPath", lens.accessPath.name)
        put("discoverySources", stringArray(lens.discoverySources.map { it.name }.sorted()))
        put("displayName", lens.displayName)
        put("facing", facingLabel(lens.facing))
        put("focalLengthMm", lens.focalLengthMm ?: JSONObject.NULL)
        put("logicalMultiCamera", lens.isLogicalMultiCamera)
        put("metadataPreviewCandidate", lens.usableForPreview)
        put("previewSizeCount", lens.previewSizes.size)
        put("largestPreviewSize", lens.previewSizes.firstOrNull()?.let(::sizeJson) ?: JSONObject.NULL)
        put("yuvSizeCount", lens.yuvSizes.size)
        put("largestYuvSize", lens.yuvSizes.firstOrNull()?.let(::sizeJson) ?: JSONObject.NULL)
        put("rawAdvertised", lens.rawSupported)
        put("rawSizeCount", lens.rawSizes.size)
        put("largestRawSize", lens.rawSizes.firstOrNull()?.let(::sizeJson) ?: JSONObject.NULL)
        put("manualSensor", lens.manualSensor)
        put("burstCapture", lens.burstCapture)
        put("ultraHighResolutionSensor", lens.maxResolutionSensor)
        put("nativeHardwareLevel", lens.nativeHardwareLevel ?: JSONObject.NULL)
        put("nativeCharacteristicsStatus", lens.nativeCharacteristicsStatus ?: JSONObject.NULL)
        put("qualification", JSONObject().apply {
            put("accessPathOpenQualified", lens.qualification.accessPathOpenQualified)
            put("previewSessionQualified", lens.qualification.previewSessionQualified)
            put("yuvSessionQualified", lens.qualification.yuvSessionQualified)
            put("rawSessionQualified", lens.qualification.rawSessionQualified)
            put("qualifiedRawSize", lens.qualification.qualifiedRawSize?.let(::sizeJson) ?: JSONObject.NULL)
            put("detail", lens.qualification.detail)
            put("checkedAtElapsedRealtimeMs", lens.qualification.checkedAtElapsedRealtimeMs)
        })
    }

    private fun sizeJson(size: android.util.Size): JSONObject = JSONObject()
        .put("width", size.width)
        .put("height", size.height)

    private fun stringArray(values: Collection<String>): JSONArray = JSONArray().apply {
        values.forEach(::put)
    }

    private fun facingLabel(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
        else -> "unknown"
    }
}
