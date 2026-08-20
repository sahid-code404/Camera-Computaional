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
 * Safety rule: uncertainty keeps a camera visible. Missing geometry, merely similar focal lengths,
 * or merely similar FOV values are never enough to hide a potentially real physical lens.
 */
object LensValueFilter {
    fun filterForSelector(routes: List<LensCapability>): List<LensCapability> {
        val oneRoutePerId = routes
            .filter { it.userVisible }
            .groupBy { it.cameraId }
            .values
            .mapNotNull { sameId -> sameId.minByOrNull(::routeScore) }

        val ordered = oneRoutePerId.sortedWith(
            compareBy<LensCapability>(::routeScore)
                .thenBy(::cameraSortKey)
                .thenBy { it.cameraId }
        )

        val kept = mutableListOf<LensCapability>()
        ordered.forEach { candidate ->
            val duplicate = kept.any { representative ->
                shouldSuppress(candidate, representative)
            }
            if (!duplicate) kept += candidate
        }

        return kept.sortedWith(
            compareBy<LensCapability>(::cameraSortKey)
                .thenBy { it.cameraId }
        )
    }

    /**
     * Different frame-proven non-logical IDs are treated as separate real endpoints even when
     * vendors report very similar optical metadata. A frame-proven logical parent may still be
     * suppressed when a proven/non-logical child represents the same optical view.
     */
    private fun shouldSuppress(
        candidate: LensCapability,
        representative: LensCapability,
    ): Boolean {
        if (candidate.cameraId == representative.cameraId) return true
        if (knownFacingMismatch(candidate, representative)) return false

        val candidateProven = candidate.learnedFromCache
        val representativeProven = representative.learnedFromCache

        if (
            candidateProven && representativeProven &&
            !candidate.isLogicalMultiCamera && !representative.isLogicalMultiCamera
        ) {
            return false
        }

        if (!opticalEquivalent(candidate, representative)) return false

        // Explicit logical/helper structure is strong evidence that one route is an alias.
        if (candidate.isLogicalMultiCamera && !representative.isLogicalMultiCamera) return true
        if (representative.isLogicalMultiCamera && !candidate.isLogicalMultiCamera) return false

        // An unproven metadata candidate may be hidden behind a frame-proven equivalent. The
        // inverse is never allowed: real-frame evidence always survives optimistic metadata.
        if (!candidateProven && representativeProven) return true
        if (candidateProven && !representativeProven) return false

        // Two merely metadata-discovered non-logical IDs stay visible. We do not know yet whether
        // they are two physical sensors with similar optics or an OEM alias.
        return false
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
                // Similar FOV is only strong evidence when the physical sensor geometry is also
                // completely present and compatible. Missing geometry means 'unknown', not 'same'.
                return sensorGeometryStronglyCompatible(left, right)
            }
            return false
        }

        // Geometry fallback: same focal length + same complete physical sensor dimensions is
        // strong enough to identify likely aliases when FOV cannot be derived.
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

    private fun sensorGeometryStronglyCompatible(
        left: LensCapability,
        right: LensCapability,
    ): Boolean {
        val leftWidth = left.sensorWidthMm ?: return false
        val rightWidth = right.sensorWidthMm ?: return false
        val leftHeight = left.sensorHeightMm ?: return false
        val rightHeight = right.sensorHeightMm ?: return false
        if (leftWidth <= 0f || rightWidth <= 0f || leftHeight <= 0f || rightHeight <= 0f) return false

        return relativeDelta(leftWidth, rightWidth) <= MAX_SENSOR_RELATIVE_DELTA &&
            relativeDelta(leftHeight, rightHeight) <= MAX_SENSOR_RELATIVE_DELTA
    }

    private fun knownFacingMismatch(left: LensCapability, right: LensCapability): Boolean =
        left.facing != null && right.facing != null && left.facing != right.facing

    private fun relativeDelta(left: Float, right: Float): Float =
        abs(left - right) / max(left, right)

    private fun routeScore(lens: LensCapability): Int {
        var score = 0

        // A route that already produced a frame is the strongest representative.
        if (!lens.learnedFromCache) score += 100

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
