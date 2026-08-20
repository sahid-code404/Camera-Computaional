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

/**
 * Native Camera2 discovery used as a second, independent view of the camera service.
 *
 * This intentionally mirrors MotionCam's public discovery strategy at the enumeration
 * layer: ask ACameraManager for every directly exposed ID, then inspect that exact ID's
 * metadata. Camera/Aurora still performs its own runtime session qualification before
 * exposing a lens to the normal UI.
 */
object NativeCameraEnumerator {
    init {
        System.loadLibrary("aurora_core")
    }

    fun enumerate(): List<NativeCameraInfo> = runCatching {
        val root = JSONObject(nativeEnumerateJson())
        val cameras = root.optJSONArray("cameras") ?: JSONArray()
        buildList(cameras.length()) {
            for (index in 0 until cameras.length()) {
                val item = cameras.getJSONObject(index)
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
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    /** Diagnostic-only exact-ID open probe. Real NDK sessions use [NativeCameraSession]. */
    fun probeDirectOpen(cameraId: String): NativeCameraOpenProbe =
        NativeCameraOpenProbe(cameraId, nativeProbeDirectOpen(cameraId))

    private external fun nativeEnumerateJson(): String
    private external fun nativeProbeDirectOpen(cameraId: String): Int

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
}
