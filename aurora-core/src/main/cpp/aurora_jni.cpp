#include <jni.h>

#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImage.h>

#include <algorithm>
#include <cstdint>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "aurora/version.h"

namespace {

struct NativeSize {
    int32_t width;
    int32_t height;
};

std::string jsonEscape(const char* value) {
    std::ostringstream out;
    const char* cursor = value == nullptr ? "" : value;
    while (*cursor != '\0') {
        const unsigned char c = static_cast<unsigned char>(*cursor++);
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

bool hasByteValue(const ACameraMetadata* metadata, uint32_t tag, uint8_t expected) {
    ACameraMetadata_const_entry entry{};
    if (metadata == nullptr ||
        ACameraMetadata_getConstEntry(metadata, tag, &entry) != ACAMERA_OK ||
        entry.data.u8 == nullptr) {
        return false;
    }
    for (uint32_t index = 0; index < entry.count; ++index) {
        if (entry.data.u8[index] == expected) {
            return true;
        }
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

    // Stream configuration tuples are: format, width, height, input(1)/output(0).
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

void appendSizesJson(std::ostringstream& out, const std::vector<NativeSize>& sizes) {
    out << '[';
    for (size_t index = 0; index < sizes.size(); ++index) {
        if (index != 0) out << ',';
        out << "{\"width\":" << sizes[index].width
            << ",\"height\":" << sizes[index].height << '}';
    }
    out << ']';
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_sahid_camera_aurora_AuroraNative_nativeVersion(JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF(aurora::kVersion);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sahid_camera_aurora_AuroraNative_nativeSelfTest(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(aurora::kSelfTestMagic);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sahid_camera_aurora_NativeCameraEnumerator_nativeEnumerateJson(
    JNIEnv* env,
    jobject /* thiz */) {
    ACameraManager* manager = ACameraManager_create();
    if (manager == nullptr) {
        return env->NewStringUTF("{\"managerStatus\":-1,\"cameras\":[]}");
    }

    ACameraIdList* cameraIds = nullptr;
    const camera_status_t listStatus = ACameraManager_getCameraIdList(manager, &cameraIds);

    std::ostringstream out;
    out << "{\"managerStatus\":" << static_cast<int>(listStatus) << ",\"cameras\":[";

    if (listStatus == ACAMERA_OK && cameraIds != nullptr) {
        for (int index = 0; index < cameraIds->numCameras; ++index) {
            if (index != 0) out << ',';
            const char* cameraId = cameraIds->cameraIds[index];
            ACameraMetadata* metadata = nullptr;
            const camera_status_t metadataStatus =
                ACameraManager_getCameraCharacteristics(manager, cameraId, &metadata);

            uint8_t hardwareLevel = 0;
            uint8_t facing = 0;
            float focalLength = 0.0f;
            const bool hasHardwareLevel =
                metadataStatus == ACAMERA_OK &&
                readByte(metadata, ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL, &hardwareLevel);
            const bool hasFacing =
                metadataStatus == ACAMERA_OK && readByte(metadata, ACAMERA_LENS_FACING, &facing);
            const bool hasFocalLength =
                metadataStatus == ACAMERA_OK &&
                readFloat(metadata, ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS, &focalLength);
            const bool rawCapability =
                metadataStatus == ACAMERA_OK &&
                hasByteValue(
                    metadata,
                    ACAMERA_REQUEST_AVAILABLE_CAPABILITIES,
                    ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_RAW);

            const auto privateSizes = metadataStatus == ACAMERA_OK
                ? outputSizesForFormat(metadata, AIMAGE_FORMAT_PRIVATE)
                : std::vector<NativeSize>{};
            const auto yuvSizes = metadataStatus == ACAMERA_OK
                ? outputSizesForFormat(metadata, AIMAGE_FORMAT_YUV_420_888)
                : std::vector<NativeSize>{};
            const auto rawSizes = metadataStatus == ACAMERA_OK
                ? outputSizesForFormat(metadata, AIMAGE_FORMAT_RAW16)
                : std::vector<NativeSize>{};

            out << "{\"id\":\"" << jsonEscape(cameraId) << "\""
                << ",\"characteristicsStatus\":" << static_cast<int>(metadataStatus)
                << ",\"hardwareLevel\":";
            if (hasHardwareLevel) out << static_cast<int>(hardwareLevel); else out << "null";
            out << ",\"facing\":";
            if (hasFacing) out << static_cast<int>(facing); else out << "null";
            out << ",\"focalLengthMm\":";
            if (hasFocalLength) out << focalLength; else out << "null";
            out << ",\"rawCapability\":" << (rawCapability ? "true" : "false")
                << ",\"privateOutputSizes\":";
            appendSizesJson(out, privateSizes);
            out << ",\"yuvOutputSizes\":";
            appendSizesJson(out, yuvSizes);
            out << ",\"rawOutputSizes\":";
            appendSizesJson(out, rawSizes);
            out << '}';

            if (metadata != nullptr) {
                ACameraMetadata_free(metadata);
            }
        }
    }

    out << "]}";

    if (cameraIds != nullptr) {
        ACameraManager_deleteCameraIdList(cameraIds);
    }
    ACameraManager_delete(manager);

    const std::string json = out.str();
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sahid_camera_aurora_NativeCameraEnumerator_nativeProbeDirectOpen(
    JNIEnv* env,
    jobject /* thiz */,
    jstring cameraIdValue) {
    if (cameraIdValue == nullptr) {
        return static_cast<jint>(ACAMERA_ERROR_INVALID_PARAMETER);
    }

    const char* cameraId = env->GetStringUTFChars(cameraIdValue, nullptr);
    if (cameraId == nullptr) {
        return static_cast<jint>(ACAMERA_ERROR_INVALID_PARAMETER);
    }

    ACameraManager* manager = ACameraManager_create();
    if (manager == nullptr) {
        env->ReleaseStringUTFChars(cameraIdValue, cameraId);
        return static_cast<jint>(ACAMERA_ERROR_UNKNOWN);
    }

    ACameraDevice_StateCallbacks callbacks{};
    callbacks.context = nullptr;
    callbacks.onDisconnected = [](void*, ACameraDevice*) {};
    callbacks.onError = [](void*, ACameraDevice*, int) {};

    ACameraDevice* device = nullptr;
    const camera_status_t status =
        ACameraManager_openCamera(manager, cameraId, &callbacks, &device);

    if (device != nullptr) {
        ACameraDevice_close(device);
    }
    ACameraManager_delete(manager);
    env->ReleaseStringUTFChars(cameraIdValue, cameraId);

    return static_cast<jint>(status);
}
