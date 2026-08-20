package com.sahid.camera.core

import android.util.Size
import kotlin.math.abs
import kotlin.math.max

/**
 * MotionCam-style lens-family resolver.
 *
 * Camera IDs are transport endpoints, not necessarily physical lenses. One sensor can be reachable
 * through several endpoint/profile combinations (for example direct NDK plus physical-via-logical),
 * while two genuinely different sensors can report almost identical focal/sensor metadata.
 *
 * This resolver therefore keeps every endpoint/profile internally and exposes one default route per
 * family. Cross-ID grouping requires explicit topology or a very strong public<->hidden mirror
 * signature; hidden auxiliaries are never merged with each other from optics alone.
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

        val byId = usable.groupBy { it.cameraId }

        // Explicit logical topology is the strongest family evidence. A logical parent with one
        // exposed child is an alias/profile of that child. With several children we merge the
        // parent only when exactly one child matches the parent's optical identity.
        usable
            .filter { it.physicalCameraId == null && it.logicalPhysicalIds.isNotEmpty() }
            .forEach { logical ->
                val children = logical.logicalPhysicalIds.filter { it in byId }
                when {
                    children.size == 1 -> union(logical.cameraId, children.single())
                    children.size > 1 -> {
                        val matchingChildren = children.filter { childId ->
                            byId[childId].orEmpty().any { child -> opticalEquivalent(logical, child) }
                        }
                        if (matchingChildren.size == 1) {
                            union(logical.cameraId, matchingChildren.single())
                        }
                    }
                }
            }

        // A PHYSICAL_VIA_LOGICAL route explicitly says "open logical X, render physical Y". If a
        // direct route for X is also present and represents the same view, X is a parent alias of Y.
        usable
            .filter { it.physicalCameraId != null && it.logicalCameraId != it.cameraId }
            .forEach { physicalRoute ->
                byId[physicalRoute.logicalCameraId].orEmpty().forEach { logicalRoute ->
                    val explicitChild = physicalRoute.cameraId in logicalRoute.logicalPhysicalIds
                    if (explicitChild || opticalEquivalent(logicalRoute, physicalRoute)) {
                        union(logicalRoute.cameraId, physicalRoute.cameraId)
                    }
                }
            }

        // OEMs sometimes publish a normal Java endpoint and also expose a hidden NDK mirror of the
        // same sensor. Treat it as the same family only when the hardware/stream signature is very
        // strong. Hidden<->hidden routes are intentionally excluded: two real aux modules may use
        // the exact same sensor model and optics.
        val publicJava = usable.filter(::isNormalPublicJava)
        val hiddenDirect = usable.filter(::isHiddenDirect)
        hiddenDirect.forEach { hidden ->
            publicJava.forEach { public ->
                if (strongMirrorEquivalent(hidden, public)) {
                    union(hidden.cameraId, public.cameraId)
                }
            }
        }

        val groups = usable.groupBy { find(it.cameraId) }
        return groups.values
            .map { familyRoutes -> buildFamily(familyRoutes) }
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
        val canonicalRoutes = routes.filter { it.cameraId == canonicalId }
            .ifEmpty { routes }
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

        // If one ID is explicitly named as a physical child by another route in this family, the
        // child is the lens identity; the logical parent is only another profile.
        val explicitChildren = routes
            .flatMap { it.logicalPhysicalIds }
            .filter { it in routeIds }
            .toSet()
        if (explicitChildren.size == 1) return explicitChildren.single()

        routes.firstOrNull { route ->
            route.physicalCameraId != null && route.physicalCameraId == route.cameraId
        }?.let { return it.cameraId }

        // For a public<->hidden mirror family, the normal public Java endpoint is the default
        // identity. This keeps stable user-facing labels while retaining the hidden NDK route as a
        // fallback profile.
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

        if (lens.physicalCameraId != null) score += 15
        if (lens.isLogicalMultiCamera && lens.logicalPhysicalIds.isNotEmpty()) score += 40
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

        // PRIVATE + YUV normally gives two independent pieces of stream evidence. If only one
        // category exists, don't guess that two separate physical modules are aliases.
        return comparable >= 2 && matches == comparable
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

    private fun isHiddenDirect(lens: LensCapability): Boolean =
        lens.physicalCameraId == null &&
            lens.accessPath == CameraAccessPath.NDK_DIRECT &&
            CameraDiscoverySource.HIDDEN_ID_PROBE in lens.discoverySources

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
