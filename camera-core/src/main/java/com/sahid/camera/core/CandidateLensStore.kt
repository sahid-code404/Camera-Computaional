package com.sahid.camera.core

import android.content.Context
import android.os.Build
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-ROM cache for useful metadata-discovered lenses that have not produced a real frame yet.
 *
 * This is intentionally separate from [LearnedLensStore]. A candidate can be restored instantly on
 * the next launch, but it never gains LEARNED_CACHE status until CameraPreviewController observes
 * an actual TextureView/ImageReader frame.
 */
class CandidateLensStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<LensCapability> {
        val raw = prefs.getString(Build.FINGERPRINT, null) ?: return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            root.optJSONArray("routes")?.let(::parseRoutes).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun replace(routes: List<LensCapability>) {
        val filtered = LensValueFilter.filterForSelector(
            routes
                .filter { it.userVisible && !it.learnedFromCache }
                .map { route ->
                    route.copy(
                        discoverySources = (route.discoverySources - CameraDiscoverySource.LEARNED_CACHE) +
                            CameraDiscoverySource.CANDIDATE_CACHE,
                    )
                }
        )
        save(filtered)
    }

    fun removeCamera(cameraId: String) {
        val current = load()
        if (current.none { it.cameraId == cameraId }) return
        save(current.filterNot { it.cameraId == cameraId })
    }

    fun removeRoute(stableId: String) {
        val current = load()
        if (current.none { it.stableId == stableId }) return
        save(current.filterNot { it.stableId == stableId })
    }

    fun clearCurrentBuild() {
        prefs.edit().remove(Build.FINGERPRINT).apply()
    }

    private fun save(routes: List<LensCapability>) {
        val payload = JSONObject()
            .put("schemaVersion", 1)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("savedAtUnixMs", System.currentTimeMillis())
            .put("routes", JSONArray().apply {
                routes.forEach { put(routeJson(it)) }
            })
        prefs.edit().putString(Build.FINGERPRINT, payload.toString()).apply()
    }

    private fun routeJson(lens: LensCapability): JSONObject = JSONObject().apply {
        put("cameraId", lens.cameraId)
        put("logicalCameraId", lens.logicalCameraId)
        put("physicalCameraId", lens.physicalCameraId ?: JSONObject.NULL)
        put("accessPath", lens.accessPath.name)
        put(
            "discoverySources",
            JSONArray(
                lens.discoverySources
                    .filterNot { it == CameraDiscoverySource.LEARNED_CACHE }
                    .map { it.name }
            )
        )
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
        put("previewHint", lens.qualification.previewSessionQualified)
        put("yuvHint", lens.qualification.yuvSessionQualified)
    }

    private fun parseRoutes(values: JSONArray): List<LensCapability> = buildList {
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val accessPath = runCatching {
                CameraAccessPath.valueOf(item.getString("accessPath"))
            }.getOrNull() ?: continue
            val previewSizes = item.optSizeArray("previewSizes")
            val yuvSizes = item.optSizeArray("yuvSizes")
            if (previewSizes.isEmpty() && yuvSizes.isEmpty()) continue
            val rawSizes = item.optSizeArray("rawSizes")
            val sources = buildSet {
                val array = item.optJSONArray("discoverySources")
                if (array != null) {
                    for (sourceIndex in 0 until array.length()) {
                        runCatching {
                            CameraDiscoverySource.valueOf(array.getString(sourceIndex))
                        }.getOrNull()?.let(::add)
                    }
                }
                remove(CameraDiscoverySource.LEARNED_CACHE)
                add(CameraDiscoverySource.CANDIDATE_CACHE)
            }
            val previewHint = item.optBoolean("previewHint", previewSizes.isNotEmpty())
            val yuvHint = item.optBoolean("yuvHint", !previewHint && yuvSizes.isNotEmpty())
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
                    usableForPreview = true,
                    nativeHardwareLevel = item.optNullableInt("nativeHardwareLevel"),
                    nativeCharacteristicsStatus = item.optNullableInt("nativeCharacteristicsStatus"),
                    qualification = LensQualification(
                        accessPathOpenQualified = false,
                        previewSessionQualified = previewHint,
                        yuvSessionQualified = yuvHint,
                        rawSessionQualified = false,
                        qualifiedRawSize = null,
                        detail = "Persistent metadata candidate; first live frame still proves route",
                    ),
                )
            )
        }
    }

    private fun sizeArray(values: List<Size>): JSONArray = JSONArray().apply {
        values.forEach { size ->
            put(JSONObject().put("width", size.width).put("height", size.height))
        }
    }

    private fun JSONObject.optSizeArray(name: String): List<Size> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val width = item.optInt("width", 0)
                val height = item.optInt("height", 0)
                if (width > 0 && height > 0) add(Size(width, height))
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
        const val PREFS_NAME = "camera_ready_candidates_v1"
    }
}
