package com.sahid.camera.core

import android.os.SystemClock
import android.util.Size

/**
 * Runtime evidence that a lens can create the sessions Camera intends to expose.
 * Static CameraCharacteristics/NDK metadata is never considered sufficient qualification.
 */
data class LensQualification(
    val accessPathOpenQualified: Boolean,
    val previewSessionQualified: Boolean,
    val yuvSessionQualified: Boolean,
    val rawSessionQualified: Boolean,
    val qualifiedRawSize: Size?,
    val detail: String,
    val checkedAtElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
) {
    companion object {
        fun unqualified(detail: String = "Not runtime-qualified") = LensQualification(
            accessPathOpenQualified = false,
            previewSessionQualified = false,
            yuvSessionQualified = false,
            rawSessionQualified = false,
            qualifiedRawSize = null,
            detail = detail,
        )
    }
}
