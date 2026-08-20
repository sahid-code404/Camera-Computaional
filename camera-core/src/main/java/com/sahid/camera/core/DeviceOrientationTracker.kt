package com.sahid.camera.core

import android.content.Context
import android.view.OrientationEventListener
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tracks physical device rotation independently from Activity orientation.
 *
 * Camera keeps the Activity portrait-locked, so Display#getRotation is intentionally not useful for
 * knowing how the user is physically holding the phone. OrientationEventListener reports clockwise
 * physical rotation; Camera2 orientation formulas use the counter-clockwise Surface convention, so
 * this tracker exposes the converted 0/90/180/270 degree value.
 */
object DeviceOrientationTracker {
    @Volatile
    var surfaceRotationDegrees: Int = 0
        private set

    private val callbacks = CopyOnWriteArraySet<(Int) -> Unit>()
    private var listener: OrientationEventListener? = null
    private var clients = 0

    @Synchronized
    fun register(context: Context, callback: (Int) -> Unit) {
        callbacks += callback
        clients += 1
        if (listener == null) {
            listener = object : OrientationEventListener(context.applicationContext) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    val clockwiseQuarterTurn = ((orientation + 45) / 90 * 90) % 360
                    val surfaceDegrees = (360 - clockwiseQuarterTurn) % 360
                    if (surfaceDegrees == surfaceRotationDegrees) return
                    surfaceRotationDegrees = surfaceDegrees
                    callbacks.forEach { it(surfaceDegrees) }
                }
            }.also { orientationListener ->
                if (orientationListener.canDetectOrientation()) orientationListener.enable()
            }
        }
        callback(surfaceRotationDegrees)
    }

    @Synchronized
    fun unregister(callback: (Int) -> Unit) {
        callbacks -= callback
        clients = (clients - 1).coerceAtLeast(0)
        if (clients == 0) {
            listener?.disable()
            listener = null
        }
    }
}
