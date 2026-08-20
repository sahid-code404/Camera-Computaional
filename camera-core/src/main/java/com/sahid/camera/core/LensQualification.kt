package com.sahid.camera.core

import android.os.SystemClock
import android.util.Size

/**
 * Runtime evidence that a lens can create the sessions Camera intends to expose.
 * Static CameraCharacteristics metadata is not considered sufficient qualification.
 */
data class LensQualification(
    val previewSessionQualified: Boolean,
    val rawSessionQualified: Boolean,
    val qualifiedRawSize: Size?,
    val detail: String,
    val checkedAtElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
) {
    companion object {
        fun unqualified(detail: String = "Not runtime-qualified") = LensQualification(
            previewSessionQualified = false,
            rawSessionQualified = false,
            qualifiedRawSize = null,
            detail = detail,
        )
    }
}
