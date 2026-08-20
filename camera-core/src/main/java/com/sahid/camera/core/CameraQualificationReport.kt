package com.sahid.camera.core

data class CameraDiscoverySnapshot(
    val javaDirectIds: List<String>,
    val ndkDirectIds: List<String>,
    val logicalTopology: Map<String, List<String>>,
    val hiddenProbeMaxNumericId: Int = 0,
    val hiddenProbeAttemptedCount: Int = 0,
    val hiddenMetadataIds: List<String> = emptyList(),
    val hiddenDiscoveredIds: List<String> = emptyList(),
    val hiddenLogicalTopology: Map<String, List<String>> = emptyMap(),
    val hiddenRejectedStatuses: Map<String, Int> = emptyMap(),
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
