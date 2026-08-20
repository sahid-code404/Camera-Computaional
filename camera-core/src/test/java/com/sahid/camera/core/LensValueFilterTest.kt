package com.sahid.camera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensValueFilterTest {
    @Test
    fun keepsTwoLearnedNonLogicalRoutesEvenWhenMetadataMatches() {
        val first = lens(
            id = "20",
            learned = true,
            focal = 1.9f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 92.0f,
        )
        val second = lens(
            id = "22",
            learned = true,
            focal = 1.9f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 92.0f,
        )

        val result = LensValueFilter.filterForSelector(listOf(first, second))

        assertEquals(listOf("20", "22"), result.map { it.cameraId })
    }

    @Test
    fun missingSensorGeometryNeverCollapsesSimilarFov() {
        val main = lens(
            id = "0",
            learned = true,
            focal = 4.7f,
            sensorWidth = null,
            sensorHeight = null,
            fov = 74.0f,
        )
        val candidate = lens(
            id = "100",
            learned = false,
            focal = 4.7f,
            sensorWidth = null,
            sensorHeight = null,
            fov = 74.0f,
        )

        val result = LensValueFilter.filterForSelector(listOf(main, candidate))

        assertEquals(2, result.size)
    }

    @Test
    fun unprovenExactOpticalAliasCanStillBeSuppressedBehindLearnedRoute() {
        val main = lens(
            id = "0",
            learned = true,
            focal = 4.7f,
            sensorWidth = 5.6f,
            sensorHeight = 4.2f,
            fov = 61.5f,
        )
        val alias = lens(
            id = "100",
            learned = false,
            focal = 4.7f,
            sensorWidth = 5.6f,
            sensorHeight = 4.2f,
            fov = 61.5f,
        )

        val result = LensValueFilter.filterForSelector(listOf(main, alias))

        assertEquals(listOf("0"), result.map { it.cameraId })
    }

    @Test
    fun learnedLogicalAliasCanBeSuppressedByLearnedPhysicalRoute() {
        val physical = lens(
            id = "0",
            learned = true,
            focal = 4.7f,
            sensorWidth = 5.6f,
            sensorHeight = 4.2f,
            fov = 61.5f,
        )
        val logicalAlias = lens(
            id = "61",
            learned = true,
            focal = 4.7f,
            sensorWidth = 5.6f,
            sensorHeight = 4.2f,
            fov = 61.5f,
            logical = true,
        )

        val result = LensValueFilter.filterForSelector(listOf(physical, logicalAlias))

        assertEquals(listOf("0"), result.map { it.cameraId })
        assertTrue(result.none { it.cameraId == "61" })
    }

    private fun lens(
        id: String,
        learned: Boolean,
        focal: Float,
        sensorWidth: Float?,
        sensorHeight: Float?,
        fov: Float?,
        logical: Boolean = false,
    ): LensCapability = LensCapability(
        cameraId = id,
        logicalCameraId = id,
        physicalCameraId = null,
        accessPath = CameraAccessPath.NDK_DIRECT,
        discoverySources = buildSet {
            add(CameraDiscoverySource.NDK_DIRECT)
            if (learned) add(CameraDiscoverySource.LEARNED_CACHE)
            else add(CameraDiscoverySource.AUTO_METADATA)
        },
        facing = null,
        displayName = "ID $id",
        focalLengthMm = focal,
        sensorWidthMm = sensorWidth,
        sensorHeightMm = sensorHeight,
        horizontalFovDegrees = fov,
        rawSupported = false,
        rawSizes = emptyList(),
        previewSizes = emptyList(),
        yuvSizes = emptyList(),
        manualSensor = false,
        burstCapture = false,
        maxResolutionSensor = false,
        isLogicalMultiCamera = logical,
        usableForPreview = true,
        qualification = LensQualification(
            accessPathOpenQualified = learned,
            previewSessionQualified = true,
            yuvSessionQualified = false,
            rawSessionQualified = false,
            qualifiedRawSize = null,
            detail = "test",
            checkedAtElapsedRealtimeMs = 0L,
        ),
    )
}
