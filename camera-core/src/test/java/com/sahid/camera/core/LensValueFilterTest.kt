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
    fun directAndPhysicalViaLogicalProfilesBecomeOneTargetFamily() {
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
    fun directLogicalParentIsNotMistakenForItsPhysicalChild() {
        val child = lens(
            id = "20",
            learned = true,
            focal = 1.9f,
            sensorWidth = 4.0f,
            sensorHeight = 3.0f,
            fov = 92.0f,
            hidden = true,
        )
        val directLogical = lens(
            id = "61",
            learned = true,
            focal = 4.7f,
            sensorWidth = 5.6f,
            sensorHeight = 4.2f,
            fov = 61.5f,
            hidden = true,
            logical = true,
            physicalIds = setOf("20"),
        )

        val families = LensFamilyResolver.resolve(listOf(directLogical, child))

        assertEquals(2, families.size)
        assertTrue(families.any { it.defaultRoute.cameraId == "20" })
        assertTrue(families.any { it.defaultRoute.cameraId == "61" })
    }

    @Test
    fun oneAliasProfileDoesNotReduceFiveTargetLensesToFour() {
        val routes = listOf(
            lens("0", true, 4.7f, 5.6f, 4.2f, 61.5f, accessPath = CameraAccessPath.JAVA_DIRECT),
            lens("1", true, 3.7f, 4.8f, 3.6f, 66.0f, accessPath = CameraAccessPath.JAVA_DIRECT),
            lens("20", true, 1.9f, 4.0f, 3.0f, 92.0f, hidden = true),
            lens("21", true, 1.6f, 4.0f, 3.0f, 102.0f, hidden = true),
            lens("22", true, 1.9f, 4.4f, 3.3f, 98.0f, hidden = true),
            lens(
                id = "20",
                learned = false,
                focal = 1.9f,
                sensorWidth = 4.0f,
                sensorHeight = 3.0f,
                fov = 92.0f,
                accessPath = CameraAccessPath.PHYSICAL_VIA_LOGICAL,
                logicalCameraId = "61",
                physicalCameraId = "20",
            ),
        )

        val families = LensFamilyResolver.resolve(routes)

        assertEquals(5, families.size)
        assertEquals(listOf("0", "1", "20", "21", "22"), families.map { it.defaultRoute.cameraId })
        assertEquals(1, families.first { it.familyId == "20" }.aliases.size)
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
