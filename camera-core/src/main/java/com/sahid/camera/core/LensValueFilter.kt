package com.sahid.camera.core

/**
 * Compatibility facade for older Phase-01 call sites.
 *
 * The selector no longer performs ad-hoc optical deduplication. Camera endpoints are first grouped
 * into [LensFamily] objects by [LensFamilyResolver], which retains every alias/profile internally
 * and exposes only the family's default route to the normal UI.
 */
object LensValueFilter {
    fun filterForSelector(routes: List<LensCapability>): List<LensCapability> =
        LensFamilyResolver.defaultsForSelector(routes)

    fun opticalEquivalent(left: LensCapability, right: LensCapability): Boolean =
        LensFamilyResolver.opticalEquivalent(left, right)
}
