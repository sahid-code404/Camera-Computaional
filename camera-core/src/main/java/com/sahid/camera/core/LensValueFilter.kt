package com.sahid.camera.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Conservative user-facing lens identity filter.
 *
 * Different camera IDs are not interchangeable just because vendors report the same focal length,
 * sensor size or FOV. Phones commonly contain two real auxiliary modules built from very similar
 * sensors, so learning one route must never make another hidden auxiliary disappear on restart.
 *
 * Cross-ID suppression is therefore limited to evidence that is materially stronger than optical
 * similarity alone:
 *  1. explicit logical-parent -> physical-child topology, or
 *  2. a hidden numeric/NDK endpoint that is an exact optical match for a normal public Java camera.
 *
 * The second rule targets OEM helper aliases such as a hidden mirror of the public front/main
 * camera without collapsing two independent hidden auxiliary IDs. Uncertainty keeps the camera
 * visible.
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

    private fun shouldSuppress(
        candidate: LensCapability,
        representative: LensCapability,
    ): Boolean {
        if (candidate.cameraId == representative.cameraId) return true
        if (knownFacingMismatch(candidate, representative)) return false

        // A logical parent may duplicate the exact view of one of its explicitly-addressable
        // physical children. Because physical children are scored ahead of logical parents below,
        // the parent arrives later and can be safely suppressed only when the optics also match.
        if (
            isLogicalParentOf(candidate, representative) &&
            opticalEquivalent(candidate, representative)
        ) {
            return true
        }

        // Never collapse one hidden auxiliary behind another hidden auxiliary just because their
        // modules report the same focal length/sensor geometry. Macro/depth/mono modules often do.
        // A hidden endpoint is treated as a helper alias only when it exactly mirrors a normal
        // public Java camera.
        if (
            isHiddenDirect(candidate) &&
            isNormalPublicJava(representative) &&
            opticalEquivalent(candidate, representative)
        ) {
            return true
        }

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
                // Similar FOV is only strong evidence when complete physical sensor geometry is
                // also compatible. Missing geometry means unknown, never same.
                return sensorGeometryStronglyCompatible(left, right)
            }
            return false
        }

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

    private fun isLogicalParentOf(parent: LensCapability, child: LensCapability): Boolean =
        parent.isLogicalMultiCamera &&
            parent.physicalCameraId == null &&
            child.physicalCameraId != null &&
            child.logicalCameraId == parent.cameraId

    private fun isHiddenDirect(lens: LensCapability): Boolean =
        lens.physicalCameraId == null &&
            lens.accessPath == CameraAccessPath.NDK_DIRECT &&
            CameraDiscoverySource.HIDDEN_ID_PROBE in lens.discoverySources

    private fun isNormalPublicJava(lens: LensCapability): Boolean =
        lens.physicalCameraId == null &&
            lens.accessPath == CameraAccessPath.JAVA_DIRECT &&
            CameraDiscoverySource.JAVA_DIRECT in lens.discoverySources &&
            CameraDiscoverySource.HIDDEN_ID_PROBE !in lens.discoverySources

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

        // A route that already produced a frame is the strongest representative for the same ID.
        if (!lens.learnedFromCache) score += 100

        score += when (lens.accessPath) {
            CameraAccessPath.JAVA_DIRECT -> 0
            CameraAccessPath.PHYSICAL_VIA_LOGICAL -> 10
            CameraAccessPath.NDK_DIRECT -> 20
        }
        if (CameraDiscoverySource.HIDDEN_ID_PROBE in lens.discoverySources) score += 25
        if (CameraDiscoverySource.CANDIDATE_CACHE in lens.discoverySources) score += 5

        // If a logical parent and one of its physical children have the same view, inspect the
        // physical child first so the later parent can be removed rather than the reverse.
        if (lens.isLogicalMultiCamera && lens.physicalCameraId == null) score += 40

        return score
    }

    private fun cameraSortKey(lens: LensCapability): Int =
        lens.cameraId.toIntOrNull() ?: Int.MAX_VALUE

    private const val MAX_FOV_DELTA_DEGREES = 2.0f
    private const val MAX_FOV_RELATIVE_DELTA = 0.035f
    private const val MAX_FOCAL_RELATIVE_DELTA = 0.025f
    private const val MAX_SENSOR_RELATIVE_DELTA = 0.04f
}
