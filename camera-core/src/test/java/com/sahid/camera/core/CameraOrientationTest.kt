package com.sahid.camera.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun backCameraRelativeRotationFollowsPhysicalDeviceAngle() {
        assertEquals(90, CameraOrientation.sensorToDeviceDegrees(90, false, 0))
        assertEquals(180, CameraOrientation.sensorToDeviceDegrees(90, false, 90))
        assertEquals(270, CameraOrientation.sensorToDeviceDegrees(90, false, 180))
        assertEquals(0, CameraOrientation.sensorToDeviceDegrees(90, false, 270))
    }

    @Test
    fun frontCameraRelativeRotationUsesOppositeDeviceSign() {
        assertEquals(270, CameraOrientation.sensorToDeviceDegrees(270, true, 0))
        assertEquals(180, CameraOrientation.sensorToDeviceDegrees(270, true, 90))
        assertEquals(90, CameraOrientation.sensorToDeviceDegrees(270, true, 180))
        assertEquals(0, CameraOrientation.sensorToDeviceDegrees(270, true, 270))
    }

    @Test
    fun surfacePreviewCompensatesDeviceRotationWithoutSensorDoubleRotation() {
        assertEquals(0, CameraOrientation.surfacePreviewRotationDegrees(false, 0))
        assertEquals(270, CameraOrientation.surfacePreviewRotationDegrees(false, 90))
        assertEquals(180, CameraOrientation.surfacePreviewRotationDegrees(false, 180))
        assertEquals(90, CameraOrientation.surfacePreviewRotationDegrees(false, 270))
    }
}
