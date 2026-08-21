package com.sahid.camera.aurora

import android.view.Surface

data class NativeSessionStart(
    val handle: Long,
    val status: Int,
    val stage: Int,
    val cameraOpened: Boolean,
) {
    val started: Boolean
        get() = handle != 0L && status == 0

    val stageLabel: String
        get() = when (stage) {
            1 -> "manager"
            2 -> "open"
            3 -> "window"
            4 -> "output-container"
            5 -> "output"
            6 -> "request"
            7 -> "target"
            8 -> "capture-session"
            9 -> "submit"
            10 -> "running"
            else -> "stage-$stage"
        }
}

/**
 * Genuine NDK-direct Camera2 session path.
 *
 * Unlike the older preflight-only probe, this keeps ACameraManager -> ACameraDevice ->
 * ACameraCaptureSession -> ACaptureRequest native from open through request submission.
 * The supplied Android Surface is converted to ANativeWindow in C++ and used as the
 * actual camera output target.
 */
object NativeCameraSession {
    const val TEMPLATE_PREVIEW = 1
    const val TEMPLATE_STILL_CAPTURE = 2

    init {
        System.loadLibrary("aurora_core")
    }

    fun startPreview(cameraId: String, surface: Surface): NativeSessionStart =
        start(cameraId, surface, TEMPLATE_PREVIEW, repeating = true)

    fun startSingleCapture(cameraId: String, surface: Surface): NativeSessionStart =
        start(cameraId, surface, TEMPLATE_STILL_CAPTURE, repeating = false)

    fun stop(handle: Long) {
        if (handle != 0L) nativeStopSession(handle)
    }

    private fun start(
        cameraId: String,
        surface: Surface,
        template: Int,
        repeating: Boolean,
    ): NativeSessionStart {
        val values = runCatching {
            nativeStartSession(cameraId, surface, template, repeating)
        }.getOrElse {
            return NativeSessionStart(0L, Int.MIN_VALUE, 0, false)
        }
        return NativeSessionStart(
            handle = values.getOrElse(0) { 0L },
            status = values.getOrElse(1) { Int.MIN_VALUE.toLong() }.toInt(),
            stage = values.getOrElse(2) { 0L }.toInt(),
            cameraOpened = values.getOrElse(3) { 0L } != 0L,
        )
    }

    private external fun nativeStartSession(
        cameraId: String,
        surface: Surface,
        template: Int,
        repeating: Boolean,
    ): LongArray

    private external fun nativeStopSession(handle: Long)
}
