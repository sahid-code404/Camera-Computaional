package com.sahid.camera.core

import android.media.ExifInterface
import android.view.Surface

/** Shared Camera2 orientation math. */
object CameraOrientation {
    fun surfaceRotationConstantToDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /**
     * TextureView already accounts for sensor orientation. This is the remaining device-rotation
     * compensation used by the Camera2 reference preview transform.
     */
    fun surfacePreviewRotationDegrees(
        isFrontFacing: Boolean,
        surfaceRotationDegrees: Int,
    ): Int = if (isFrontFacing) {
        (360 + surfaceRotationDegrees) % 360
    } else {
        (360 - surfaceRotationDegrees) % 360
    }

    /**
     * Camera2 relative-rotation formula from Android's camera orientation guidance.
     * surfaceRotationDegrees uses the counter-clockwise Surface/Display convention.
     */
    fun sensorToDeviceDegrees(
        sensorOrientation: Int,
        isFrontFacing: Boolean,
        surfaceRotationDegrees: Int,
    ): Int {
        val sign = if (isFrontFacing) 1 else -1
        return (sensorOrientation - surfaceRotationDegrees * sign + 360) % 360
    }

    fun exifOrientationForDegrees(degrees: Int): Int = when ((degrees % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}
