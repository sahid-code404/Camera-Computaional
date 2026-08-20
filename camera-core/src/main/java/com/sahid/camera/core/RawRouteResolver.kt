package com.sahid.camera.core

import android.content.Context

/**
 * Resolves the best RAW_SENSOR access profile for a selected user-facing lens family.
 *
 * Preview and RAW do not have to use the same API route. A public Java preview default can retain
 * an NDK or physical-via-logical alias that is the only profile advertising RAW. Phase 02 therefore
 * chooses RAW from the complete cached family rather than assuming the visible route is sufficient.
 */
class RawRouteResolver(context: Context) {
    private val appContext = context.applicationContext

    fun resolve(selected: LensCapability): LensCapability? {
        val allRoutes = buildList {
            add(selected)
            addAll(LearnedLensStore(appContext).load().routes)
            addAll(CandidateLensStore(appContext).load())
        }.distinctBy { it.stableId to it.discoverySources }

        val family = LensFamilyResolver.resolve(allRoutes).firstOrNull { candidate ->
            candidate.routes.any { route ->
                route.stableId == selected.stableId || route.cameraId == selected.cameraId
            }
        }

        val candidates = (family?.routes ?: listOf(selected))
            .filter { it.rawSupported && it.rawSizes.isNotEmpty() }
        if (candidates.isEmpty()) return null

        return candidates.minWithOrNull(
            compareBy<LensCapability> { route ->
                when {
                    route.stableId == selected.stableId -> 0
                    route.learnedFromCache -> 1
                    else -> 2
                }
            }.thenBy { route ->
                when (route.accessPath) {
                    CameraAccessPath.JAVA_DIRECT -> 0
                    CameraAccessPath.NDK_DIRECT -> 1
                    CameraAccessPath.PHYSICAL_VIA_LOGICAL -> 2
                }
            }.thenByDescending { route ->
                route.rawSizes.maxOfOrNull { it.width.toLong() * it.height.toLong() } ?: 0L
            }
        )
    }
}
