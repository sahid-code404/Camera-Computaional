package com.sahid.camera.core

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
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
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    data class Snapshot(
        val deepScanCompleted: Boolean,
        val routes: List<LensCapability>,
    )

    fun load(): Snapshot {
        val raw = prefs.getString(Build.FINGERPRINT, null)
        if (raw != null) {
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

        // Seamless upgrade from Phase-01 schema-v5 diagnostics. This prevents users who already
        // completed the expensive hidden scan from paying that cost once more after this update.
        return migrateLegacyQualificationCache()
            ?: Snapshot(deepScanCompleted = false, routes = emptyList())
    }

    /** Save only routes that already produced a usable preview/YUV frame in the deep pass. */
    fun saveDeepScan(report: CameraQualificationReport) {
        saveRoutes(report.visibleLenses, deepScanCompleted = true)
    }

    fun clearCurrentBuild() {
        prefs.edit().remove(Build.FINGERPRINT).apply()
    }

    private fun saveRoutes(routes: List<LensCapability>, deepScanCompleted: Boolean) {
        val payload = JSONObject()
            .put("schemaVersion", 1)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("deepScanCompleted", deepScanCompleted)
            .put("savedAtUnixMs", System.currentTimeMillis())
            .put("routes", JSONArray().apply {
                routes.forEach { lens -> put(routeJson(lens)) }
            })
        prefs.edit().putString(Build.FINGERPRINT, payload.toString()).apply()
    }

    private fun migrateLegacyQualificationCache(): Snapshot? {
        val raw = legacyPrefs.getString(Build.FINGERPRINT, null) ?: return null
        val routes = runCatching {
            val root = JSONObject(raw)
            val candidates = root.optJSONArray("candidates") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until candidates.length()) {
                    val item = candidates.optJSONObject(index) ?: continue
                    if (!item.optBoolean("userVisible", false)) continue
                    legacyCandidate(item)?.let(::add)
                }
            }
                .groupBy { it.cameraId }
                .values
                .mapNotNull { paths -> paths.minByOrNull(::legacyRouteScore) }
        }.getOrNull() ?: return null

        // The old cache only exists after a complete Phase-01 qualification report was saved.
        // Treat it as a completed deep scan even when the device had no hidden lenses.
        saveRoutes(routes, deepScanCompleted = true)
        return Snapshot(deepScanCompleted = true, routes = routes)
    }

    private fun legacyCandidate(item: JSONObject): LensCapability? {
        val accessPath = runCatching {
            CameraAccessPath.valueOf(item.getString("accessPath"))
        }.getOrNull() ?: return null
        val qualificationObject = item.optJSONObject("qualification") ?: JSONObject()
        val previewSize = item.optJSONObject("largestPreviewSize")?.toSize()
        val yuvSize = item.optJSONObject("largestYuvSize")?.toSize()
        val rawSize = item.optJSONObject("largestRawSize")?.toSize()
        val sources = buildSet {
            val array = item.optJSONArray("discoverySources")
            if (array != null) {
                for (index in 0 until array.length()) {
                    runCatching {
                        CameraDiscoverySource.valueOf(array.getString(index))
                    }.getOrNull()?.let(::add)
                }
            }
            add(CameraDiscoverySource.LEARNED_CACHE)
        }
        return LensCapability(
            cameraId = item.getString("cameraId"),
            logicalCameraId = item.optString("logicalCameraId", item.getString("cameraId")),
            physicalCameraId = item.optNullableString("physicalCameraId"),
            accessPath = accessPath,
            discoverySources = sources,
            facing = when (item.optString("facing")) {
                "back" -> CameraCharacteristics.LENS_FACING_BACK
                "front" -> CameraCharacteristics.LENS_FACING_FRONT
                "external" -> CameraCharacteristics.LENS_FACING_EXTERNAL
                else -> null
            },
            displayName = item.optString("displayName", "ID ${item.getString("cameraId")}"),
            focalLengthMm = item.optNullableDouble("focalLengthMm")?.toFloat(),
            sensorWidthMm = item.optNullableDouble("sensorWidthMm")?.toFloat(),
            sensorHeightMm = item.optNullableDouble("sensorHeightMm")?.toFloat(),
            horizontalFovDegrees = item.optNullableDouble("horizontalFovDegrees")?.toFloat(),
            rawSupported = item.optBoolean("rawAdvertised", false),
            rawSizes = listOfNotNull(rawSize),
            previewSizes = listOfNotNull(previewSize),
            yuvSizes = listOfNotNull(yuvSize),
            manualSensor = item.optBoolean("manualSensor", false),
            burstCapture = item.optBoolean("burstCapture", false),
            maxResolutionSensor = item.optBoolean("ultraHighResolutionSensor", false),
            isLogicalMultiCamera = item.optBoolean("logicalMultiCamera", false),
            usableForPreview = previewSize != null || yuvSize != null,
            nativeHardwareLevel = item.optNullableInt("nativeHardwareLevel"),
            nativeCharacteristicsStatus = item.optNullableInt("nativeCharacteristicsStatus"),
            qualification = LensQualification(
                accessPathOpenQualified = qualificationObject.optBoolean("accessPathOpenQualified", true),
                previewSessionQualified = qualificationObject.optBoolean("previewSessionQualified", false),
                yuvSessionQualified = qualificationObject.optBoolean("yuvSessionQualified", false),
                rawSessionQualified = qualificationObject.optBoolean("rawSessionQualified", false),
                qualifiedRawSize = qualificationObject.optJSONObject("qualifiedRawSize")?.toSize(),
                detail = "Migrated from previous real-frame qualification",
            ),
        )
    }

    private fun legacyRouteScore(lens: LensCapability): Int {
        val renderer = when {
            lens.qualification.previewSessionQualified -> 0
            lens.qualification.yuvSessionQualified -> 20
            else -> 100
        }
        val access = when (lens.accessPath) {
            CameraAccessPath.JAVA_DIRECT -> 0
            CameraAccessPath.NDK_DIRECT -> 1
            CameraAccessPath.PHYSICAL_VIA_LOGICAL -> 2
        }
        return renderer + access
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
        const val LEGACY_PREFS_NAME = "camera_phase01_qualification_v5"
    }
}
