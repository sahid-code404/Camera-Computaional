package com.sahid.camera.core

/**
 * Stable, device-independent policy knobs for lens-family discovery.
 *
 * CLASSIFIER_VERSION is deliberately separate from the app version and the ROM fingerprint. Bump
 * it only when family/role semantics change in a way that requires rebuilding metadata candidates.
 * Learned routes remain valid because they are backed by a real frame; only metadata candidates are
 * invalidated and rebuilt.
 */
object LensFamilyPolicy {
    const val CLASSIFIER_VERSION = 2

    /** Fast default that covers the numeric ranges used by the overwhelming majority of HALs. */
    const val BASE_NUMERIC_SCAN_MAX_ID = 255

    /** Safety ceiling: hidden-ID probing stays bounded even on unusual vendor numbering schemes. */
    const val MAX_NUMERIC_SCAN_ID = 1024

    /** Extend beyond the largest already-observed numeric endpoint without scanning huge gaps. */
    const val NUMERIC_SCAN_MARGIN = 64

    fun adaptiveNumericScanMax(knownIds: Iterable<String>): Int {
        val highestKnown = knownIds
            .mapNotNull { it.toIntOrNull() }
            .filter { it >= 0 }
            .maxOrNull()
            ?: return BASE_NUMERIC_SCAN_MAX_ID

        return maxOf(
            BASE_NUMERIC_SCAN_MAX_ID,
            highestKnown + NUMERIC_SCAN_MARGIN,
        ).coerceAtMost(MAX_NUMERIC_SCAN_ID)
    }
}
