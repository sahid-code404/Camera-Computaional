package com.sahid.camera.core

data class CameraDiscoverySnapshot(
    val javaDirectIds: List<String>,
    val ndkDirectIds: List<String>,
    val logicalTopology: Map<String, List<String>>,
)

/**
 * Complete result of a Phase-01 discovery pass.
 *
 * [candidates] contains accepted and rejected access paths so OEM-specific behavior is
 * diagnosable. [visibleLenses] is the strictly runtime-qualified set permitted in normal UI.
 */
data class CameraQualificationReport(
    val discovery: CameraDiscoverySnapshot,
    val candidates: List<LensCapability>,
    val visibleLenses: List<LensCapability>,
)
