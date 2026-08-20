package com.sahid.camera.aurora

import android.view.Surface

/** Dedicated one-shot NDK RAW session with asynchronous capture-result metadata retention. */
object NativeRawCaptureSession {
    init {
        System.loadLibrary("aurora_core")
    }

    fun start(cameraId: String, rawSurface: Surface): NativeSessionStart {
        val values = runCatching { nativeStart(cameraId, rawSurface) }.getOrElse {
            return NativeSessionStart(0L, Int.MIN_VALUE, 0, false)
        }
        return NativeSessionStart(
            handle = values.getOrElse(0) { 0L },
            status = values.getOrElse(1) { Int.MIN_VALUE.toLong() }.toInt(),
            stage = values.getOrElse(2) { 0L }.toInt(),
            cameraOpened = values.getOrElse(3) { 0L } != 0L,
        )
    }

    fun captureMetadataJson(handle: Long): String? =
        if (handle == 0L) null else runCatching { nativeCaptureMetadataJson(handle) }.getOrNull()

    fun stop(handle: Long) {
        if (handle != 0L) nativeStop(handle)
    }

    private external fun nativeStart(cameraId: String, rawSurface: Surface): LongArray
    private external fun nativeCaptureMetadataJson(handle: Long): String?
    private external fun nativeStop(handle: Long)
}
