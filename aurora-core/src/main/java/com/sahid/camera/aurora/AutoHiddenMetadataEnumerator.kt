package com.sahid.camera.aurora

import org.json.JSONArray
import org.json.JSONObject

data class AutoHiddenCameraInfo(
    val id: String,
    val advertised: Boolean,
    val facing: Int?,
    val focalLengthMm: Float?,
    val sensorWidthMm: Float?,
    val sensorHeightMm: Float?,
    val rawCapability: Boolean,
    val privateOutputSizes: List<NativeCameraSize>,
    val yuvOutputSizes: List<NativeCameraSize>,
    val rawOutputSizes: List<NativeCameraSize>,
    val logicalMultiCamera: Boolean,
    val physicalIds: List<String>,
)

/**
 * Background-only metadata discovery. It never opens a camera and is therefore safe to run while
 * the primary preview is already visible. Its job is to make extra metadata-addressable lenses
 * appear automatically without delaying app startup.
 */
object AutoHiddenMetadataEnumerator {
    init {
        System.loadLibrary("aurora_core")
    }

    fun scan(maxNumericId: Int = 255): List<AutoHiddenCameraInfo> = runCatching {
        val root = JSONObject(nativeScanJson(maxNumericId.coerceIn(0, 1024)))
        parse(root.optJSONArray("cameras"))
    }.getOrDefault(emptyList())

    private external fun nativeScanJson(maxNumericId: Int): String

    private fun parse(values: JSONArray?): List<AutoHiddenCameraInfo> {
        values ?: return emptyList()
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                add(
                    AutoHiddenCameraInfo(
                        id = item.getString("id"),
                        advertised = item.optBoolean("advertised", false),
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
                val item = values.optJSONObject(index) ?: continue
                val width = item.optInt("width", 0)
                val height = item.optInt("height", 0)
                if (width > 0 && height > 0) add(NativeCameraSize(width, height))
            }
        }
    }

    private fun JSONObject.optStringArray(name: String): List<String> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                val value = values.optString(index, "")
                if (value.isNotBlank()) add(value)
            }
        }
    }
}
