from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"expected block not found in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))


replace_once(
    "aurora-core/src/main/cpp/aurora_jni.cpp",
    "    record.rawOutputSizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW16);\n",
    """    record.rawOutputSizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW10);\n    const auto raw16Sizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW16);\n    const auto raw12Sizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW12);\n    record.rawOutputSizes.insert(record.rawOutputSizes.end(), raw16Sizes.begin(), raw16Sizes.end());\n    record.rawOutputSizes.insert(record.rawOutputSizes.end(), raw12Sizes.begin(), raw12Sizes.end());\n    std::sort(record.rawOutputSizes.begin(), record.rawOutputSizes.end(), [](const NativeSize& left, const NativeSize& right) {\n        return static_cast<int64_t>(left.width) * left.height >\n               static_cast<int64_t>(right.width) * right.height;\n    });\n    record.rawOutputSizes.erase(\n        std::unique(record.rawOutputSizes.begin(), record.rawOutputSizes.end(), [](const NativeSize& left, const NativeSize& right) {\n            return left.width == right.width && left.height == right.height;\n        }),\n        record.rawOutputSizes.end());\n""",
)

replace_once(
    "aurora-core/src/main/cpp/auto_hidden_metadata.cpp",
    "        const auto rawSizes = outputSizes(metadata, AIMAGE_FORMAT_RAW16);\n",
    """        auto rawSizes = outputSizes(metadata, AIMAGE_FORMAT_RAW10);\n        const auto raw16Sizes = outputSizes(metadata, AIMAGE_FORMAT_RAW16);\n        const auto raw12Sizes = outputSizes(metadata, AIMAGE_FORMAT_RAW12);\n        rawSizes.insert(rawSizes.end(), raw16Sizes.begin(), raw16Sizes.end());\n        rawSizes.insert(rawSizes.end(), raw12Sizes.begin(), raw12Sizes.end());\n        std::sort(rawSizes.begin(), rawSizes.end(), [](const NativeSize& left, const NativeSize& right) {\n            return static_cast<int64_t>(left.width) * left.height >\n                   static_cast<int64_t>(right.width) * right.height;\n        });\n        rawSizes.erase(\n            std::unique(rawSizes.begin(), rawSizes.end(), [](const NativeSize& left, const NativeSize& right) {\n                return left.width == right.width && left.height == right.height;\n            }),\n            rawSizes.end());\n""",
)

replace_once(
    "camera-core/src/main/java/com/sahid/camera/core/CameraCapabilityProbe.kt",
    """        val javaRawSizes = streamMap\n            ?.getOutputSizes(ImageFormat.RAW_SENSOR)\n            ?.toList()\n            .orEmpty()\n""",
    """        val javaRawSizes = mergeSizes(\n            streamMap?.getOutputSizes(ImageFormat.RAW10)?.toList().orEmpty(),\n            streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty(),\n            streamMap?.getOutputSizes(ImageFormat.RAW12)?.toList().orEmpty(),\n        )\n""",
)
replace_once(
    "camera-core/src/main/java/com/sahid/camera/core/CameraCapabilityProbe.kt",
    "        val rawSupported = rawAdvertisedByJava || native?.rawCapability == true\n",
    "        val rawSupported = rawAdvertisedByJava || native?.rawCapability == true || rawSizes.isNotEmpty()\n",
)
replace_once(
    "camera-core/src/main/java/com/sahid/camera/core/CameraCapabilityProbe.kt",
    """        val qualifiedNew = CameraSessionQualifier(appContext).use { qualifier ->\n            newCandidates.mapIndexed { index, lens ->\n                qualifier.qualifyPreviewOnly(lens).also {\n                    onProgress?.invoke(index + 1, newCandidates.size, it)\n                }\n            }\n        }\n""",
    """        val qualifiedNew = CameraSessionQualifier(appContext).use { qualifier ->\n            newCandidates.mapIndexed { index, lens ->\n                qualifier.qualify(lens).also {\n                    onProgress?.invoke(index + 1, newCandidates.size, it)\n                }\n            }\n        }\n""",
)

replace_once(
    "camera-core/src/main/java/com/sahid/camera/core/ProgressiveLensDiscovery.kt",
    """        val raw = map.getOutputSizes(ImageFormat.RAW_SENSOR)\n            ?.toList().orEmpty().sortedByDescending(::area)\n""",
    """        val raw = (\n            map.getOutputSizes(ImageFormat.RAW10)?.toList().orEmpty() +\n                map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty() +\n                map.getOutputSizes(ImageFormat.RAW12)?.toList().orEmpty()\n            ).distinct().sortedByDescending(::area)\n""",
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

q = Path("camera-core/src/main/java/com/sahid/camera/core/CameraSessionQualifier.kt")
text = q.read_text()
old = """        var rawQualifiedSize: Size? = null\n        var rawDetail = if (lens.rawSupported) \"RAW frame not tested\" else \"RAW not advertised\"\n        if (lens.rawSupported) {\n            for (rawSize in boundedRawCandidates(lens.rawSizes)) {\n                val rawCheck = checkNativeImageFrame(\n                    cameraId = lens.cameraId,\n                    size = rawSize,\n                    format = ImageFormat.RAW_SENSOR,\n                    repeating = false,\n                )\n                rawDetail = rawCheck.detail\n                if (rawCheck.supported) {\n                    rawQualifiedSize = rawSize\n                    break\n                }\n            }\n            if (lens.rawSizes.isEmpty()) rawDetail = \"No RAW output size\"\n        }\n"""
new = """        var rawQualifiedSize: Size? = null\n        var rawQualifiedFormat: Int? = null\n        var rawDetail = if (lens.rawSupported) \"RAW frame not tested\" else \"RAW not advertised\"\n        if (lens.rawSupported) {\n            rawLoop@ for (rawSize in boundedRawCandidates(lens.rawSizes)) {\n                for (rawFormat in RAW_PROBE_FORMATS) {\n                    val rawCheck = checkNativeImageFrame(\n                        cameraId = lens.cameraId,\n                        size = rawSize,\n                        format = rawFormat,\n                        repeating = false,\n                    )\n                    rawDetail = \"${rawFormatLabel(rawFormat)}: ${rawCheck.detail}\"\n                    if (rawCheck.supported) {\n                        rawQualifiedSize = rawSize\n                        rawQualifiedFormat = rawFormat\n                        break@rawLoop\n                    }\n                }\n            }\n            if (lens.rawSizes.isEmpty()) rawDetail = \"No RAW output size\"\n        }\n"""
if old not in text:
    raise SystemExit("native RAW qualification block not found")
text = text.replace(old, new, 1)
text = text.replace(
    '                    append("; RAW frame OK ${rawQualifiedSize.width}×${rawQualifiedSize.height}")\n',
    '                    append("; ${rawFormatLabel(rawQualifiedFormat ?: ImageFormat.RAW_SENSOR)} frame OK ${rawQualifiedSize.width}×${rawQualifiedSize.height}")\n',
    1,
)

old = """        var lastDetail = \"RAW session not configured\"\n        for (rawSize in boundedRawCandidates(lens.rawSizes)) {\n            val primary = when {\n                previewSize != null -> checkSession(\n                    camera,\n                    lens,\n                    previewSize = previewSize,\n                    rawSize = rawSize,\n                ) to \"preview+raw\"\n                yuvSize != null -> checkSession(\n                    camera,\n                    lens,\n                    yuvSize = yuvSize,\n                    rawSize = rawSize,\n                ) to \"yuv+raw\"\n                else -> checkSession(camera, lens, rawSize = rawSize) to \"raw-only\"\n            }\n            lastDetail = primary.first.detail\n            if (primary.first.supported) {\n                return RawQualification(rawSize, primary.second, primary.first.detail)\n            }\n\n            if (previewSize != null || yuvSize != null) {\n                val standalone = checkSession(camera, lens, rawSize = rawSize)\n                lastDetail = standalone.detail\n                if (standalone.supported) {\n                    return RawQualification(rawSize, \"raw-only\", standalone.detail)\n                }\n            }\n        }\n"""
new = """        var lastDetail = \"RAW session not configured\"\n        for (rawSize in boundedRawCandidates(lens.rawSizes)) {\n            for (rawFormat in RAW_PROBE_FORMATS) {\n                val formatLabel = rawFormatLabel(rawFormat)\n                val primary = when {\n                    previewSize != null -> checkSession(\n                        camera,\n                        lens,\n                        previewSize = previewSize,\n                        rawSize = rawSize,\n                        rawFormat = rawFormat,\n                    ) to \"preview+$formatLabel\"\n                    yuvSize != null -> checkSession(\n                        camera,\n                        lens,\n                        yuvSize = yuvSize,\n                        rawSize = rawSize,\n                        rawFormat = rawFormat,\n                    ) to \"yuv+$formatLabel\"\n                    else -> checkSession(\n                        camera,\n                        lens,\n                        rawSize = rawSize,\n                        rawFormat = rawFormat,\n                    ) to \"$formatLabel-only\"\n                }\n                lastDetail = \"$formatLabel: ${primary.first.detail}\"\n                if (primary.first.supported) {\n                    return RawQualification(rawSize, primary.second, lastDetail)\n                }\n                if (previewSize != null || yuvSize != null) {\n                    val standalone = checkSession(\n                        camera,\n                        lens,\n                        rawSize = rawSize,\n                        rawFormat = rawFormat,\n                    )\n                    lastDetail = \"$formatLabel: ${standalone.detail}\"\n                    if (standalone.supported) {\n                        return RawQualification(rawSize, \"$formatLabel-only\", lastDetail)\n                    }\n                }\n            }\n        }\n"""
if old not in text:
    raise SystemExit("Java RAW qualification block not found")
text = text.replace(old, new, 1)
text = text.replace(
    """        rawSize: Size? = null,\n        timeoutMs: Long = SESSION_TIMEOUT_MS,\n""",
    """        rawSize: Size? = null,\n        rawFormat: Int = ImageFormat.RAW_SENSOR,\n        timeoutMs: Long = SESSION_TIMEOUT_MS,\n""",
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
    """        rawSize: Size?,\n    ): Boolean? {\n""",
    """        rawSize: Size?,\n        rawFormat: Int,\n    ): Boolean? {\n""",
    1,
)
text = text.replace(
    "                outputs += OutputConfiguration(ImageFormat.RAW_SENSOR, size).apply {\n",
    "                outputs += OutputConfiguration(rawFormat, size).apply {\n",
    1,
)
marker = "    private fun chooseQualificationSize(sizes: List<Size>): Size? {\n"
helper = """    private fun rawFormatLabel(format: Int): String = when (format) {\n        ImageFormat.RAW10 -> \"RAW10\"\n        ImageFormat.RAW12 -> \"RAW12\"\n        ImageFormat.RAW_SENSOR -> \"RAW16\"\n        else -> \"RAW($format)\"\n    }\n\n"""
if marker not in text:
    raise SystemExit("qualifier helper insertion point not found")
text = text.replace(marker, helper + marker, 1)
marker = "        const val MAX_RAW_PROBES = 6\n"
replacement = """        const val MAX_RAW_PROBES = 6\n        val RAW_PROBE_FORMATS = intArrayOf(\n            ImageFormat.RAW10,\n            ImageFormat.RAW_SENSOR,\n            ImageFormat.RAW12,\n        )\n"""
if marker not in text:
    raise SystemExit("qualifier companion insertion point not found")
text = text.replace(marker, replacement, 1)
q.write_text(text)

replace_once(
    "app/src/main/java/com/sahid/camera/MainActivity.kt",
    """                        if (lens.rawUsable) {\n                            append(\" • RAW\")\n                            qualifiedRaw?.let { append(\" ${it.width}×${it.height}\") }\n                        }\n""",
    """                        when {\n                            lens.rawUsable -> {\n                                append(\" • RAW\")\n                                qualifiedRaw?.let { append(\" ${it.width}×${it.height}\") }\n                            }\n                            lens.rawSupported -> append(\" • RAW?\")\n                        }\n""",
)
replace_once(
    "app/src/main/java/com/sahid/camera/MainActivity.kt",
    "                            status = \"Deep compatibility rescan…\"\n",
    "                            status = \"Deep compatibility rescan + RAW10/16/12 validation…\"\n",
)

print("AUX RAW compatibility patch applied successfully")
