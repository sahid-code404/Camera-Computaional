package com.sahid.camera.core

import android.content.Context
import android.os.Build
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-ROM cache for useful metadata-discovered camera routes that have not produced a real frame.
 *
 * Important: this store keeps every endpoint/profile, including aliases that are hidden by the
 * normal selector. [LensFamilyResolver] decides which route is the default for each user-facing lens
 * family. Keeping aliases here means restart never destroys alternate routes that may later be
 * useful as compatibility fallbacks.
 *
 * The cache is also tied to [LensFamilyPolicy.CLASSIFIER_VERSION]. A ROM fingerprint can stay the
 * same across app updates while Aurora's family semantics improve; stale metadata decisions must not
 * survive that change. Learned routes are stored separately and remain valid because they have
 * actual-frame evidence.
 */
class CandidateLensStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private data class Snapshot(
        val routes: List<LensCapability>,
        val autoScanCompleted: Boolean,
    )

    fun load(): List<LensCapability> = loadSnapshot().routes

    /**
     * A completed automatic metadata pass is valid only for the current Build.FINGERPRINT and the
     * current family-classifier version.
     */
    fun hasCompletedAutoScan(): Boolean = loadSnapshot().autoScanCompleted

    fun replace(routes: List<LensCapability>, autoScanCompleted: Boolean = true) {
        val retained = routes
            .filter { it.userVisible && !it.learnedFromCache }
            .map { route ->
                route.copy(
                    discoverySources = (route.discoverySources - CameraDiscoverySource.LEARNED_CACHE) +
                        CameraDiscoverySource.CANDIDATE_CACHE,
                )
            }
            .groupBy { it.stableId }
            .values
            .mapNotNull { sameRoute -> sameRoute.maxByOrNull(::metadataCompletenessScore) }
        save(retained, autoScanCompleted = autoScanCompleted)
    }

    fun removeCamera(cameraId: String) {
        val current = loadSnapshot()
        if (current.routes.none { it.cameraId == cameraId }) return
        save(
            current.routes.filterNot { it.cameraId == cameraId },
            autoScanCompleted = current.autoScanCompleted,
        )
    }

    fun removeRoute(stableId: String) {
        val current = loadSnapshot()
        if (current.routes.none { it.stableId == stableId }) return
        save(
            current.routes.filterNot { it.stableId == stableId },
            autoScanCompleted = current.autoScanCompleted,
        )
    }

    fun clearCurrentBuild() {
        prefs.edit().remove(Build.FINGERPRINT).apply()
    }

    private fun loadSnapshot(): Snapshot {
        val raw = prefs.getString(Build.FINGERPRINT, null)
            ?: return Snapshot(emptyList(), autoScanCompleted = false)
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("classifierVersion", 0) != LensFamilyPolicy.CLASSIFIER_VERSION) {
                return@runCatching Snapshot(emptyList(), autoScanCompleted = false)
            }
            Snapshot(
                routes = root.optJSONArray("routes")?.let(::parseRoutes).orEmpty(),
                autoScanCompleted = root.optBoolean("autoScanCompleted", false),
            )
        }.getOrDefault(Snapshot(emptyList(), autoScanCompleted = false))
    }

    private fun save(routes: List<LensCapability>, autoScanCompleted: Boolean) {
        val payload = JSONObject()
            .put("schemaVersion", 3)
            .put("classifierVersion", LensFamilyPolicy.CLASSIFIER_VERSION)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("savedAtUnixMs", System.currentTimeMillis())
            .put("autoScanCompleted", autoScanCompleted)
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
        put("logicalPhysicalIds", JSONArray(lens.logicalPhysicalIds.toList().sorted()))
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
                    logicalPhysicalIds = item.optStringSet("logicalPhysicalIds"),
                    usableForPreview = true,
                    nativeHardwareLevel = item.optNullableInt("nativeHardwareLevel"),
                    nativeCharacteristicsStatus = item.optNullableInt("nativeCharacteristicsStatus"),
                    qualification = LensQualification(
                        accessPathOpenQualified = false,
                        previewSessionQualified = previewHint,
                        yuvSessionQualified = yuvHint,
                        rawSessionQualified = false,
                        qualifiedRawSize = null,
                        detail = "Persistent metadata profile; first live frame still proves route",
                    ),
                )
            )
        }
    }

    private fun metadataCompletenessScore(lens: LensCapability): Int {
        var score = 0
        if (lens.focalLengthMm != null) score += 4
        if (lens.sensorWidthMm != null && lens.sensorHeightMm != null) score += 4
        if (lens.horizontalFovDegrees != null) score += 2
        if (lens.previewSizes.isNotEmpty()) score += 3
        if (lens.yuvSizes.isNotEmpty()) score += 2
        if (lens.rawSizes.isNotEmpty()) score += 1
        if (lens.logicalPhysicalIds.isNotEmpty()) score += 3
        return score
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

    private fun JSONObject.optStringSet(name: String): Set<String> {
        val values = optJSONArray(name) ?: return emptySet()
        return buildSet {
            for (index in 0 until values.length()) {
                val value = values.optString(index, "")
                if (value.isNotBlank()) add(value)
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
