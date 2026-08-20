#include <jni.h>

#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImage.h>

#include <algorithm>
#include <cstdint>
#include <set>
#include <sstream>
#include <string>
#include <vector>

namespace {

struct NativeSize {
    int32_t width;
    int32_t height;
};

std::string escapeJson(const std::string& value) {
    std::ostringstream out;
    for (const unsigned char c : value) {
        switch (c) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default: out << static_cast<char>(c); break;
        }
    }
    return out.str();
}

bool readByte(const ACameraMetadata* metadata, uint32_t tag, uint8_t* value) {
    ACameraMetadata_const_entry entry{};
    if (metadata == nullptr || value == nullptr ||
        ACameraMetadata_getConstEntry(metadata, tag, &entry) != ACAMERA_OK ||
        entry.count == 0 || entry.data.u8 == nullptr) {
        return false;
    }
    *value = entry.data.u8[0];
    return true;
}

bool readFirstFloat(const ACameraMetadata* metadata, uint32_t tag, float* value) {
    ACameraMetadata_const_entry entry{};
    if (metadata == nullptr || value == nullptr ||
        ACameraMetadata_getConstEntry(metadata, tag, &entry) != ACAMERA_OK ||
        entry.count == 0 || entry.data.f == nullptr) {
        return false;
    }
    *value = entry.data.f[0];
    return true;
}

bool readFloatPair(const ACameraMetadata* metadata, uint32_t tag, float* first, float* second) {
    ACameraMetadata_const_entry entry{};
    if (metadata == nullptr || first == nullptr || second == nullptr ||
        ACameraMetadata_getConstEntry(metadata, tag, &entry) != ACAMERA_OK ||
        entry.count < 2 || entry.data.f == nullptr) {
        return false;
    }
    *first = entry.data.f[0];
    *second = entry.data.f[1];
    return true;
}

bool hasByteValue(const ACameraMetadata* metadata, uint32_t tag, uint8_t expected) {
    ACameraMetadata_const_entry entry{};
    if (metadata == nullptr ||
        ACameraMetadata_getConstEntry(metadata, tag, &entry) != ACAMERA_OK ||
        entry.data.u8 == nullptr) {
        return false;
    }
    for (uint32_t index = 0; index < entry.count; ++index) {
        if (entry.data.u8[index] == expected) return true;
    }
    return false;
}

std::vector<NativeSize> outputSizes(const ACameraMetadata* metadata, int32_t format) {
    std::vector<NativeSize> result;
    ACameraMetadata_const_entry entry{};
    if (metadata == nullptr ||
        ACameraMetadata_getConstEntry(
            metadata,
            ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
            &entry) != ACAMERA_OK ||
        entry.data.i32 == nullptr) {
        return result;
    }
    for (uint32_t index = 0; index + 3 < entry.count; index += 4) {
        if (entry.data.i32[index] != format || entry.data.i32[index + 3] != 0) continue;
        const int32_t width = entry.data.i32[index + 1];
        const int32_t height = entry.data.i32[index + 2];
        if (width > 0 && height > 0) result.push_back({width, height});
    }
    std::sort(result.begin(), result.end(), [](const NativeSize& left, const NativeSize& right) {
        return static_cast<int64_t>(left.width) * left.height >
               static_cast<int64_t>(right.width) * right.height;
    });
    result.erase(
        std::unique(result.begin(), result.end(), [](const NativeSize& left, const NativeSize& right) {
            return left.width == right.width && left.height == right.height;
        }),
        result.end());
    return result;
}

std::vector<std::string> physicalIds(const ACameraMetadata* metadata) {
    std::vector<std::string> result;
    ACameraMetadata_const_entry entry{};
    if (metadata == nullptr ||
        ACameraMetadata_getConstEntry(
            metadata,
            ACAMERA_LOGICAL_MULTI_CAMERA_PHYSICAL_IDS,
            &entry) != ACAMERA_OK ||
        entry.data.u8 == nullptr) {
        return result;
    }
    size_t start = 0;
    for (size_t index = 0; index < entry.count; ++index) {
        if (entry.data.u8[index] != '\0') continue;
        if (index > start) {
            result.emplace_back(
                reinterpret_cast<const char*>(entry.data.u8 + start),
                index - start);
        }
        start = index + 1;
    }
    if (start < entry.count) {
        result.emplace_back(
            reinterpret_cast<const char*>(entry.data.u8 + start),
            entry.count - start);
    }
    return result;
}

void appendSizes(std::ostringstream& out, const std::vector<NativeSize>& values) {
    out << '[';
    for (size_t index = 0; index < values.size(); ++index) {
        if (index != 0) out << ',';
        out << "{\"width\":" << values[index].width
            << ",\"height\":" << values[index].height << '}';
    }
    out << ']';
}

void appendStrings(std::ostringstream& out, const std::vector<std::string>& values) {
    out << '[';
    for (size_t index = 0; index < values.size(); ++index) {
        if (index != 0) out << ',';
        out << '"' << escapeJson(values[index]) << '"';
    }
    out << ']';
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_sahid_camera_aurora_AutoHiddenMetadataEnumerator_nativeScanJson(
    JNIEnv* env,
    jobject /* thiz */,
    jint maxIdValue) {
    const int32_t maxId = std::clamp(static_cast<int32_t>(maxIdValue), 0, 1024);
    ACameraManager* manager = ACameraManager_create();
    if (manager == nullptr) return env->NewStringUTF("{\"cameras\":[]}");

    std::set<std::string> advertised;
    ACameraIdList* cameraIds = nullptr;
    if (ACameraManager_getCameraIdList(manager, &cameraIds) == ACAMERA_OK && cameraIds != nullptr) {
        for (int index = 0; index < cameraIds->numCameras; ++index) {
            if (cameraIds->cameraIds[index] != nullptr) advertised.emplace(cameraIds->cameraIds[index]);
        }
    }

    std::ostringstream out;
    out << "{\"maxId\":" << maxId << ",\"cameras\":[";
    bool firstCamera = true;
    for (int32_t numericId = 0; numericId <= maxId; ++numericId) {
        const std::string id = std::to_string(numericId);
        ACameraMetadata* metadata = nullptr;
        const camera_status_t status =
            ACameraManager_getCameraCharacteristics(manager, id.c_str(), &metadata);
        if (status != ACAMERA_OK || metadata == nullptr) {
            if (metadata != nullptr) ACameraMetadata_free(metadata);
            continue;
        }

        uint8_t facing = 0;
        float focal = 0.0f;
        float sensorWidth = 0.0f;
        float sensorHeight = 0.0f;
        const bool hasFacing = readByte(metadata, ACAMERA_LENS_FACING, &facing);
        const bool hasFocal = readFirstFloat(metadata, ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS, &focal);
        const bool hasSensor = readFloatPair(
            metadata,
            ACAMERA_SENSOR_INFO_PHYSICAL_SIZE,
            &sensorWidth,
            &sensorHeight);
        const bool raw = hasByteValue(
            metadata,
            ACAMERA_REQUEST_AVAILABLE_CAPABILITIES,
            ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_RAW);
        const bool logical = hasByteValue(
            metadata,
            ACAMERA_REQUEST_AVAILABLE_CAPABILITIES,
            ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
        const auto privateSizes = outputSizes(metadata, AIMAGE_FORMAT_PRIVATE);
        const auto yuvSizes = outputSizes(metadata, AIMAGE_FORMAT_YUV_420_888);
        const auto rawSizes = outputSizes(metadata, AIMAGE_FORMAT_RAW16);
        const auto children = physicalIds(metadata);

        if (!firstCamera) out << ',';
        firstCamera = false;
        out << "{\"id\":\"" << escapeJson(id) << "\""
            << ",\"advertised\":" << (advertised.count(id) ? "true" : "false")
            << ",\"facing\":";
        if (hasFacing) out << static_cast<int>(facing); else out << "null";
        out << ",\"focalLengthMm\":";
        if (hasFocal) out << focal; else out << "null";
        out << ",\"sensorWidthMm\":";
        if (hasSensor) out << sensorWidth; else out << "null";
        out << ",\"sensorHeightMm\":";
        if (hasSensor) out << sensorHeight; else out << "null";
        out << ",\"rawCapability\":" << (raw ? "true" : "false")
            << ",\"logicalMultiCamera\":" << (logical ? "true" : "false")
            << ",\"physicalIds\":";
        appendStrings(out, children);
        out << ",\"privateOutputSizes\":";
        appendSizes(out, privateSizes);
        out << ",\"yuvOutputSizes\":";
        appendSizes(out, yuvSizes);
        out << ",\"rawOutputSizes\":";
        appendSizes(out, rawSizes);
        out << '}';

        ACameraMetadata_free(metadata);
    }
    out << "]}";

    if (cameraIds != nullptr) ACameraManager_deleteCameraIdList(cameraIds);
    ACameraManager_delete(manager);
    const std::string json = out.str();
    return env->NewStringUTF(json.c_str());
}
