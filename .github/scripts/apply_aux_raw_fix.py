from pathlib import Path

path = Path("aurora-core/src/main/cpp/hidden_camera_discovery.cpp")
text = path.read_text()
old = "    record.rawOutputSizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW16);\n"
new = """    record.rawOutputSizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW10);
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
"""

if old in text:
    path.write_text(text.replace(old, new, 1))
elif "AIMAGE_FORMAT_RAW10" not in text or "AIMAGE_FORMAT_RAW12" not in text:
    raise SystemExit("hidden RAW block is neither legacy nor already patched")

checks = {
    "aurora-core/src/main/cpp/aurora_jni.cpp": ("AIMAGE_FORMAT_RAW10", "AIMAGE_FORMAT_RAW12"),
    "camera-core/src/main/java/com/sahid/camera/core/CameraCapabilityProbe.kt": ("ImageFormat.RAW10", "ImageFormat.RAW12", "qualifier.qualify(lens)"),
    "camera-core/src/main/java/com/sahid/camera/core/CameraSessionQualifier.kt": ("RAW_PROBE_FORMATS", "ImageFormat.RAW10", "ImageFormat.RAW12"),
}
for filename, markers in checks.items():
    body = Path(filename).read_text()
    missing = [marker for marker in markers if marker not in body]
    if missing:
        raise SystemExit(f"{filename} missing expected AUX RAW markers: {missing}")

print("AUX RAW10/RAW16/RAW12 compatibility patch verified")
