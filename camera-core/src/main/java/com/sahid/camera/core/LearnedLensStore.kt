package com.sahid.camera.core

import android.content.Context
import android.os.Build
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small per-build topology cache used only to make camera startup fast.
 *
 * Deep discovery is expensive on OEMs that hide auxiliary IDs. Once a route has produced a
 * real frame, we remember the exact route for this Build.FINGERPRINT. A ROM/OTA fingerprint
 * change automatically invalidates it. The selected preview still performs a real camera open,
 * so stale hardware state cannot silently produce frames from the wrong endpoint.
 */
class LearnedLensStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    data class Snapshot(
        val deepScanCompleted: Boolean,
        val routes: List<LensCapability>,
    )

    fun load(): Snapshot {
        val raw = prefs.getString(Build.FINGERPRINT, null)
            ?: return Snapshot(deepScanCompleted = false, routes = emptyList())
        return runCatching {
            val root = JSONObject(raw)
            val routes = root.optJSONArray("routes")?.let(::parseRoutes).orEmpty()
            Snapshot(
                deepScanCompleted = root.optBoolean("deepScanCompleted", false),
                routes = routes,
            )
        }.getOrElse {
            Snapshot(deepScanCompleted = false, routes = emptyList())
        }
    }

    /** Save only routes that already produced a usable preview/YUV frame in the deep pass. */
    fun saveDeepScan(report: CameraQualificationReport) {
        val payload = JSONObject()
            .put("schemaVersion", 1)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("deepScanCompleted", true)
            .put("savedAtUnixMs", System.currentTimeMillis())
            .put("routes", JSONArray().apply {
                report.visibleLenses.forEach { lens -> put(routeJson(lens)) }
            })
        prefs.edit().putString(Build.FINGERPRINT, payload.toString()).apply()
    }

    fun clearCurrentBuild() {
        prefs.edit().remove(Build.FINGERPRINT).apply()
    }

    private fun routeJson(lens: LensCapability): JSONObject = JSONObject().apply {
        put("cameraId", lens.cameraId)
        put("logicalCameraId", lens.logicalCameraId)
        put("physicalCameraId", lens.physicalCameraId ?: JSONObject.NULL)
        put("accessPath", lens.accessPath.name)
        put("discoverySources", JSONArray(lens.discoverySources.map { it.name }))
        put("facing", lens.facing ?: JSONObject.NULL)
        put("displayName", lens.displayName)
        put("focalLengthMm", lens.focalLengthMm ?: JSONObject.NULL)
        put("sensorWidthMm", lens.sensorWidthMm ?: JSONObject.NULL)
        put("sensorHeightMm", lens.sensorHeightMm ?: JSONObject.NULL)
        put("horizontalFovDegrees", lens.horizontalFovDegrees ?: JSONObject.NULL)
        put("rawSupported", lens.rawSupported)
        put("rawSizes", sizeArray(lens.rawSizes))
        put("previewSizes", sizeArray(lens.previewSizes))
        put("yuvSizes", sizeArray(lens.yuvSizes))
        put("manualSensor", lens.manualSensor)
        put("burstCapture", lens.burstCapture)
        put("maxResolutionSensor", lens.maxResolutionSensor)
        put("logicalMultiCamera", lens.isLogicalMultiCamera)
        put("nativeHardwareLevel", lens.nativeHardwareLevel ?: JSONObject.NULL)
        put("nativeCharacteristicsStatus", lens.nativeCharacteristicsStatus ?: JSONObject.NULL)
        put("qualification", JSONObject().apply {
            put("open", lens.qualification.accessPathOpenQualified)
            put("preview", lens.qualification.previewSessionQualified)
            put("yuv", lens.qualification.yuvSessionQualified)
            put("raw", lens.qualification.rawSessionQualified)
            put("qualifiedRawSize", lens.qualification.qualifiedRawSize?.let(::sizeJson) ?: JSONObject.NULL)
        })
    }

    private fun parseRoutes(values: JSONArray): List<LensCapability> = buildList {
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val accessPath = runCatching {
                CameraAccessPath.valueOf(item.getString("accessPath"))
            }.getOrNull() ?: continue
            val sources = buildSet {
                val array = item.optJSONArray("discoverySources")
                if (array != null) {
                    for (sourceIndex in 0 until array.length()) {
                        runCatching {
                            CameraDiscoverySource.valueOf(array.getString(sourceIndex))
                        }.getOrNull()?.let(::add)
                    }
                }
                add(CameraDiscoverySource.LEARNED_CACHE)
            }
            val qualification = item.optJSONObject("qualification") ?: JSONObject()
            val previewSizes = item.optSizeArray("previewSizes")
            val yuvSizes = item.optSizeArray("yuvSizes")
            val rawSizes = item.optSizeArray("rawSizes")
            add(
                LensCapability(
                    cameraId = item.getString("cameraId"),
                    logicalCameraId = item.optString("logicalCameraId", item.getString("cameraId")),
                    physicalCameraId = item.optNullableString("physicalCameraId"),
                    accessPath = accessPath,
                    discoverySources = sources,
                    facing = item.optNullableInt("facing"),
                    displayName = item.optString("displayName", "ID ${item.getString("cameraId")}"),
                    focalLengthMm = item.optNullableDouble("focalLengthMm")?.toFloat(),
                    sensorWidthMm = item.optNullableDouble("sensorWidthMm")?.toFloat(),
                    sensorHeightMm = item.optNullableDouble("sensorHeightMm")?.toFloat(),
                    horizontalFovDegrees = item.optNullableDouble("horizontalFovDegrees")?.toFloat(),
                    rawSupported = item.optBoolean("rawSupported", false),
                    rawSizes = rawSizes,
                    previewSizes = previewSizes,
                    yuvSizes = yuvSizes,
                    manualSensor = item.optBoolean("manualSensor", false),
                    burstCapture = item.optBoolean("burstCapture", false),
                    maxResolutionSensor = item.optBoolean("maxResolutionSensor", false),
                    isLogicalMultiCamera = item.optBoolean("logicalMultiCamera", false),
                    usableForPreview = previewSizes.isNotEmpty() || yuvSizes.isNotEmpty(),
                    nativeHardwareLevel = item.optNullableInt("nativeHardwareLevel"),
                    nativeCharacteristicsStatus = item.optNullableInt("nativeCharacteristicsStatus"),
                    qualification = LensQualification(
                        accessPathOpenQualified = qualification.optBoolean("open", true),
                        previewSessionQualified = qualification.optBoolean("preview", false),
                        yuvSessionQualified = qualification.optBoolean("yuv", false),
                        rawSessionQualified = qualification.optBoolean("raw", false),
                        qualifiedRawSize = qualification.optJSONObject("qualifiedRawSize")?.toSize(),
                        detail = "Learned route from previous real-frame qualification",
                    ),
                )
            )
        }
    }

    private fun sizeArray(values: List<Size>): JSONArray = JSONArray().apply {
        values.forEach { put(sizeJson(it)) }
    }

    private fun sizeJson(size: Size): JSONObject = JSONObject()
        .put("width", size.width)
        .put("height", size.height)

    private fun JSONObject.toSize(): Size? {
        val width = optInt("width", 0)
        val height = optInt("height", 0)
        return if (width > 0 && height > 0) Size(width, height) else null
    }

    private fun JSONObject.optSizeArray(name: String): List<Size> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                values.optJSONObject(index)?.toSize()?.let(::add)
            }
        }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) getInt(name) else null

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (has(name) && !isNull(name)) getDouble(name) else null

    private companion object {
        const val PREFS_NAME = "camera_learned_lenses_v1"
    }
}
