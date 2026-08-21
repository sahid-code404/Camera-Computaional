package com.sahid.camera.core

data class CameraDiscoverySnapshot(
    val javaDirectIds: List<String>,
    val ndkDirectIds: List<String>,
    val logicalTopology: Map<String, List<String>>,
    val hiddenProbeMaxNumericId: Int = 0,
    val hiddenProbeAttemptedCount: Int = 0,
    /** IDs whose NDK getCameraCharacteristics probe succeeded. */
    val hiddenMetadataIds: List<String> = emptyList(),
    /** Metadata-valid IDs absent from both advertised direct-ID lists. */
    val hiddenDiscoveredIds: List<String> = emptyList(),
    val hiddenLogicalTopology: Map<String, List<String>> = emptyMap(),
    /** NDK metadata failure status by numeric ID. */
    val hiddenRejectedStatuses: Map<String, Int> = emptyMap(),
    /** IDs whose Java CameraManager.getCameraCharacteristics probe succeeded independently. */
    val deepJavaMetadataIds: List<String> = emptyList(),
    /** Java metadata failure/exception detail by numeric ID. */
    val deepJavaMetadataFailures: Map<String, String> = emptyMap(),
    /** Java direct-open probe detail for IDs where both metadata APIs failed. */
    val deepJavaOpenResults: Map<String, String> = emptyMap(),
    val deepJavaOpenSucceededIds: List<String> = emptyList(),
    /** NDK direct-open status for IDs where both metadata APIs failed. */
    val deepNdkOpenStatuses: Map<String, Int> = emptyMap(),
    val deepNdkOpenSucceededIds: List<String> = emptyList(),
    /** IDs promoted into runtime qualification solely because a direct-open path succeeded. */
    val deepOpenDiscoveredIds: List<String> = emptyList(),
    /** Camera Lab deliberately disables cross-ID optical deduplication. */
    val cameraLabMode: Boolean = false,
)

/**
 * Complete result of a Phase-01 discovery pass.
 *
 * [candidates] contains accepted and rejected access paths so OEM-specific behavior is
 * diagnosable. [visibleLenses] is the runtime-qualified set permitted in the current UI mode.
 */
data class CameraQualificationReport(
    val discovery: CameraDiscoverySnapshot,
    val candidates: List<LensCapability>,
    val visibleLenses: List<LensCapability>,
)
