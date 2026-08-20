package com.sahid.camera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensValueFilterTest {
    @Test
    fun keepsTwoHiddenAuxiliariesSeparateEvenWhenMetadataMatches() {
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
            learned = false,
            focal = 1.9f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 92.0f,
            hidden = true,
        )

        val families = LensFamilyResolver.resolve(listOf(first, second))

        assertEquals(2, families.size)
        assertEquals(listOf("20", "22"), families.map { it.defaultRoute.cameraId })
    }

    @Test
    fun directAndPhysicalViaLogicalProfilesBecomeOneFamily() {
        val direct = lens(
            id = "20",
            learned = true,
            focal = 1.9f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 92.0f,
            hidden = true,
        )
        val viaLogical = lens(
            id = "20",
            learned = false,
            focal = 1.9f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 92.0f,
            accessPath = CameraAccessPath.PHYSICAL_VIA_LOGICAL,
            logicalCameraId = "61",
            physicalCameraId = "20",
        )

        val families = LensFamilyResolver.resolve(listOf(direct, viaLogical))

        assertEquals(1, families.size)
        assertEquals("20", families.single().familyId)
        assertEquals(direct.stableId, families.single().defaultRoute.stableId)
        assertEquals(listOf(viaLogical.stableId), families.single().aliases.map { it.stableId })
    }

    @Test
    fun singleChildLogicalParentBecomesAliasOfPhysicalChild() {
        val child = lens(
            id = "20",
            learned = true,
            focal = 1.9f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 92.0f,
            hidden = true,
        )
        val parent = lens(
            id = "61",
            learned = true,
            focal = 1.9f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 92.0f,
            hidden = true,
            logical = true,
            physicalIds = setOf("20"),
        )

        val families = LensFamilyResolver.resolve(listOf(parent, child))

        assertEquals(1, families.size)
        assertEquals("20", families.single().defaultRoute.cameraId)
        assertTrue(families.single().aliases.any { it.cameraId == "61" })
    }

    @Test
    fun multiChildLogicalParentMergesOnlyWithUniqueOpticalChild() {
        val parent = lens(
            id = "60",
            learned = false,
            focal = 4.7f,
            sensorWidth = 5.6f,
            sensorHeight = 4.2f,
            fov = 61.5f,
            hidden = true,
            logical = true,
            physicalIds = setOf("20", "21"),
        )
        val matchingChild = lens(
            id = "20",
            learned = true,
            focal = 4.7f,
            sensorWidth = 5.6f,
            sensorHeight = 4.2f,
            fov = 61.5f,
            hidden = true,
        )
        val differentChild = lens(
            id = "21",
            learned = true,
            focal = 1.6f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 102.0f,
            hidden = true,
        )

        val families = LensFamilyResolver.resolve(listOf(parent, matchingChild, differentChild))

        assertEquals(2, families.size)
        val mainFamily = families.first { it.defaultRoute.cameraId == "20" }
        assertTrue(mainFamily.aliases.any { it.cameraId == "60" })
        assertTrue(families.any { it.defaultRoute.cameraId == "21" })
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
        physicalIds: Set<String> = emptySet(),
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
        logicalPhysicalIds = physicalIds,
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
