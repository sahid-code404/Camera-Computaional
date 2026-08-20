package com.sahid.camera.core

/**
 * Complete result of a Phase-01 discovery pass.
 *
 * [candidates] contains both accepted and rejected Camera2 candidates so device problems
 * are diagnosable. [visibleLenses] is the strictly filtered set permitted in normal UI.
 */
data class CameraQualificationReport(
    val candidates: List<LensCapability>,
    val visibleLenses: List<LensCapability>,
)
