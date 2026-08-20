#include <jni.h>

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCaptureRequest.h>

#include <cstdint>
#include <mutex>
#include <sstream>
#include <string>

namespace {

enum class RawStage : int32_t {
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

struct RawSession {
    ACameraManager* manager = nullptr;
    ACameraDevice* device = nullptr;
    ACameraCaptureSession* session = nullptr;
    ACaptureSessionOutputContainer* outputContainer = nullptr;
    ACaptureSessionOutput* output = nullptr;
    ACaptureRequest* request = nullptr;
    ACameraOutputTarget* target = nullptr;
    ANativeWindow* window = nullptr;
    int sequenceId = -1;

    std::mutex metadataMutex;
    int64_t captureStartedTimestampNs = 0;
    std::string captureMetadataJson = "{\"complete\":false,\"failed\":false}";
};

void deviceDisconnected(void*, ACameraDevice*) {}
void deviceError(void*, ACameraDevice*, int) {}
void sessionClosed(void*, ACameraCaptureSession*) {}
void sessionReady(void*, ACameraCaptureSession*) {}
void sessionActive(void*, ACameraCaptureSession*) {}

bool getEntry(const ACameraMetadata* metadata, uint32_t tag, ACameraMetadata_const_entry* entry) {
    return metadata != nullptr && entry != nullptr &&
        ACameraMetadata_getConstEntry(metadata, tag, entry) == ACAMERA_OK && entry->count > 0;
}

void appendInt64(std::ostringstream& out, const ACameraMetadata* metadata, uint32_t tag, const char* name) {
    ACameraMetadata_const_entry entry{};
    out << ",\"" << name << "\":";
    if (getEntry(metadata, tag, &entry) && entry.data.i64 != nullptr) out << entry.data.i64[0];
    else out << "null";
}

void appendInt32(std::ostringstream& out, const ACameraMetadata* metadata, uint32_t tag, const char* name) {
    ACameraMetadata_const_entry entry{};
    out << ",\"" << name << "\":";
    if (getEntry(metadata, tag, &entry) && entry.data.i32 != nullptr) out << entry.data.i32[0];
    else out << "null";
}

void appendByte(std::ostringstream& out, const ACameraMetadata* metadata, uint32_t tag, const char* name) {
    ACameraMetadata_const_entry entry{};
    out << ",\"" << name << "\":";
    if (getEntry(metadata, tag, &entry) && entry.data.u8 != nullptr) out << static_cast<int>(entry.data.u8[0]);
    else out << "null";
}

void appendFloat(std::ostringstream& out, const ACameraMetadata* metadata, uint32_t tag, const char* name) {
    ACameraMetadata_const_entry entry{};
    out << ",\"" << name << "\":";
    if (getEntry(metadata, tag, &entry) && entry.data.f != nullptr) out << entry.data.f[0];
    else out << "null";
}

void appendFloatArray(std::ostringstream& out, const ACameraMetadata* metadata, uint32_t tag, const char* name) {
    ACameraMetadata_const_entry entry{};
    out << ",\"" << name << "\":";
    if (!getEntry(metadata, tag, &entry) || entry.data.f == nullptr) {
        out << "null";
        return;
    }
    out << '[';
    for (uint32_t i = 0; i < entry.count; ++i) {
        if (i != 0) out << ',';
        out << entry.data.f[i];
    }
    out << ']';
}

void appendDoublePairs(std::ostringstream& out, const ACameraMetadata* metadata, uint32_t tag, const char* name) {
    ACameraMetadata_const_entry entry{};
    out << ",\"" << name << "\":";
    if (!getEntry(metadata, tag, &entry) || entry.data.d == nullptr) {
        out << "null";
        return;
    }
    out << '[';
    for (uint32_t i = 0; i + 1 < entry.count; i += 2) {
        if (i != 0) out << ',';
        out << '[' << entry.data.d[i] << ',' << entry.data.d[i + 1] << ']';
    }
    out << ']';
}

void appendRationalArray(std::ostringstream& out, const ACameraMetadata* metadata, uint32_t tag, const char* name) {
    ACameraMetadata_const_entry entry{};
    out << ",\"" << name << "\":";
    if (!getEntry(metadata, tag, &entry) || entry.data.r == nullptr) {
        out << "null";
        return;
    }
    out << '[';
    for (uint32_t i = 0; i < entry.count; ++i) {
        if (i != 0) out << ',';
        out << "{\"numerator\":" << entry.data.r[i].numerator
            << ",\"denominator\":" << entry.data.r[i].denominator << '}';
    }
    out << ']';
}

std::string resultJson(const RawSession* state, const ACameraMetadata* result) {
    std::ostringstream out;
    out << "{\"complete\":true,\"failed\":false"
        << ",\"captureStartedTimestampNs\":" << state->captureStartedTimestampNs;
    appendInt64(out, result, ACAMERA_SENSOR_TIMESTAMP, "sensorTimestampNs");
    appendInt64(out, result, ACAMERA_SENSOR_EXPOSURE_TIME, "exposureTimeNs");
    appendInt32(out, result, ACAMERA_SENSOR_SENSITIVITY, "sensitivityIso");
    appendInt64(out, result, ACAMERA_SENSOR_FRAME_DURATION, "frameDurationNs");
    appendInt64(out, result, ACAMERA_SENSOR_ROLLING_SHUTTER_SKEW, "rollingShutterSkewNs");
    appendFloat(out, result, ACAMERA_LENS_FOCUS_DISTANCE, "focusDistanceDiopters");
    appendFloat(out, result, ACAMERA_LENS_APERTURE, "aperture");
    appendFloat(out, result, ACAMERA_LENS_FOCAL_LENGTH, "focalLengthMm");
    appendByte(out, result, ACAMERA_CONTROL_AE_STATE, "aeState");
    appendByte(out, result, ACAMERA_CONTROL_AF_STATE, "afState");
    appendByte(out, result, ACAMERA_CONTROL_AWB_STATE, "awbState");
    appendInt32(out, result, ACAMERA_SENSOR_DYNAMIC_WHITE_LEVEL, "dynamicWhiteLevel");
    appendFloatArray(out, result, ACAMERA_SENSOR_DYNAMIC_BLACK_LEVEL, "dynamicBlackLevel");
    appendRationalArray(out, result, ACAMERA_SENSOR_NEUTRAL_COLOR_POINT, "neutralColorPoint");
    appendDoublePairs(out, result, ACAMERA_SENSOR_NOISE_PROFILE, "noiseProfile");
    appendFloatArray(out, result, ACAMERA_COLOR_CORRECTION_GAINS, "colorCorrectionGains");
    appendRationalArray(out, result, ACAMERA_COLOR_CORRECTION_TRANSFORM, "colorCorrectionTransform");
    out << '}';
    return out.str();
}

void captureStarted(
    void* context,
    ACameraCaptureSession*,
    const ACaptureRequest*,
    int64_t timestamp) {
    auto* state = static_cast<RawSession*>(context);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->metadataMutex);
    state->captureStartedTimestampNs = timestamp;
}

void captureCompleted(
    void* context,
    ACameraCaptureSession*,
    ACaptureRequest*,
    const ACameraMetadata* result) {
    auto* state = static_cast<RawSession*>(context);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->metadataMutex);
    state->captureMetadataJson = resultJson(state, result);
}

void captureFailed(
    void* context,
    ACameraCaptureSession*,
    ACaptureRequest*,
    ACameraCaptureFailure*) {
    auto* state = static_cast<RawSession*>(context);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->metadataMutex);
    state->captureMetadataJson = "{\"complete\":false,\"failed\":true}";
}

void sequenceAborted(void* context, ACameraCaptureSession*, int) {
    auto* state = static_cast<RawSession*>(context);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->metadataMutex);
    if (state->captureMetadataJson.find("\"complete\":true") == std::string::npos) {
        state->captureMetadataJson = "{\"complete\":false,\"failed\":true,\"reason\":\"sequence-aborted\"}";
    }
}

void destroySession(RawSession* state) {
    if (state == nullptr) return;
    if (state->session != nullptr) {
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

jlongArray startResult(JNIEnv* env, RawSession* state, camera_status_t status, RawStage stage, bool opened) {
    const jlong values[4] = {
        state == nullptr ? static_cast<jlong>(0) : static_cast<jlong>(reinterpret_cast<intptr_t>(state)),
        static_cast<jlong>(status),
        static_cast<jlong>(stage),
        opened ? static_cast<jlong>(1) : static_cast<jlong>(0),
    };
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_sahid_camera_aurora_NativeRawCaptureSession_nativeStart(
    JNIEnv* env,
    jobject,
    jstring cameraIdValue,
    jobject surface) {
    if (cameraIdValue == nullptr || surface == nullptr) {
        return startResult(env, nullptr, ACAMERA_ERROR_INVALID_PARAMETER, RawStage::MANAGER, false);
    }

    const char* cameraId = env->GetStringUTFChars(cameraIdValue, nullptr);
    if (cameraId == nullptr) {
        return startResult(env, nullptr, ACAMERA_ERROR_INVALID_PARAMETER, RawStage::MANAGER, false);
    }

    auto* state = new RawSession();
    camera_status_t status = ACAMERA_OK;
    RawStage stage = RawStage::MANAGER;
    bool opened = false;

    state->manager = ACameraManager_create();
    if (state->manager == nullptr) {
        env->ReleaseStringUTFChars(cameraIdValue, cameraId);
        delete state;
        return startResult(env, nullptr, ACAMERA_ERROR_UNKNOWN, stage, false);
    }

    ACameraDevice_StateCallbacks deviceCallbacks{};
    deviceCallbacks.context = nullptr;
    deviceCallbacks.onDisconnected = deviceDisconnected;
    deviceCallbacks.onError = deviceError;

    stage = RawStage::OPEN;
    status = ACameraManager_openCamera(state->manager, cameraId, &deviceCallbacks, &state->device);
    env->ReleaseStringUTFChars(cameraIdValue, cameraId);
    if (status != ACAMERA_OK || state->device == nullptr) {
        destroySession(state);
        return startResult(env, nullptr, status, stage, false);
    }
    opened = true;

    stage = RawStage::WINDOW;
    state->window = ANativeWindow_fromSurface(env, surface);
    if (state->window == nullptr) {
        destroySession(state);
        return startResult(env, nullptr, ACAMERA_ERROR_INVALID_PARAMETER, stage, opened);
    }

    stage = RawStage::OUTPUT_CONTAINER;
    status = ACaptureSessionOutputContainer_create(&state->outputContainer);
    if (status != ACAMERA_OK || state->outputContainer == nullptr) {
        destroySession(state);
        return startResult(env, nullptr, status, stage, opened);
    }

    stage = RawStage::OUTPUT;
    status = ACaptureSessionOutput_create(state->window, &state->output);
    if (status != ACAMERA_OK || state->output == nullptr) {
        destroySession(state);
        return startResult(env, nullptr, status, stage, opened);
    }
    status = ACaptureSessionOutputContainer_add(state->outputContainer, state->output);
    if (status != ACAMERA_OK) {
        destroySession(state);
        return startResult(env, nullptr, status, stage, opened);
    }

    stage = RawStage::REQUEST;
    status = ACameraDevice_createCaptureRequest(
        state->device,
        TEMPLATE_STILL_CAPTURE,
        &state->request);
    if (status != ACAMERA_OK || state->request == nullptr) {
        destroySession(state);
        return startResult(env, nullptr, status, stage, opened);
    }

    stage = RawStage::TARGET;
    status = ACameraOutputTarget_create(state->window, &state->target);
    if (status != ACAMERA_OK || state->target == nullptr) {
        destroySession(state);
        return startResult(env, nullptr, status, stage, opened);
    }
    status = ACaptureRequest_addTarget(state->request, state->target);
    if (status != ACAMERA_OK) {
        destroySession(state);
        return startResult(env, nullptr, status, stage, opened);
    }

    ACameraCaptureSession_stateCallbacks sessionCallbacks{};
    sessionCallbacks.context = nullptr;
    sessionCallbacks.onClosed = sessionClosed;
    sessionCallbacks.onReady = sessionReady;
    sessionCallbacks.onActive = sessionActive;

    stage = RawStage::CAPTURE_SESSION;
    status = ACameraDevice_createCaptureSession(
        state->device,
        state->outputContainer,
        &sessionCallbacks,
        &state->session);
    if (status != ACAMERA_OK || state->session == nullptr) {
        destroySession(state);
        return startResult(env, nullptr, status, stage, opened);
    }

    ACameraCaptureSession_captureCallbacks callbacks{};
    callbacks.context = state;
    callbacks.onCaptureStarted = captureStarted;
    callbacks.onCaptureCompleted = captureCompleted;
    callbacks.onCaptureFailed = captureFailed;
    callbacks.onCaptureSequenceAborted = sequenceAborted;

    ACaptureRequest* requests[] = {state->request};
    stage = RawStage::SUBMIT;
    status = ACameraCaptureSession_capture(
        state->session,
        &callbacks,
        1,
        requests,
        &state->sequenceId);
    if (status != ACAMERA_OK) {
        destroySession(state);
        return startResult(env, nullptr, status, stage, opened);
    }

    return startResult(env, state, ACAMERA_OK, RawStage::RUNNING, opened);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sahid_camera_aurora_NativeRawCaptureSession_nativeCaptureMetadataJson(
    JNIEnv* env,
    jobject,
    jlong handle) {
    if (handle == 0) return nullptr;
    auto* state = reinterpret_cast<RawSession*>(static_cast<intptr_t>(handle));
    std::lock_guard<std::mutex> lock(state->metadataMutex);
    return env->NewStringUTF(state->captureMetadataJson.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_sahid_camera_aurora_NativeRawCaptureSession_nativeStop(
    JNIEnv*,
    jobject,
    jlong handle) {
    if (handle == 0) return;
    auto* state = reinterpret_cast<RawSession*>(static_cast<intptr_t>(handle));
    destroySession(state);
}
