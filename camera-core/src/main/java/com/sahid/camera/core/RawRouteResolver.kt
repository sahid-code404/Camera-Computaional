package com.sahid.camera.core

import android.content.Context

/**
 * Resolves the best standards-correct DNG capture profile for a selected lens family.
 *
 * Phase 02 requires a public Camera2 CaptureResult + CameraCharacteristics pair so Android's
 * DngCreator can write one real DNG without fabricating metadata. NDK-only profiles remain useful
 * for preview/discovery, but are not selected for DNG until Aurora has a complete native DNG writer.
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
            .filter {
                it.rawSupported &&
                    it.rawSizes.isNotEmpty() &&
                    it.accessPath != CameraAccessPath.NDK_DIRECT
            }
        if (candidates.isEmpty()) return null

        return candidates.minWithOrNull(
            compareBy<LensCapability> { route ->
                when (route.accessPath) {
                    CameraAccessPath.JAVA_DIRECT -> 0
                    CameraAccessPath.PHYSICAL_VIA_LOGICAL -> 1
                    CameraAccessPath.NDK_DIRECT -> 2
                }
            }.thenBy { route ->
                when {
                    route.learnedFromCache -> 0
                    route.stableId == selected.stableId -> 1
                    else -> 2
                }
            }.thenByDescending { route ->
                route.rawSizes.maxOfOrNull { it.width.toLong() * it.height.toLong() } ?: 0L
            }
        )
    }
}
