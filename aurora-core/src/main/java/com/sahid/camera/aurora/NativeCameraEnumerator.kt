package com.sahid.camera.aurora

import org.json.JSONArray
import org.json.JSONObject

data class NativeCameraSize(
    val width: Int,
    val height: Int,
)

data class NativeCameraInfo(
    val id: String,
    val characteristicsStatus: Int,
    val hardwareLevel: Int?,
    val facing: Int?,
    val focalLengthMm: Float?,
    val sensorWidthMm: Float?,
    val sensorHeightMm: Float?,
    val rawCapability: Boolean,
    val privateOutputSizes: List<NativeCameraSize>,
    val yuvOutputSizes: List<NativeCameraSize>,
    val rawOutputSizes: List<NativeCameraSize>,
    val logicalMultiCamera: Boolean = false,
    val physicalIds: List<String> = emptyList(),
) {
    val hasRawOutput: Boolean
        get() = rawOutputSizes.isNotEmpty()
}

data class NativeCameraOpenProbe(
    val cameraId: String,
    val status: Int,
) {
    val opened: Boolean
        get() = status == 0
}

data class HiddenCameraProbeResult(
    val maxNumericId: Int,
    val attemptedCount: Int,
    val validCameras: List<NativeCameraInfo>,
    val hiddenIds: List<String>,
    val rejectedStatuses: Map<String, Int>,
    /** Batched NDK open results produced with one ACameraManager instance. */
    val directOpenStatuses: Map<String, Int> = emptyMap(),
    val directOpenSucceededIds: List<String> = emptyList(),
) {
    val validIds: List<String>
        get() = validCameras.map { it.id }

    val logicalTopology: Map<String, List<String>>
        get() = validCameras
            .filter { it.physicalIds.isNotEmpty() }
            .associate { it.id to it.physicalIds }

    companion object {
        fun empty(maxNumericId: Int) = HiddenCameraProbeResult(
            maxNumericId = maxNumericId,
            attemptedCount = 0,
            validCameras = emptyList(),
            hiddenIds = emptyList(),
            rejectedStatuses = emptyMap(),
            directOpenStatuses = emptyMap(),
            directOpenSucceededIds = emptyList(),
        )
    }
}

/**
 * Native Camera2 discovery used as an independent view of the camera service.
 *
 * [includeDirectOpenFallback] lets the UI use a very cheap metadata-only background pass while a
 * live preview is already running. The explicit compatibility/deep scan can still enable direct
 * open fallback for metadata-filtered IDs. Both modes reuse one ACameraManager per scan.
 */
object NativeCameraEnumerator {
    const val DEFAULT_HIDDEN_SCAN_MAX_ID = 255

    init {
        System.loadLibrary("aurora_core")
    }

    fun enumerate(): List<NativeCameraInfo> = runCatching {
        parseCameraArray(JSONObject(nativeEnumerateJson()).optJSONArray("cameras"))
    }.getOrDefault(emptyList())

    fun searchHiddenNumericIds(
        maxNumericId: Int = DEFAULT_HIDDEN_SCAN_MAX_ID,
        includeDirectOpenFallback: Boolean = true,
    ): HiddenCameraProbeResult = runCatching {
        val boundedMax = maxNumericId.coerceIn(0, 1024)
        val root = JSONObject(nativeSearchHiddenNumericJson(boundedMax, includeDirectOpenFallback))
        HiddenCameraProbeResult(
            maxNumericId = root.optInt("maxId", boundedMax),
            attemptedCount = root.optInt("attemptedCount", 0),
            validCameras = parseCameraArray(root.optJSONArray("validCameras")),
            hiddenIds = root.optStringArray("hiddenIds"),
            rejectedStatuses = root.optIntMap("rejectedStatuses"),
            directOpenStatuses = root.optIntMap("directOpenStatuses"),
            directOpenSucceededIds = root.optStringArray("directOpenSucceededIds"),
        )
    }.getOrElse { HiddenCameraProbeResult.empty(maxNumericId.coerceIn(0, 1024)) }

    /** Diagnostic-only exact-ID open probe. Real NDK sessions use [NativeCameraSession]. */
    fun probeDirectOpen(cameraId: String): NativeCameraOpenProbe =
        NativeCameraOpenProbe(cameraId, nativeProbeDirectOpen(cameraId))

    private external fun nativeEnumerateJson(): String
    private external fun nativeSearchHiddenNumericJson(
        maxNumericId: Int,
        includeDirectOpenFallback: Boolean,
    ): String
    private external fun nativeProbeDirectOpen(cameraId: String): Int

    private fun parseCameraArray(values: JSONArray?): List<NativeCameraInfo> {
        values ?: return emptyList()
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index)
                add(
                    NativeCameraInfo(
                        id = item.getString("id"),
                        characteristicsStatus = item.optInt("characteristicsStatus", -1),
                        hardwareLevel = item.optNullableInt("hardwareLevel"),
                        facing = item.optNullableInt("facing"),
                        focalLengthMm = item.optNullableDouble("focalLengthMm")?.toFloat(),
                        sensorWidthMm = item.optNullableDouble("sensorWidthMm")?.toFloat(),
                        sensorHeightMm = item.optNullableDouble("sensorHeightMm")?.toFloat(),
                        rawCapability = item.optBoolean("rawCapability", false),
                        privateOutputSizes = item.optSizeArray("privateOutputSizes"),
                        yuvOutputSizes = item.optSizeArray("yuvOutputSizes"),
                        rawOutputSizes = item.optSizeArray("rawOutputSizes"),
                        logicalMultiCamera = item.optBoolean("logicalMultiCamera", false),
                        physicalIds = item.optStringArray("physicalIds"),
                    )
                )
            }
        }
    }

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) getInt(name) else null

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (has(name) && !isNull(name)) getDouble(name) else null

    private fun JSONObject.optSizeArray(name: String): List<NativeCameraSize> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index)
                add(NativeCameraSize(item.getInt("width"), item.getInt("height")))
            }
        }
    }

    private fun JSONObject.optStringArray(name: String): List<String> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                val value = values.optString(index, "")
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    private fun JSONObject.optIntMap(name: String): Map<String, Int> {
        val values = optJSONObject(name) ?: return emptyMap()
        return buildMap {
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, values.optInt(key, Int.MIN_VALUE))
            }
        }
    }
}
