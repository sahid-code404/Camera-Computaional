#include <jni.h>

#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImage.h>

#include <algorithm>
#include <cstdint>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace {

struct NativeSize {
    int32_t width;
    int32_t height;
};

struct CameraRecord {
    std::string id;
    bool advertised = false;
    int32_t characteristicsStatus = ACAMERA_ERROR_UNKNOWN;
    bool hasHardwareLevel = false;
    uint8_t hardwareLevel = 0;
    bool hasFacing = false;
    uint8_t facing = 0;
    bool hasFocalLength = false;
    float focalLengthMm = 0.0f;
    bool hasSensorPhysicalSize = false;
    float sensorWidthMm = 0.0f;
    float sensorHeightMm = 0.0f;
    bool rawCapability = false;
    bool logicalMultiCamera = false;
    std::vector<std::string> physicalIds;
    std::vector<NativeSize> privateOutputSizes;
    std::vector<NativeSize> yuvOutputSizes;
    std::vector<NativeSize> rawOutputSizes;
};

std::string jsonEscape(const std::string& value) {
    std::ostringstream out;
    for (const unsigned char c : value) {
        switch (c) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) {
                    const char* hex = "0123456789abcdef";
                    out << "\\u00" << hex[(c >> 4) & 0x0f] << hex[c & 0x0f];
                } else {
                    out << static_cast<char>(c);
                }
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

bool readFloat(const ACameraMetadata* metadata, uint32_t tag, float* value) {
    ACameraMetadata_const_entry entry{};
    if (metadata == nullptr || value == nullptr ||
        ACameraMetadata_getConstEntry(metadata, tag, &entry) != ACAMERA_OK ||
        entry.count == 0 || entry.data.f == nullptr) {
        return false;
    }
    *value = entry.data.f[0];
    return true;
}

bool readFloatPair(
    const ACameraMetadata* metadata,
    uint32_t tag,
    float* first,
    float* second) {
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

std::vector<NativeSize> outputSizesForFormat(const ACameraMetadata* metadata, int32_t format) {
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
        const int32_t candidateFormat = entry.data.i32[index];
        const int32_t width = entry.data.i32[index + 1];
        const int32_t height = entry.data.i32[index + 2];
        const int32_t input = entry.data.i32[index + 3];
        if (candidateFormat == format && input == 0 && width > 0 && height > 0) {
            result.push_back({width, height});
        }
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

std::vector<std::string> readPhysicalIds(const ACameraMetadata* metadata) {
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
        if (entry.data.u8[index] == '\0') {
            if (index > start) {
                result.emplace_back(
                    reinterpret_cast<const char*>(entry.data.u8 + start),
                    index - start);
            }
            start = index + 1;
        }
    }
    if (start < entry.count) {
        result.emplace_back(
            reinterpret_cast<const char*>(entry.data.u8 + start),
            entry.count - start);
    }
    std::sort(result.begin(), result.end());
    result.erase(std::unique(result.begin(), result.end()), result.end());
    return result;
}

void appendStringArray(std::ostringstream& out, const std::vector<std::string>& values) {
    out << '[';
    for (size_t index = 0; index < values.size(); ++index) {
        if (index != 0) out << ',';
        out << '"' << jsonEscape(values[index]) << '"';
    }
    out << ']';
}

void appendSizes(std::ostringstream& out, const std::vector<NativeSize>& sizes) {
    out << '[';
    for (size_t index = 0; index < sizes.size(); ++index) {
        if (index != 0) out << ',';
        out << "{\"width\":" << sizes[index].width
            << ",\"height\":" << sizes[index].height << '}';
    }
    out << ']';
}

void appendCamera(std::ostringstream& out, const CameraRecord& camera) {
    out << "{\"id\":\"" << jsonEscape(camera.id) << "\""
        << ",\"advertised\":" << (camera.advertised ? "true" : "false")
        << ",\"characteristicsStatus\":" << camera.characteristicsStatus
        << ",\"hardwareLevel\":";
    if (camera.hasHardwareLevel) out << static_cast<int>(camera.hardwareLevel); else out << "null";
    out << ",\"facing\":";
    if (camera.hasFacing) out << static_cast<int>(camera.facing); else out << "null";
    out << ",\"focalLengthMm\":";
    if (camera.hasFocalLength) out << camera.focalLengthMm; else out << "null";
    out << ",\"sensorWidthMm\":";
    if (camera.hasSensorPhysicalSize) out << camera.sensorWidthMm; else out << "null";
    out << ",\"sensorHeightMm\":";
    if (camera.hasSensorPhysicalSize) out << camera.sensorHeightMm; else out << "null";
    out << ",\"rawCapability\":" << (camera.rawCapability ? "true" : "false")
        << ",\"logicalMultiCamera\":" << (camera.logicalMultiCamera ? "true" : "false")
        << ",\"physicalIds\":";
    appendStringArray(out, camera.physicalIds);
    out << ",\"privateOutputSizes\":";
    appendSizes(out, camera.privateOutputSizes);
    out << ",\"yuvOutputSizes\":";
    appendSizes(out, camera.yuvOutputSizes);
    out << ",\"rawOutputSizes\":";
    appendSizes(out, camera.rawOutputSizes);
    out << '}';
}

CameraRecord readCameraRecord(
    const std::string& id,
    bool advertised,
    const ACameraMetadata* metadata,
    camera_status_t status) {
    CameraRecord record;
    record.id = id;
    record.advertised = advertised;
    record.characteristicsStatus = static_cast<int32_t>(status);
    if (status != ACAMERA_OK || metadata == nullptr) return record;

    record.hasHardwareLevel =
        readByte(metadata, ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL, &record.hardwareLevel);
    record.hasFacing = readByte(metadata, ACAMERA_LENS_FACING, &record.facing);
    record.hasFocalLength =
        readFloat(metadata, ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS, &record.focalLengthMm);
    record.hasSensorPhysicalSize = readFloatPair(
        metadata,
        ACAMERA_SENSOR_INFO_PHYSICAL_SIZE,
        &record.sensorWidthMm,
        &record.sensorHeightMm);
    record.rawCapability = hasByteValue(
        metadata,
        ACAMERA_REQUEST_AVAILABLE_CAPABILITIES,
        ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_RAW);
    record.logicalMultiCamera = hasByteValue(
        metadata,
        ACAMERA_REQUEST_AVAILABLE_CAPABILITIES,
        ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
    record.physicalIds = readPhysicalIds(metadata);
    record.privateOutputSizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_PRIVATE);
    record.yuvOutputSizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_YUV_420_888);
    record.rawOutputSizes = outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW16);
    return record;
}

void deepDeviceDisconnected(void*, ACameraDevice*) {}
void deepDeviceError(void*, ACameraDevice*, int) {}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_sahid_camera_aurora_NativeCameraEnumerator_nativeSearchHiddenNumericJson(
    JNIEnv* env,
    jobject /* thiz */,
    jint maxIdValue) {
    const int32_t maxId = std::clamp(static_cast<int32_t>(maxIdValue), 0, 1024);
    ACameraManager* manager = ACameraManager_create();
    if (manager == nullptr) {
        return env->NewStringUTF(
            "{\"maxId\":0,\"attemptedCount\":0,\"advertisedIds\":[],\"validCameras\":[],\"hiddenIds\":[],\"rejectedStatuses\":{},\"directOpenStatuses\":{},\"directOpenSucceededIds\":[]}");
    }

    std::set<std::string> advertised;
    ACameraIdList* cameraIds = nullptr;
    const camera_status_t listStatus = ACameraManager_getCameraIdList(manager, &cameraIds);
    if (listStatus == ACAMERA_OK && cameraIds != nullptr) {
        for (int index = 0; index < cameraIds->numCameras; ++index) {
            if (cameraIds->cameraIds[index] != nullptr) {
                advertised.emplace(cameraIds->cameraIds[index]);
            }
        }
    }

    std::vector<CameraRecord> validCameras;
    std::vector<std::string> hiddenIds;
    std::map<std::string, int32_t> rejectedStatuses;

    for (int32_t numericId = 0; numericId <= maxId; ++numericId) {
        const std::string id = std::to_string(numericId);
        ACameraMetadata* metadata = nullptr;
        const camera_status_t status =
            ACameraManager_getCameraCharacteristics(manager, id.c_str(), &metadata);
        if (status == ACAMERA_OK && metadata != nullptr) {
            const bool isAdvertised = advertised.find(id) != advertised.end();
            validCameras.push_back(readCameraRecord(id, isAdvertised, metadata, status));
            if (!isAdvertised) hiddenIds.push_back(id);
        } else {
            rejectedStatuses[id] = static_cast<int32_t>(status);
        }
        if (metadata != nullptr) ACameraMetadata_free(metadata);
    }

    // Fast MotionCam-style fallback: reuse the SAME native manager and synchronously try only
    // IDs whose metadata was filtered. Invalid IDs fail immediately; real hidden endpoints are
    // opened and closed without hundreds of Java callback timeouts or manager allocations.
    std::map<std::string, int32_t> directOpenStatuses;
    std::vector<std::string> directOpenSucceededIds;
    ACameraDevice_StateCallbacks callbacks{};
    callbacks.context = nullptr;
    callbacks.onDisconnected = deepDeviceDisconnected;
    callbacks.onError = deepDeviceError;

    for (const auto& [id, metadataStatus] : rejectedStatuses) {
        (void) metadataStatus;
        ACameraDevice* device = nullptr;
        const camera_status_t openStatus =
            ACameraManager_openCamera(manager, id.c_str(), &callbacks, &device);
        directOpenStatuses[id] = static_cast<int32_t>(openStatus);
        if (openStatus == ACAMERA_OK && device != nullptr) {
            directOpenSucceededIds.push_back(id);
        }
        if (device != nullptr) {
            ACameraDevice_close(device);
        }
    }

    std::vector<std::string> advertisedIds(advertised.begin(), advertised.end());
    std::ostringstream out;
    out << "{\"maxId\":" << maxId
        << ",\"attemptedCount\":" << (maxId + 1)
        << ",\"listStatus\":" << static_cast<int32_t>(listStatus)
        << ",\"advertisedIds\":";
    appendStringArray(out, advertisedIds);
    out << ",\"validCameras\":[";
    for (size_t index = 0; index < validCameras.size(); ++index) {
        if (index != 0) out << ',';
        appendCamera(out, validCameras[index]);
    }
    out << "] ,\"hiddenIds\":";
    appendStringArray(out, hiddenIds);
    out << ",\"rejectedStatuses\":{";
    bool firstRejected = true;
    for (const auto& [id, status] : rejectedStatuses) {
        if (!firstRejected) out << ',';
        firstRejected = false;
        out << '"' << jsonEscape(id) << "\":" << status;
    }
    out << "},\"directOpenStatuses\":{";
    bool firstOpen = true;
    for (const auto& [id, status] : directOpenStatuses) {
        if (!firstOpen) out << ',';
        firstOpen = false;
        out << '"' << jsonEscape(id) << "\":" << status;
    }
    out << "},\"directOpenSucceededIds\":";
    appendStringArray(out, directOpenSucceededIds);
    out << '}';

    if (cameraIds != nullptr) ACameraManager_deleteCameraIdList(cameraIds);
    ACameraManager_delete(manager);

    const std::string json = out.str();
    return env->NewStringUTF(json.c_str());
}