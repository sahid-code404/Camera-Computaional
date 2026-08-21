from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"expected block not found in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))


# Normal NDK-advertised camera enumeration: MotionCam-style RAW10 -> RAW16 -> RAW12 coverage.
replace_once(
    "aurora-core/src/main/cpp/aurora_jni.cpp",
    """            const auto rawSizes = metadataStatus == ACAMERA_OK
                ? outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW16)
                : std::vector<NativeSize>{};
""",
    """            std::vector<NativeSize> rawSizes;
            if (metadataStatus == ACAMERA_OK) {
                rawSizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW10);
                const auto raw16Sizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW16);
                const auto raw12Sizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW12);
                rawSizes.insert(rawSizes.end(), raw16Sizes.begin(), raw16Sizes.end());
                rawSizes.insert(rawSizes.end(), raw12Sizes.begin(), raw12Sizes.end());
                std::sort(rawSizes.begin(), rawSizes.end(), [](const NativeSize& left, const NativeSize& right) {
                    return static_cast<int64_t>(left.width) * left.height >
                           static_cast<int64_t>(right.width) * right.height;
                });
                rawSizes.erase(
                    std::unique(rawSizes.begin(), rawSizes.end(), [](const NativeSize& left, const NativeSize& right) {
                        return left.width == right.width && left.height == right.height;
                    }),
                    rawSizes.end());
            }
""",
)

# Hidden numeric/direct-open camera metadata path uses the same three RAW formats.
replace_once(
    "aurora-core/src/main/cpp/hidden_camera_discovery.cpp",
    "    record.rawOutputSizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW16);\n",
    """    record.rawOutputSizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW10);
    const auto raw16Sizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW16);
    const auto raw12Sizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW12);
    record.rawOutputSizes.insert(record.rawOutputSizes.end(), raw16Sizes.begin(), raw16Sizes.end());
    record.rawOutputSizes.insert(record.rawOutputSizes.end(), raw12Sizes.begin(), raw12Sizes.end());
    std::sort(record.rawOutputSizes.begin(), record.rawOutputSizes.end(), [](const NativeSize& left, const NativeSize& right) {
        return static_cast<int64_t>(left.width) * left.height >
               static_cast<int64_t>(right.width) * right.height;
    });
    record.rawOutputSizes.erase(
        std::unique(record.rawOutputSizes.begin(), record.rawOutputSizes.end(), [](const NativeSize& left, const NativeSize& right) {
            return left.width == right.width && left.height == right.height;
        }),
        record.rawOutputSizes.end());
""",
)

# Automatic hidden metadata scanner also retains all RAW stream geometries.
replace_once(
    "aurora-core/src/main/cpp/auto_hidden_metadata.cpp",
    "        const auto rawSizes = outputSizes(metadata, AIMAGE_FORMAT_RAW16);\n",
    """        auto rawSizes = outputSizes(metadata, AIMAGE_FORMAT_RAW10);
        const auto raw16Sizes = outputSizes(metadata, AIMAGE_FORMAT_RAW16);
        const auto raw12Sizes = outputSizes(metadata, AIMAGE_FORMAT_RAW12);
        rawSizes.insert(rawSizes.end(), raw16Sizes.begin(), raw16Sizes.end());
        rawSizes.insert(rawSizes.end(), raw12Sizes.begin(), raw12Sizes.end());
        std::sort(rawSizes.begin(), rawSizes.end(), [](const NativeSize& left, const NativeSize& right) {
            return static_cast<int64_t>(left.width) * left.height >
                   static_cast<int64_t>(right.width) * right.height;
        });
        rawSizes.erase(
            std::unique(rawSizes.begin(), rawSizes.end(), [](const NativeSize& left, const NativeSize& right) {
                return left.width == right.width && left.height == right.height;
            }),
            rawSizes.end());
""",
)

# Java Camera2 metadata: collect all standard RAW formats and treat a real RAW stream as evidence.
replace_once(
    "camera-core/src/main/java/com/sahid/camera/core/CameraCapabilityProbe.kt",
    """        val javaRawSizes = streamMap
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.toList()
            .orEmpty()
""",
    """        val javaRawSizes = mergeSizes(
            streamMap?.getOutputSizes(ImageFormat.RAW10)?.toList().orEmpty(),
            streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty(),
            streamMap?.getOutputSizes(ImageFormat.RAW12)?.toList().orEmpty(),
        )
""",
)
replace_once(
    "camera-core/src/main/java/com/sahid/camera/core/CameraCapabilityProbe.kt",
    "        val rawSupported = rawAdvertisedByJava || native?.rawCapability == true\n",
    "        val rawSupported = rawAdvertisedByJava || native?.rawCapability == true || rawSizes.isNotEmpty()\n",
)

# Keep launch fast, but an explicit Deep rescan now proves RAW with a real frame.
replace_once(
    "camera-core/src/main/java/com/sahid/camera/core/CameraCapabilityProbe.kt",
    """        val qualifiedNew = CameraSessionQualifier(appContext).use { qualifier ->
            newCandidates.mapIndexed { index, lens ->
                qualifier.qualifyPreviewOnly(lens).also {
                    onProgress?.invoke(index + 1, newCandidates.size, it)
                }
            }
        }
""",
    """        val qualifiedNew = CameraSessionQualifier(appContext).use { qualifier ->
            newCandidates.mapIndexed { index, lens ->
                qualifier.qualify(lens).also {
                    onProgress?.invoke(index + 1, newCandidates.size, it)
                }
            }
        }
""",
)

# Progressive/cached metadata should not throw away RAW10/RAW12-only lenses.
replace_once(
    "camera-core/src/main/java/com/sahid/camera/core/ProgressiveLensDiscovery.kt",
    """        val raw = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.toList().orEmpty().sortedByDescending(::area)
""",
    """        val raw = (
            map.getOutputSizes(ImageFormat.RAW10)?.toList().orEmpty() +
                map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty() +
                map.getOutputSizes(ImageFormat.RAW12)?.toList().orEmpty()
            ).distinct().sortedByDescending(::area)
""",
)
replace_once(
    "camera-core/src/main/java/com/sahid/camera/core/ProgressiveLensDiscovery.kt",
    "            rawSupported = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities,\n",
    "            rawSupported = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities || raw.isNotEmpty(),\n",
)
replace_once(
    "camera-core/src/main/java/com/sahid/camera/core/ProgressiveLensDiscovery.kt",
    "            rawSupported = native.rawCapability,\n",
    "            rawSupported = native.rawCapability || native.rawOutputSizes.isNotEmpty(),\n",
)
replace_once(
    "camera-core/src/main/java/com/sahid/camera/core/ProgressiveLensDiscovery.kt",
    "            rawSupported = info.rawCapability,\n",
    "            rawSupported = info.rawCapability || info.rawOutputSizes.isNotEmpty(),\n",
)

# Actual session validation: try RAW10 -> RAW16 -> RAW12 instead of RAW16 only.
q = Path("camera-core/src/main/java/com/sahid/camera/core/CameraSessionQualifier.kt")
text = q.read_text()
old = """        var rawQualifiedSize: Size? = null
        var rawDetail = if (lens.rawSupported) "RAW frame not tested" else "RAW not advertised"
        if (lens.rawSupported) {
            for (rawSize in boundedRawCandidates(lens.rawSizes)) {
                val rawCheck = checkNativeImageFrame(
                    cameraId = lens.cameraId,
                    size = rawSize,
                    format = ImageFormat.RAW_SENSOR,
                    repeating = false,
                )
                rawDetail = rawCheck.detail
                if (rawCheck.supported) {
                    rawQualifiedSize = rawSize
                    break
                }
            }
            if (lens.rawSizes.isEmpty()) rawDetail = "No RAW output size"
        }
"""
new = """        var rawQualifiedSize: Size? = null
        var rawQualifiedFormat: Int? = null
        var rawDetail = if (lens.rawSupported) "RAW frame not tested" else "RAW not advertised"
        if (lens.rawSupported) {
            rawLoop@ for (rawSize in boundedRawCandidates(lens.rawSizes)) {
                for (rawFormat in RAW_PROBE_FORMATS) {
                    val rawCheck = checkNativeImageFrame(
                        cameraId = lens.cameraId,
                        size = rawSize,
                        format = rawFormat,
                        repeating = false,
                    )
                    rawDetail = "${rawFormatLabel(rawFormat)}: ${rawCheck.detail}"
                    if (rawCheck.supported) {
                        rawQualifiedSize = rawSize
                        rawQualifiedFormat = rawFormat
                        break@rawLoop
                    }
                }
            }
            if (lens.rawSizes.isEmpty()) rawDetail = "No RAW output size"
        }
"""
if old not in text:
    raise SystemExit("native RAW qualification block not found")
text = text.replace(old, new, 1)
text = text.replace(
    '                    append("; RAW frame OK ${rawQualifiedSize.width}×${rawQualifiedSize.height}")\n',
    '                    append("; ${rawFormatLabel(rawQualifiedFormat ?: ImageFormat.RAW_SENSOR)} frame OK ${rawQualifiedSize.width}×${rawQualifiedSize.height}")\n',
    1,
)

old = """        var lastDetail = "RAW session not configured"
        for (rawSize in boundedRawCandidates(lens.rawSizes)) {
            val primary = when {
                previewSize != null -> checkSession(
                    camera,
                    lens,
                    previewSize = previewSize,
                    rawSize = rawSize,
                ) to "preview+raw"
                yuvSize != null -> checkSession(
                    camera,
                    lens,
                    yuvSize = yuvSize,
                    rawSize = rawSize,
                ) to "yuv+raw"
                else -> checkSession(camera, lens, rawSize = rawSize) to "raw-only"
            }
            lastDetail = primary.first.detail
            if (primary.first.supported) {
                return RawQualification(rawSize, primary.second, primary.first.detail)
            }

            if (previewSize != null || yuvSize != null) {
                val standalone = checkSession(camera, lens, rawSize = rawSize)
                lastDetail = standalone.detail
                if (standalone.supported) {
                    return RawQualification(rawSize, "raw-only", standalone.detail)
                }
            }
        }
"""
new = """        var lastDetail = "RAW session not configured"
        for (rawSize in boundedRawCandidates(lens.rawSizes)) {
            for (rawFormat in RAW_PROBE_FORMATS) {
                val formatLabel = rawFormatLabel(rawFormat)
                val primary = when {
                    previewSize != null -> checkSession(
                        camera,
                        lens,
                        previewSize = previewSize,
                        rawSize = rawSize,
                        rawFormat = rawFormat,
                    ) to "preview+$formatLabel"
                    yuvSize != null -> checkSession(
                        camera,
                        lens,
                        yuvSize = yuvSize,
                        rawSize = rawSize,
                        rawFormat = rawFormat,
                    ) to "yuv+$formatLabel"
                    else -> checkSession(
                        camera,
                        lens,
                        rawSize = rawSize,
                        rawFormat = rawFormat,
                    ) to "$formatLabel-only"
                }
                lastDetail = "$formatLabel: ${primary.first.detail}"
                if (primary.first.supported) {
                    return RawQualification(rawSize, primary.second, lastDetail)
                }
                if (previewSize != null || yuvSize != null) {
                    val standalone = checkSession(
                        camera,
                        lens,
                        rawSize = rawSize,
                        rawFormat = rawFormat,
                    )
                    lastDetail = "$formatLabel: ${standalone.detail}"
                    if (standalone.supported) {
                        return RawQualification(rawSize, "$formatLabel-only", lastDetail)
                    }
                }
            }
        }
"""
if old not in text:
    raise SystemExit("Java RAW qualification block not found")
text = text.replace(old, new, 1)
text = text.replace(
    """        rawSize: Size? = null,
        timeoutMs: Long = SESSION_TIMEOUT_MS,
""",
    """        rawSize: Size? = null,
        rawFormat: Int = ImageFormat.RAW_SENSOR,
        timeoutMs: Long = SESSION_TIMEOUT_MS,
""",
    1,
)
text = text.replace(
    "        val query = querySupportIfAvailable(lens, previewSize, yuvSize, rawSize)\n",
    "        val query = querySupportIfAvailable(lens, previewSize, yuvSize, rawSize, rawFormat)\n",
    1,
)
text = text.replace(
    "                ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 2)\n",
    "                ImageReader.newInstance(it.width, it.height, rawFormat, 2)\n",
    1,
)
text = text.replace(
    """        rawSize: Size?,
    ): Boolean? {
""",
    """        rawSize: Size?,
        rawFormat: Int,
    ): Boolean? {
""",
    1,
)
text = text.replace(
    "                outputs += OutputConfiguration(ImageFormat.RAW_SENSOR, size).apply {\n",
    "                outputs += OutputConfiguration(rawFormat, size).apply {\n",
    1,
)
marker = "    private fun chooseQualificationSize(sizes: List<Size>): Size? {\n"
helper = """    private fun rawFormatLabel(format: Int): String = when (format) {
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.RAW12 -> "RAW12"
        ImageFormat.RAW_SENSOR -> "RAW16"
        else -> "RAW($format)"
    }

"""
if marker not in text:
    raise SystemExit("qualifier helper insertion point not found")
text = text.replace(marker, helper + marker, 1)
marker = "        const val MAX_RAW_PROBES = 6\n"
replacement = """        const val MAX_RAW_PROBES = 6
        val RAW_PROBE_FORMATS = intArrayOf(
            ImageFormat.RAW10,
            ImageFormat.RAW_SENSOR,
            ImageFormat.RAW12,
        )
"""
if marker not in text:
    raise SystemExit("qualifier companion insertion point not found")
text = text.replace(marker, replacement, 1)
q.write_text(text)

# UI distinguishes metadata-detected-but-unproven RAW from a frame-proven RAW route.
replace_once(
    "app/src/main/java/com/sahid/camera/MainActivity.kt",
    """                        if (lens.rawUsable) {
                            append(" • RAW")
                            qualifiedRaw?.let { append(" ${it.width}×${it.height}") }
                        }
""",
    """                        when {
                            lens.rawUsable -> {
                                append(" • RAW")
                                qualifiedRaw?.let { append(" ${it.width}×${it.height}") }
                            }
                            lens.rawSupported -> append(" • RAW?")
                        }
""",
)
replace_once(
    "app/src/main/java/com/sahid/camera/MainActivity.kt",
    "                            status = \"Deep compatibility rescan…\"\n",
    "                            status = \"Deep compatibility rescan + RAW10/16/12 validation…\"\n",
)

print("AUX RAW compatibility patch applied successfully")
