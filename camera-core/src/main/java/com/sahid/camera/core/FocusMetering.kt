package com.sahid.camera.core

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.view.TextureView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A normalized point in the physical sensor's active-array coordinate system.
 *
 * Keeping the point normalized lets preview focus and the later dedicated RAW session use the
 * exact same user-selected subject even though the preview CameraDevice is closed before capture.
 */
data class FocusMeteringPoint(
    val x: Float,
    val y: Float,
) {
    fun clamped(): FocusMeteringPoint = FocusMeteringPoint(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
    )
}

/** Camera2 metering helpers shared by live preview and RAW capture. */
object FocusMetering {
    fun fromViewTap(
        view: TextureView,
        characteristics: CameraCharacteristics,
        isFrontFacing: Boolean,
        surfaceRotationDegrees: Int,
        viewX: Float,
        viewY: Float,
    ): FocusMeteringPoint {
        val displayX = (viewX / max(1, view.width).toFloat()).coerceIn(0f, 1f)
        val displayY = (viewY / max(1, view.height).toFloat()).coerceIn(0f, 1f)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val clockwiseDegrees = CameraOrientation.sensorToDeviceDegrees(
            sensorOrientation = sensorOrientation,
            isFrontFacing = isFrontFacing,
            surfaceRotationDegrees = surfaceRotationDegrees,
        )

        // Preview is intentionally not mirrored. This is the inverse of the display rotation so the
        // tap remains attached to the same physical subject in the sensor active array.
        return when ((clockwiseDegrees % 360 + 360) % 360) {
            90 -> FocusMeteringPoint(displayY, 1f - displayX)
            180 -> FocusMeteringPoint(1f - displayX, 1f - displayY)
            270 -> FocusMeteringPoint(1f - displayY, displayX)
            else -> FocusMeteringPoint(displayX, displayY)
        }.clamped()
    }

    fun applyRegions(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        physicalCameraId: String?,
        point: FocusMeteringPoint?,
    ) {
        if (point == null) return
        val region = meteringRegion(characteristics, point)
        val regions = arrayOf(region)

        if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
            set(builder, CaptureRequest.CONTROL_AF_REGIONS, regions, physicalCameraId)
        }
        if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
            set(builder, CaptureRequest.CONTROL_AE_REGIONS, regions, physicalCameraId)
        }
        if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0) > 0) {
            set(builder, CaptureRequest.CONTROL_AWB_REGIONS, regions, physicalCameraId)
        }
    }

    fun meteringRegion(
        characteristics: CameraCharacteristics,
        point: FocusMeteringPoint,
        boxFraction: Float = 0.14f,
    ): MeteringRectangle {
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, 1, 1)
        val safe = point.clamped()
        val boxWidth = max(1, (active.width() * boxFraction.coerceIn(0.04f, 0.35f)).roundToInt())
        val boxHeight = max(1, (active.height() * boxFraction.coerceIn(0.04f, 0.35f)).roundToInt())
        val centerX = active.left + (safe.x * active.width()).roundToInt()
        val centerY = active.top + (safe.y * active.height()).roundToInt()
        val left = (centerX - boxWidth / 2).coerceIn(active.left, max(active.left, active.right - boxWidth))
        val top = (centerY - boxHeight / 2).coerceIn(active.top, max(active.top, active.bottom - boxHeight))
        val right = min(active.right, left + boxWidth)
        val bottom = min(active.bottom, top + boxHeight)
        return MeteringRectangle(
            Rect(left, top, max(left + 1, right), max(top + 1, bottom)),
            MeteringRectangle.METERING_WEIGHT_MAX,
        )
    }

    fun supportsAutofocus(characteristics: CameraCharacteristics): Boolean {
        val minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        if (minimumFocusDistance != null && minimumFocusDistance <= 0f) return false
        return characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.any { it != CaptureRequest.CONTROL_AF_MODE_OFF } == true
    }

    fun lockAfMode(characteristics: CameraCharacteristics): Int {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        return when {
            CaptureRequest.CONTROL_AF_MODE_AUTO in modes -> CaptureRequest.CONTROL_AF_MODE_AUTO
            CaptureRequest.CONTROL_AF_MODE_MACRO in modes -> CaptureRequest.CONTROL_AF_MODE_MACRO
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
    }

    fun previewAfMode(characteristics: CameraCharacteristics): Int {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        return when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CaptureRequest.CONTROL_AF_MODE_AUTO in modes -> CaptureRequest.CONTROL_AF_MODE_AUTO
            CaptureRequest.CONTROL_AF_MODE_MACRO in modes -> CaptureRequest.CONTROL_AF_MODE_MACRO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
    }

    fun <T> set(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<T>,
        value: T,
        physicalCameraId: String?,
    ) {
        runCatching { builder.set(key, value) }
        if (physicalCameraId != null) {
            runCatching { builder.setPhysicalCameraKey(key, value, physicalCameraId) }
        }
    }
}
