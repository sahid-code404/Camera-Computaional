package com.sahid.camera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensValueFilterTest {
    @Test
    fun keepsTwoLearnedHiddenAuxiliariesEvenWhenMetadataMatches() {
        val first = lens(
            id = "20",
            learned = true,
            focal = 1.9f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 92.0f,
            hidden = true,
        )
        val second = lens(
            id = "22",
            learned = true,
            focal = 1.9f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 92.0f,
            hidden = true,
        )

        val result = LensValueFilter.filterForSelector(listOf(first, second))

        assertEquals(listOf("20", "22"), result.map { it.cameraId })
    }

    @Test
    fun learningOneHiddenAuxiliaryDoesNotEraseMatchingUnprovenAuxiliary() {
        val learned = lens(
            id = "20",
            learned = true,
            focal = 1.9f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 92.0f,
            hidden = true,
        )
        val candidate = lens(
            id = "22",
            learned = false,
            focal = 1.9f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 92.0f,
            hidden = true,
        )

        val result = LensValueFilter.filterForSelector(listOf(learned, candidate))

        assertEquals(listOf("20", "22"), result.map { it.cameraId })
    }

    @Test
    fun missingSensorGeometryNeverCollapsesHiddenCameraBehindPublicCamera() {
        val public = lens(
            id = "1",
            learned = true,
            focal = 3.7f,
            sensorWidth = null,
            sensorHeight = null,
            fov = 74.0f,
            accessPath = CameraAccessPath.JAVA_DIRECT,
            javaPublic = true,
        )
        val hidden = lens(
            id = "101",
            learned = false,
            focal = 3.7f,
            sensorWidth = null,
            sensorHeight = null,
            fov = 74.0f,
            hidden = true,
        )

        val result = LensValueFilter.filterForSelector(listOf(public, hidden))

        assertEquals(listOf("1", "101"), result.map { it.cameraId })
    }

    @Test
    fun exactHiddenMirrorOfNormalPublicJavaCameraIsSuppressed() {
        val public = lens(
            id = "1",
            learned = true,
            focal = 3.7f,
            sensorWidth = 5.0f,
            sensorHeight = 3.8f,
            fov = 68.0f,
            accessPath = CameraAccessPath.JAVA_DIRECT,
            javaPublic = true,
        )
        val helper = lens(
            id = "101",
            learned = false,
            focal = 3.7f,
            sensorWidth = 5.0f,
            sensorHeight = 3.8f,
            fov = 68.0f,
            hidden = true,
        )

        val result = LensValueFilter.filterForSelector(listOf(public, helper))

        assertEquals(listOf("1"), result.map { it.cameraId })
    }

    @Test
    fun explicitLogicalParentCanBeSuppressedByEquivalentPhysicalChild() {
        val logicalParent = lens(
            id = "0",
            learned = true,
            focal = 4.7f,
            sensorWidth = 5.6f,
            sensorHeight = 4.2f,
            fov = 61.5f,
            accessPath = CameraAccessPath.JAVA_DIRECT,
            javaPublic = true,
            logical = true,
        )
        val physicalChild = lens(
            id = "20",
            learned = true,
            focal = 4.7f,
            sensorWidth = 5.6f,
            sensorHeight = 4.2f,
            fov = 61.5f,
            accessPath = CameraAccessPath.PHYSICAL_VIA_LOGICAL,
            logical = true,
            logicalCameraId = "0",
            physicalCameraId = "20",
        )

        val result = LensValueFilter.filterForSelector(listOf(logicalParent, physicalChild))

        assertEquals(listOf("20"), result.map { it.cameraId })
        assertTrue(result.none { it.cameraId == "0" })
    }

    private fun lens(
        id: String,
        learned: Boolean,
        focal: Float,
        sensorWidth: Float?,
        sensorHeight: Float?,
        fov: Float?,
        accessPath: CameraAccessPath = CameraAccessPath.NDK_DIRECT,
        hidden: Boolean = false,
        javaPublic: Boolean = false,
        logical: Boolean = false,
        logicalCameraId: String = id,
        physicalCameraId: String? = null,
    ): LensCapability = LensCapability(
        cameraId = id,
        logicalCameraId = logicalCameraId,
        physicalCameraId = physicalCameraId,
        accessPath = accessPath,
        discoverySources = buildSet {
            when (accessPath) {
                CameraAccessPath.JAVA_DIRECT -> add(CameraDiscoverySource.JAVA_DIRECT)
                CameraAccessPath.NDK_DIRECT -> add(CameraDiscoverySource.NDK_DIRECT)
                CameraAccessPath.PHYSICAL_VIA_LOGICAL -> add(CameraDiscoverySource.LOGICAL_PHYSICAL)
            }
            if (javaPublic) add(CameraDiscoverySource.JAVA_DIRECT)
            if (hidden) add(CameraDiscoverySource.HIDDEN_ID_PROBE)
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
