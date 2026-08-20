package com.sahid.camera.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Conservative user-facing lens identity filter.
 *
 * Camera services can expose logical aliases, helper devices and multiple API routes that render
 * effectively the same optical view. Camera Lab still records those endpoints in diagnostics, but
 * the normal selector should show one representative per useful optical identity.
 *
 * The filter deliberately requires strong optical evidence. Focal length alone is never enough to
 * hide a camera because two real sensors can share a similar focal length. When enough geometry is
 * unavailable, both IDs remain visible rather than risking the loss of a real lens.
 */
object LensValueFilter {
    fun filterForSelector(routes: List<LensCapability>): List<LensCapability> {
        val oneRoutePerId = routes
            .filter { it.userVisible }
            .groupBy { it.cameraId }
            .values
            .mapNotNull { sameId -> sameId.minByOrNull(::routeScore) }

        // Greedy clustering is intentional: process the most trustworthy route first, then discard
        // only later endpoints that have strong evidence of representing the same optical view.
        val ordered = oneRoutePerId.sortedWith(
            compareBy<LensCapability>(::routeScore)
                .thenBy(::cameraSortKey)
                .thenBy { it.cameraId }
        )
        val kept = mutableListOf<LensCapability>()
        ordered.forEach { candidate ->
            if (kept.none { representative -> opticalEquivalent(candidate, representative) }) {
                kept += candidate
            }
        }

        return kept.sortedWith(
            compareBy<LensCapability>(::cameraSortKey)
                .thenBy { it.cameraId }
        )
    }

    fun opticalEquivalent(left: LensCapability, right: LensCapability): Boolean {
        if (left.cameraId == right.cameraId) return true
        if (knownFacingMismatch(left, right)) return false

        val leftFov = left.horizontalFovDegrees
        val rightFov = right.horizontalFovDegrees
        if (leftFov != null && rightFov != null && leftFov > 0f && rightFov > 0f) {
            val delta = abs(leftFov - rightFov)
            val relative = delta / max(leftFov, rightFov)
            if (delta <= MAX_FOV_DELTA_DEGREES || relative <= MAX_FOV_RELATIVE_DELTA) {
                return sensorGeometryCompatible(left, right)
            }
            return false
        }

        // Geometry fallback: same focal length + same physical sensor dimensions is strong enough
        // to identify aliases even when one vendor omits a directly usable FOV value.
        val leftFocal = left.focalLengthMm
        val rightFocal = right.focalLengthMm
        val leftWidth = left.sensorWidthMm
        val rightWidth = right.sensorWidthMm
        val leftHeight = left.sensorHeightMm
        val rightHeight = right.sensorHeightMm
        if (
            leftFocal != null && rightFocal != null &&
            leftWidth != null && rightWidth != null &&
            leftHeight != null && rightHeight != null &&
            leftFocal > 0f && rightFocal > 0f &&
            leftWidth > 0f && rightWidth > 0f &&
            leftHeight > 0f && rightHeight > 0f
        ) {
            return relativeDelta(leftFocal, rightFocal) <= MAX_FOCAL_RELATIVE_DELTA &&
                relativeDelta(leftWidth, rightWidth) <= MAX_SENSOR_RELATIVE_DELTA &&
                relativeDelta(leftHeight, rightHeight) <= MAX_SENSOR_RELATIVE_DELTA
        }

        return false
    }

    private fun sensorGeometryCompatible(left: LensCapability, right: LensCapability): Boolean {
        val widths = left.sensorWidthMm to right.sensorWidthMm
        val heights = left.sensorHeightMm to right.sensorHeightMm

        val widthCompatible = when {
            widths.first == null || widths.second == null -> true
            widths.first!! <= 0f || widths.second!! <= 0f -> true
            else -> relativeDelta(widths.first!!, widths.second!!) <= MAX_SENSOR_RELATIVE_DELTA
        }
        val heightCompatible = when {
            heights.first == null || heights.second == null -> true
            heights.first!! <= 0f || heights.second!! <= 0f -> true
            else -> relativeDelta(heights.first!!, heights.second!!) <= MAX_SENSOR_RELATIVE_DELTA
        }
        return widthCompatible && heightCompatible
    }

    private fun knownFacingMismatch(left: LensCapability, right: LensCapability): Boolean =
        left.facing != null && right.facing != null && left.facing != right.facing

    private fun relativeDelta(left: Float, right: Float): Float =
        abs(left - right) / max(left, right)

    private fun routeScore(lens: LensCapability): Int {
        var score = 0

        // A route that already produced a frame is the strongest representative.
        if (!lens.learnedFromCache) score += 100

        // Public/direct routes are generally better product-facing identities than hidden helper
        // aliases when the optical result is the same.
        score += when (lens.accessPath) {
            CameraAccessPath.JAVA_DIRECT -> 0
            CameraAccessPath.PHYSICAL_VIA_LOGICAL -> 10
            CameraAccessPath.NDK_DIRECT -> 20
        }
        if (CameraDiscoverySource.HIDDEN_ID_PROBE in lens.discoverySources) score += 25
        if (lens.isLogicalMultiCamera) score += 15
        if (CameraDiscoverySource.CANDIDATE_CACHE in lens.discoverySources) score += 5

        return score
    }

    private fun cameraSortKey(lens: LensCapability): Int =
        lens.cameraId.toIntOrNull() ?: Int.MAX_VALUE

    private const val MAX_FOV_DELTA_DEGREES = 2.0f
    private const val MAX_FOV_RELATIVE_DELTA = 0.035f
    private const val MAX_FOCAL_RELATIVE_DELTA = 0.025f
    private const val MAX_SENSOR_RELATIVE_DELTA = 0.04f
}
