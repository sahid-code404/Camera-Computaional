package com.sahid.camera.core

import android.media.ExifInterface
import android.view.Surface

/** Shared Camera2 orientation math. */
object CameraOrientation {
    fun displayDegrees(displayRotation: Int): Int = when (displayRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /**
     * Rotation used by Android's Camera2 preview transform. Camera SurfaceTexture output is already
     * expressed in the device's native orientation, so this compensates only the display rotation.
     */
    fun surfacePreviewRotationDegrees(isFrontFacing: Boolean, displayRotation: Int): Int {
        val deviceDegrees = displayDegrees(displayRotation)
        return if (isFrontFacing) {
            (360 + deviceDegrees) % 360
        } else {
            (360 - deviceDegrees) % 360
        }
    }

    /**
     * Rotation from RAW sensor coordinates to the current device/display orientation. This follows
     * the Camera2 reference sample's sensorToDeviceRotation formula.
     */
    fun sensorToDeviceDegrees(
        sensorOrientation: Int,
        isFrontFacing: Boolean,
        displayRotation: Int,
    ): Int {
        var deviceDegrees = displayDegrees(displayRotation)
        if (isFrontFacing) deviceDegrees = -deviceDegrees
        return (sensorOrientation + deviceDegrees + 360) % 360
    }

    fun exifOrientationForDegrees(degrees: Int): Int = when ((degrees % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}
