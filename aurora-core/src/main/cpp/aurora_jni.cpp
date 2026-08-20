#include <jni.h>
#include "aurora/version.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_sahid_camera_aurora_AuroraNative_nativeVersion(JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF(aurora::kVersion);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sahid_camera_aurora_AuroraNative_nativeSelfTest(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(aurora::kSelfTestMagic);
}
