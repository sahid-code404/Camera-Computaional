package com.sahid.camera.core

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Produces a portable Phase-01 discovery/session report for physical-device testing. */
object CameraDiagnostics {
    fun toJson(report: CameraQualificationReport): String = JSONObject().apply {
        put("schemaVersion", 5)
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
            put("cameraLabMode", report.discovery.cameraLabMode)
            put("javaDirectIds", stringArray(report.discovery.javaDirectIds))
            put("ndkDirectIds", stringArray(report.discovery.ndkDirectIds))
            put("logicalTopology", topologyJson(report.discovery.logicalTopology))
            put("javaOnlyIds", stringArray(
                report.discovery.javaDirectIds.filterNot(report.discovery.ndkDirectIds::contains)
            ))
            put("ndkOnlyIds", stringArray(
                report.discovery.ndkDirectIds.filterNot(report.discovery.javaDirectIds::contains)
            ))
            put("hiddenProbe", JSONObject().apply {
                put("maxNumericId", report.discovery.hiddenProbeMaxNumericId)
                put("attemptedCount", report.discovery.hiddenProbeAttemptedCount)
                put("ndkMetadataValidIds", stringArray(report.discovery.hiddenMetadataIds))
                put("ndkMetadataHiddenDiscoveredIds", stringArray(report.discovery.hiddenDiscoveredIds))
                put("javaMetadataValidIds", stringArray(report.discovery.deepJavaMetadataIds))
                put("hiddenLogicalTopology", topologyJson(report.discovery.hiddenLogicalTopology))
                put("ndkMetadataRejectedStatuses", intMapJson(report.discovery.hiddenRejectedStatuses))
                put("javaMetadataFailures", stringMapJson(report.discovery.deepJavaMetadataFailures))
                put("javaDirectOpenResults", stringMapJson(report.discovery.deepJavaOpenResults))
                put("javaDirectOpenSucceededIds", stringArray(report.discovery.deepJavaOpenSucceededIds))
                put("ndkDirectOpenStatuses", intMapJson(report.discovery.deepNdkOpenStatuses))
                put("ndkDirectOpenSucceededIds", stringArray(report.discovery.deepNdkOpenSucceededIds))
                put("deepOpenDiscoveredIds", stringArray(report.discovery.deepOpenDiscoveredIds))
                put("priorityEvidence", JSONObject().apply {
                    PRIORITY_IDS.forEach { cameraId ->
                        put(cameraId, JSONObject().apply {
                            put("javaAdvertised", cameraId in report.discovery.javaDirectIds)
                            put("ndkAdvertised", cameraId in report.discovery.ndkDirectIds)
                            put("javaMetadata", cameraId in report.discovery.deepJavaMetadataIds)
                            put("ndkMetadataStatus", report.discovery.hiddenRejectedStatuses[cameraId]
                                ?: if (cameraId in report.discovery.hiddenMetadataIds) 0 else JSONObject.NULL)
                            put("javaOpen", report.discovery.deepJavaOpenResults[cameraId] ?: JSONObject.NULL)
                            put("javaOpenSucceeded", cameraId in report.discovery.deepJavaOpenSucceededIds)
                            put("ndkOpenStatus", report.discovery.deepNdkOpenStatuses[cameraId] ?: JSONObject.NULL)
                            put("ndkOpenSucceeded", cameraId in report.discovery.deepNdkOpenSucceededIds)
                            put("deepOpenDiscovered", cameraId in report.discovery.deepOpenDiscoveredIds)
                        })
                    }
                })
            })
        })
        put("summary", JSONObject().apply {
            put("candidatePathCount", report.candidates.size)
            put("uniqueCandidateCameraCount", report.candidates.map { it.cameraId }.distinct().size)
            put("visibleLensCount", report.visibleLenses.size)
            put("verifiedRawLensCount", report.visibleLenses.count { it.rawUsable })
            put("hiddenMetadataValidCount", report.discovery.hiddenMetadataIds.size)
            put("deepJavaMetadataValidCount", report.discovery.deepJavaMetadataIds.size)
            put("deepOpenDiscoveredCount", report.discovery.deepOpenDiscoveredIds.size)
            put("hiddenLogicalCameraCount", report.discovery.hiddenLogicalTopology.size)
            put("hiddenVisibleLensCount", report.visibleLenses.count {
                CameraDiscoverySource.HIDDEN_ID_PROBE in it.discoverySources
            })
            put("deepOpenVisibleLensCount", report.visibleLenses.count { it.deepOpenDiscovered })
            put("nativePreviewQualifiedCount", report.candidates.count {
                it.accessPath == CameraAccessPath.NDK_DIRECT && it.qualification.previewSessionQualified
            })
            put("yuvFallbackVisibleCount", report.visibleLenses.count {
                !it.qualification.previewSessionQualified && it.qualification.yuvSessionQualified
            })
        })
        put("visibleLenses", JSONArray().apply {
            report.visibleLenses.forEach { lens ->
                put(JSONObject().apply {
                    put("cameraId", lens.cameraId)
                    put("displayName", lens.displayName)
                    put("accessPath", lens.accessPath.name)
                    put("discoverySources", stringArray(lens.discoverySources.map { it.name }.sorted()))
                    put("hiddenDiscovered", CameraDiscoverySource.HIDDEN_ID_PROBE in lens.discoverySources)
                    put("deepOpenDiscovered", lens.deepOpenDiscovered)
                    put("renderMode", renderMode(lens))
                    put("horizontalFovDegrees", lens.horizontalFovDegrees ?: JSONObject.NULL)
                })
            }
        })
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
        put("hiddenDiscovered", CameraDiscoverySource.HIDDEN_ID_PROBE in lens.discoverySources)
        put("deepOpenDiscovered", lens.deepOpenDiscovered)
        put("displayName", lens.displayName)
        put("facing", facingLabel(lens.facing))
        put("focalLengthMm", lens.focalLengthMm ?: JSONObject.NULL)
        put("sensorWidthMm", lens.sensorWidthMm ?: JSONObject.NULL)
        put("sensorHeightMm", lens.sensorHeightMm ?: JSONObject.NULL)
        put("horizontalFovDegrees", lens.horizontalFovDegrees ?: JSONObject.NULL)
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
        put("userVisible", lens.userVisible)
        put("renderMode", renderMode(lens))
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

    private fun renderMode(lens: LensCapability): String = when {
        lens.qualification.previewSessionQualified && lens.accessPath == CameraAccessPath.NDK_DIRECT ->
            "NDK_SURFACE"
        lens.qualification.previewSessionQualified -> "CAMERA2_SURFACE"
        lens.qualification.yuvSessionQualified && lens.accessPath == CameraAccessPath.NDK_DIRECT ->
            "NDK_YUV_CPU"
        lens.qualification.yuvSessionQualified -> "CAMERA2_YUV_CPU"
        lens.qualification.rawSessionQualified -> "RAW_DIAGNOSTIC_ONLY"
        else -> "NONE"
    }

    private fun topologyJson(topology: Map<String, List<String>>): JSONObject = JSONObject().apply {
        topology.forEach { (logicalId, physicalIds) -> put(logicalId, stringArray(physicalIds)) }
    }

    private fun intMapJson(values: Map<String, Int>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
    }

    private fun stringMapJson(values: Map<String, String>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
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

    private val PRIORITY_IDS = listOf("0", "1", "20", "21", "22", "61", "100", "101")
}
