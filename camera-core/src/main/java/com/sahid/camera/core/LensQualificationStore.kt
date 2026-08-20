package com.sahid.camera.core

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the latest qualification evidence by OS/device build fingerprint.
 *
 * Cached data is diagnostic evidence only. It is never trusted to expose a lens without
 * a fresh runtime session check during the current app run.
 */
class LensQualificationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun save(report: CameraQualificationReport) {
        val payload = JSONObject()
            .put("schemaVersion", 4)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("javaDirectIds", JSONArray(report.discovery.javaDirectIds))
            .put("ndkDirectIds", JSONArray(report.discovery.ndkDirectIds))
            .put("logicalTopology", topologyJson(report.discovery.logicalTopology))
            .put("hiddenProbeMaxNumericId", report.discovery.hiddenProbeMaxNumericId)
            .put("hiddenProbeAttemptedCount", report.discovery.hiddenProbeAttemptedCount)
            .put("hiddenMetadataIds", JSONArray(report.discovery.hiddenMetadataIds))
            .put("hiddenDiscoveredIds", JSONArray(report.discovery.hiddenDiscoveredIds))
            .put("hiddenLogicalTopology", topologyJson(report.discovery.hiddenLogicalTopology))
            .put("candidates", JSONArray().apply {
                report.candidates.forEach { lens ->
                    put(JSONObject().apply {
                        put("stableId", lens.stableId)
                        put("cameraId", lens.cameraId)
                        put("logicalCameraId", lens.logicalCameraId)
                        put("physicalCameraId", lens.physicalCameraId ?: JSONObject.NULL)
                        put("accessPath", lens.accessPath.name)
                        put("discoverySources", JSONArray(lens.discoverySources.map { it.name }))
                        put("focalLengthMm", lens.focalLengthMm ?: JSONObject.NULL)
                        put("sensorWidthMm", lens.sensorWidthMm ?: JSONObject.NULL)
                        put("sensorHeightMm", lens.sensorHeightMm ?: JSONObject.NULL)
                        put("horizontalFovDegrees", lens.horizontalFovDegrees ?: JSONObject.NULL)
                        put("openQualified", lens.qualification.accessPathOpenQualified)
                        put("previewQualified", lens.qualification.previewSessionQualified)
                        put("yuvQualified", lens.qualification.yuvSessionQualified)
                        put("rawAdvertised", lens.rawSupported)
                        put("rawQualified", lens.qualification.rawSessionQualified)
                        put("userVisible", lens.userVisible)
                        put("qualificationDetail", lens.qualification.detail)
                    })
                }
            })

        prefs.edit()
            .putString(Build.FINGERPRINT, payload.toString())
            .apply()
    }

    fun loadCurrentBuildJson(): String? = prefs.getString(Build.FINGERPRINT, null)

    private fun topologyJson(topology: Map<String, List<String>>): JSONObject = JSONObject().apply {
        topology.forEach { (logicalId, physicalIds) ->
            put(logicalId, JSONArray(physicalIds))
        }
    }

    private companion object {
        const val PREFS_NAME = "camera_phase01_qualification_v4"
    }
}
