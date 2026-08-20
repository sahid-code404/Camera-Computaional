#include <jni.h>

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCaptureRequest.h>
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

enum class SessionStage : int32_t {
    MANAGER = 1,
    OPEN = 2,
    WINDOW = 3,
    OUTPUT_CONTAINER = 4,
    OUTPUT = 5,
    REQUEST = 6,
    TARGET = 7,
    CAPTURE_SESSION = 8,
    SUBMIT = 9,
    RUNNING = 10,
};

struct NativeSession {
    ACameraManager* manager = nullptr;
    ACameraDevice* device = nullptr;
    ACameraCaptureSession* session = nullptr;
    ACaptureSessionOutputContainer* outputContainer = nullptr;
    ACaptureSessionOutput* output = nullptr;
    ACaptureRequest* request = nullptr;
    ACameraOutputTarget* target = nullptr;
    ANativeWindow* window = nullptr;
    int sequenceId = -1;
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

void deviceDisconnected(void*, ACameraDevice*) {}
void deviceError(void*, ACameraDevice*, int) {}
void sessionClosed(void*, ACameraCaptureSession*) {}
void sessionReady(void*, ACameraCaptureSession*) {}
void sessionActive(void*, ACameraCaptureSession*) {}

void destroySession(NativeSession* state) {
    if (state == nullptr) return;

    if (state->session != nullptr) {
        ACameraCaptureSession_stopRepeating(state->session);
        ACameraCaptureSession_abortCaptures(state->session);
        ACameraCaptureSession_close(state->session);
        state->session = nullptr;
    }
    if (state->request != nullptr) {
        ACaptureRequest_free(state->request);
        state->request = nullptr;
    }
    if (state->target != nullptr) {
        ACameraOutputTarget_free(state->target);
        state->target = nullptr;
    }
    if (state->outputContainer != nullptr && state->output != nullptr) {
        ACaptureSessionOutputContainer_remove(state->outputContainer, state->output);
    }
    if (state->output != nullptr) {
        ACaptureSessionOutput_free(state->output);
        state->output = nullptr;
    }
    if (state->outputContainer != nullptr) {
        ACaptureSessionOutputContainer_free(state->outputContainer);
        state->outputContainer = nullptr;
    }
    if (state->window != nullptr) {
        ANativeWindow_release(state->window);
        state->window = nullptr;
    }
    if (state->device != nullptr) {
        ACameraDevice_close(state->device);
        state->device = nullptr;
    }
    if (state->manager != nullptr) {
        ACameraManager_delete(state->manager);
        state->manager = nullptr;
    }
    delete state;
}

jlongArray sessionResult(
    JNIEnv* env,
    NativeSession* session,
    camera_status_t status,
    SessionStage stage,
    bool opened) {
    jlong values[4] = {
        session == nullptr
            ? static_cast<jlong>(0)
            : static_cast<jlong>(reinterpret_cast<intptr_t>(session)),
        static_cast<jlong>(status),
        static_cast<jlong>(stage),
        opened ? static_cast<jlong>(1) : static_cast<jlong>(0),
    };
    jlongArray array = env->NewLongArray(4);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, 4, values);
    }
    return array;
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
            float sensorWidth = 0.0f;
            float sensorHeight = 0.0f;
            const bool hasHardwareLevel =
                metadataStatus == ACAMERA_OK &&
                readByte(metadata, ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL, &hardwareLevel);
            const bool hasFacing =
                metadataStatus == ACAMERA_OK && readByte(metadata, ACAMERA_LENS_FACING, &facing);
            const bool hasFocalLength =
                metadataStatus == ACAMERA_OK &&
                readFloat(metadata, ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS, &focalLength);
            const bool hasSensorPhysicalSize =
                metadataStatus == ACAMERA_OK &&
                readFloatPair(
                    metadata,
                    ACAMERA_SENSOR_INFO_PHYSICAL_SIZE,
                    &sensorWidth,
                    &sensorHeight);
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
            out << ",\"sensorWidthMm\":";
            if (hasSensorPhysicalSize) out << sensorWidth; else out << "null";
            out << ",\"sensorHeightMm\":";
            if (hasSensorPhysicalSize) out << sensorHeight; else out << "null";
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
    callbacks.onDisconnected = deviceDisconnected;
    callbacks.onError = deviceError;

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

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_sahid_camera_aurora_NativeCameraSession_nativeStartSession(
    JNIEnv* env,
    jobject /* thiz */,
    jstring cameraIdValue,
    jobject surface,
    jint templateValue,
    jboolean repeating) {
    if (cameraIdValue == nullptr || surface == nullptr) {
        return sessionResult(
            env,
            nullptr,
            ACAMERA_ERROR_INVALID_PARAMETER,
            SessionStage::MANAGER,
            false);
    }

    const char* cameraId = env->GetStringUTFChars(cameraIdValue, nullptr);
    if (cameraId == nullptr) {
        return sessionResult(
            env,
            nullptr,
            ACAMERA_ERROR_INVALID_PARAMETER,
            SessionStage::MANAGER,
            false);
    }

    NativeSession* state = new NativeSession();
    camera_status_t status = ACAMERA_OK;
    SessionStage stage = SessionStage::MANAGER;
    bool opened = false;

    state->manager = ACameraManager_create();
    if (state->manager == nullptr) {
        status = ACAMERA_ERROR_UNKNOWN;
        env->ReleaseStringUTFChars(cameraIdValue, cameraId);
        delete state;
        return sessionResult(env, nullptr, status, stage, false);
    }

    ACameraDevice_StateCallbacks deviceCallbacks{};
    deviceCallbacks.context = nullptr;
    deviceCallbacks.onDisconnected = deviceDisconnected;
    deviceCallbacks.onError = deviceError;

    stage = SessionStage::OPEN;
    status = ACameraManager_openCamera(
        state->manager,
        cameraId,
        &deviceCallbacks,
        &state->device);
    env->ReleaseStringUTFChars(cameraIdValue, cameraId);
    if (status != ACAMERA_OK || state->device == nullptr) {
        destroySession(state);
        return sessionResult(env, nullptr, status, stage, false);
    }
    opened = true;

    stage = SessionStage::WINDOW;
    state->window = ANativeWindow_fromSurface(env, surface);
    if (state->window == nullptr) {
        destroySession(state);
        return sessionResult(env, nullptr, ACAMERA_ERROR_INVALID_PARAMETER, stage, opened);
    }

    stage = SessionStage::OUTPUT_CONTAINER;
    status = ACaptureSessionOutputContainer_create(&state->outputContainer);
    if (status != ACAMERA_OK || state->outputContainer == nullptr) {
        destroySession(state);
        return sessionResult(env, nullptr, status, stage, opened);
    }

    stage = SessionStage::OUTPUT;
    status = ACaptureSessionOutput_create(state->window, &state->output);
    if (status != ACAMERA_OK || state->output == nullptr) {
        destroySession(state);
        return sessionResult(env, nullptr, status, stage, opened);
    }
    status = ACaptureSessionOutputContainer_add(state->outputContainer, state->output);
    if (status != ACAMERA_OK) {
        destroySession(state);
        return sessionResult(env, nullptr, status, stage, opened);
    }

    stage = SessionStage::REQUEST;
    const auto requestTemplate = static_cast<ACameraDevice_request_template>(templateValue);
    status = ACameraDevice_createCaptureRequest(state->device, requestTemplate, &state->request);
    if (status != ACAMERA_OK || state->request == nullptr) {
        destroySession(state);
        return sessionResult(env, nullptr, status, stage, opened);
    }

    stage = SessionStage::TARGET;
    status = ACameraOutputTarget_create(state->window, &state->target);
    if (status != ACAMERA_OK || state->target == nullptr) {
        destroySession(state);
        return sessionResult(env, nullptr, status, stage, opened);
    }
    status = ACaptureRequest_addTarget(state->request, state->target);
    if (status != ACAMERA_OK) {
        destroySession(state);
        return sessionResult(env, nullptr, status, stage, opened);
    }

    ACameraCaptureSession_stateCallbacks sessionCallbacks{};
    sessionCallbacks.context = nullptr;
    sessionCallbacks.onClosed = sessionClosed;
    sessionCallbacks.onReady = sessionReady;
    sessionCallbacks.onActive = sessionActive;

    stage = SessionStage::CAPTURE_SESSION;
    status = ACameraDevice_createCaptureSession(
        state->device,
        state->outputContainer,
        &sessionCallbacks,
        &state->session);
    if (status != ACAMERA_OK || state->session == nullptr) {
        destroySession(state);
        return sessionResult(env, nullptr, status, stage, opened);
    }

    ACaptureRequest* requests[] = {state->request};
    stage = SessionStage::SUBMIT;
    if (repeating == JNI_TRUE) {
        status = ACameraCaptureSession_setRepeatingRequest(
            state->session,
            nullptr,
            1,
            requests,
            &state->sequenceId);
    } else {
        status = ACameraCaptureSession_capture(
            state->session,
            nullptr,
            1,
            requests,
            &state->sequenceId);
    }
    if (status != ACAMERA_OK) {
        destroySession(state);
        return sessionResult(env, nullptr, status, stage, opened);
    }

    return sessionResult(env, state, ACAMERA_OK, SessionStage::RUNNING, opened);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sahid_camera_aurora_NativeCameraSession_nativeStopSession(
    JNIEnv*,
    jobject /* thiz */,
    jlong handle) {
    if (handle == 0) return;
    auto* state = reinterpret_cast<NativeSession*>(static_cast<intptr_t>(handle));
    destroySession(state);
}
