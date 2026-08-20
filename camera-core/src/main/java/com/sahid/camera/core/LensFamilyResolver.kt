package com.sahid.camera.core

import android.util.Size
import kotlin.math.abs
import kotlin.math.max

/**
 * MotionCam-style lens-family resolver.
 *
 * A camera ID is the target lens identity. The same target can have multiple access profiles:
 * JAVA_DIRECT, NDK_DIRECT, or PHYSICAL_VIA_LOGICAL (for example target 20 through logical parent
 * 61 is the profile "61/20"). Those profiles belong to the same family because their cameraId is
 * the same target sensor.
 *
 * A logical parent ID is NOT automatically the same family as one of its physical children. Direct
 * logical output can represent a different/composite/default view. Cross-ID family merging is only
 * allowed for a very strong public-Java <-> alternate-NDK mirror signature. Hidden auxiliaries are
 * never merged with each other from optics alone.
 */
data class LensFamily(
    val familyId: String,
    val defaultRoute: LensCapability,
    val aliases: List<LensCapability>,
) {
    val routes: List<LensCapability>
        get() = listOf(defaultRoute) + aliases
}

object LensFamilyResolver {
    fun resolve(routes: List<LensCapability>): List<LensFamily> {
        val usable = routes.filter { it.userVisible }
        if (usable.isEmpty()) return emptyList()

        val ids = usable.map { it.cameraId }.distinct()
        val parent = ids.associateWith { it }.toMutableMap()

        fun find(id: String): String {
            val current = parent[id] ?: id.also { parent[it] = it }
            if (current == id) return id
            val root = find(current)
            parent[id] = root
            return root
        }

        fun union(left: String, right: String) {
            val leftRoot = find(left)
            val rightRoot = find(right)
            if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
        }

        // Same target cameraId is already one family because grouping happens on find(cameraId).
        // A PHYSICAL_VIA_LOGICAL route keeps cameraId=physical target and logicalCameraId=parent,
        // exactly matching MotionCam's "parent/physical" profile model.

        // OEMs can also publish a normal Java endpoint and expose an alternate NDK endpoint that is
        // effectively a mirror of the same sensor. Merge those different IDs only with a very strong
        // hardware + stream signature. NDK<->NDK is intentionally excluded because two genuine aux
        // modules can use identical sensor models and optics.
        val publicJava = usable.filter(::isNormalPublicJava)
        val alternateNdk = usable.filter(::isAlternateNdkDirect)
        alternateNdk.forEach { alternate ->
            publicJava.forEach { public ->
                if (strongMirrorEquivalent(alternate, public)) {
                    union(alternate.cameraId, public.cameraId)
                }
            }
        }

        val groups = usable.groupBy { find(it.cameraId) }
        return groups.values
            .map(::buildFamily)
            .sortedWith(
                compareBy<LensFamily> { cameraSortKey(it.defaultRoute) }
                    .thenBy { it.familyId }
            )
    }

    fun defaultsForSelector(routes: List<LensCapability>): List<LensCapability> =
        resolve(routes).map { family ->
            family.defaultRoute.copy(displayName = "ID ${family.defaultRoute.cameraId}")
        }

    /** Conservative optical comparison. Missing physical geometry means unknown, never equal. */
    fun opticalEquivalent(left: LensCapability, right: LensCapability): Boolean {
        if (left.cameraId == right.cameraId) return true
        if (knownFacingMismatch(left, right)) return false

        val leftFov = left.horizontalFovDegrees
        val rightFov = right.horizontalFovDegrees
        if (leftFov != null && rightFov != null && leftFov > 0f && rightFov > 0f) {
            val delta = abs(leftFov - rightFov)
            val relative = delta / max(leftFov, rightFov)
            if (delta <= MAX_FOV_DELTA_DEGREES || relative <= MAX_FOV_RELATIVE_DELTA) {
                return sensorGeometryCompatible(left, right, MAX_SENSOR_RELATIVE_DELTA)
            }
            return false
        }

        val leftFocal = left.focalLengthMm
        val rightFocal = right.focalLengthMm
        if (leftFocal == null || rightFocal == null || leftFocal <= 0f || rightFocal <= 0f) return false
        if (relativeDelta(leftFocal, rightFocal) > MAX_FOCAL_RELATIVE_DELTA) return false
        return sensorGeometryCompatible(left, right, MAX_SENSOR_RELATIVE_DELTA)
    }

    private fun buildFamily(routes: List<LensCapability>): LensFamily {
        val canonicalId = canonicalCameraId(routes)
        val canonicalRoutes = routes.filter { it.cameraId == canonicalId }.ifEmpty { routes }
        val default = canonicalRoutes.minByOrNull(::routeScore) ?: routes.first()
        val aliases = routes
            .filterNot { it.stableId == default.stableId }
            .sortedBy(::routeScore)
        return LensFamily(
            familyId = canonicalId,
            defaultRoute = default,
            aliases = aliases,
        )
    }

    private fun canonicalCameraId(routes: List<LensCapability>): String {
        val routeIds = routes.mapTo(linkedSetOf()) { it.cameraId }

        // A public<->NDK mirror family keeps the normal public Java ID as the stable user-facing
        // identity. Alternate native routes remain internal profiles/fallbacks.
        val publicIds = routes.filter(::isNormalPublicJava).map { it.cameraId }.distinct()
        if (publicIds.size == 1 && routeIds.size > 1) return publicIds.single()

        return routes.minByOrNull(::routeScore)?.cameraId ?: routeIds.first()
    }

    private fun routeScore(lens: LensCapability): Int {
        var score = 0
        if (!lens.learnedFromCache) score += 30

        score += when (lens.accessPath) {
            CameraAccessPath.JAVA_DIRECT -> 0
            CameraAccessPath.NDK_DIRECT -> 5
            CameraAccessPath.PHYSICAL_VIA_LOGICAL -> 20
        }

        // For the same target sensor, direct access is the default and parent/physical routing is a
        // retained profile. A learned alternate can still win if the direct route has never worked.
        if (lens.physicalCameraId != null) score += 15
        if (CameraDiscoverySource.HIDDEN_ID_PROBE in lens.discoverySources) score += 8
        if (CameraDiscoverySource.CANDIDATE_CACHE in lens.discoverySources) score += 3
        return score
    }

    private fun strongMirrorEquivalent(left: LensCapability, right: LensCapability): Boolean {
        if (knownFacingMismatch(left, right)) return false

        val leftFocal = left.focalLengthMm ?: return false
        val rightFocal = right.focalLengthMm ?: return false
        if (leftFocal <= 0f || rightFocal <= 0f) return false
        if (relativeDelta(leftFocal, rightFocal) > MIRROR_FOCAL_RELATIVE_DELTA) return false
        if (!sensorGeometryCompatible(left, right, MIRROR_SENSOR_RELATIVE_DELTA)) return false

        val leftFov = left.horizontalFovDegrees
        val rightFov = right.horizontalFovDegrees
        if (leftFov != null && rightFov != null && abs(leftFov - rightFov) > MIRROR_FOV_DELTA_DEGREES) {
            return false
        }

        return streamSignatureMatches(left, right)
    }

    private fun streamSignatureMatches(left: LensCapability, right: LensCapability): Boolean {
        var comparable = 0
        var matches = 0

        fun compareLargest(leftSizes: List<Size>, rightSizes: List<Size>) {
            val leftLargest = leftSizes.maxByOrNull(::area) ?: return
            val rightLargest = rightSizes.maxByOrNull(::area) ?: return
            comparable += 1
            if (sameSize(leftLargest, rightLargest)) matches += 1
        }

        compareLargest(left.previewSizes, right.previewSizes)
        compareLargest(left.yuvSizes, right.yuvSizes)
        compareLargest(left.rawSizes, right.rawSizes)

        // Two matching stream classes are enough to call this the same sensor profile. This allows
        // PRIVATE preview constraints to differ between Java/NDK wrappers while YUV/RAW still prove
        // the underlying hardware identity.
        return comparable >= 2 && matches >= 2
    }

    private fun sensorGeometryCompatible(
        left: LensCapability,
        right: LensCapability,
        tolerance: Float,
    ): Boolean {
        val leftWidth = left.sensorWidthMm ?: return false
        val rightWidth = right.sensorWidthMm ?: return false
        val leftHeight = left.sensorHeightMm ?: return false
        val rightHeight = right.sensorHeightMm ?: return false
        if (leftWidth <= 0f || rightWidth <= 0f || leftHeight <= 0f || rightHeight <= 0f) return false
        return relativeDelta(leftWidth, rightWidth) <= tolerance &&
            relativeDelta(leftHeight, rightHeight) <= tolerance
    }

    private fun isAlternateNdkDirect(lens: LensCapability): Boolean =
        lens.physicalCameraId == null &&
            lens.accessPath == CameraAccessPath.NDK_DIRECT &&
            CameraDiscoverySource.JAVA_DIRECT !in lens.discoverySources

    private fun isNormalPublicJava(lens: LensCapability): Boolean =
        lens.physicalCameraId == null &&
            lens.accessPath == CameraAccessPath.JAVA_DIRECT &&
            CameraDiscoverySource.JAVA_DIRECT in lens.discoverySources &&
            CameraDiscoverySource.HIDDEN_ID_PROBE !in lens.discoverySources

    private fun knownFacingMismatch(left: LensCapability, right: LensCapability): Boolean =
        left.facing != null && right.facing != null && left.facing != right.facing

    private fun relativeDelta(left: Float, right: Float): Float =
        abs(left - right) / max(left, right)

    private fun sameSize(left: Size, right: Size): Boolean =
        left.width == right.width && left.height == right.height

    private fun area(size: Size): Long = size.width.toLong() * size.height.toLong()

    private fun cameraSortKey(lens: LensCapability): Int =
        lens.cameraId.toIntOrNull() ?: Int.MAX_VALUE

    private const val MAX_FOV_DELTA_DEGREES = 2.0f
    private const val MAX_FOV_RELATIVE_DELTA = 0.035f
    private const val MAX_FOCAL_RELATIVE_DELTA = 0.025f
    private const val MAX_SENSOR_RELATIVE_DELTA = 0.04f

    private const val MIRROR_FOV_DELTA_DEGREES = 1.0f
    private const val MIRROR_FOCAL_RELATIVE_DELTA = 0.01f
    private const val MIRROR_SENSOR_RELATIVE_DELTA = 0.015f
}
