#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string>

#define TAG "ALW::WaylandBridgeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static ANativeWindow* s_nativeWindow = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeInitServer(
    JNIEnv* env,
    jobject thiz,
    jstring socketDir,
    jint width,
    jint height,
    jfloat scale
) {
    const char* dir = env->GetStringUTFChars(socketDir, nullptr);
    LOGI("Initializing Wayland server in %s (%dx%d @ scale %.2f)", dir, width, height, scale);
    env->ReleaseStringUTFChars(socketDir, dir);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSetSurface(
    JNIEnv* env,
    jobject thiz,
    jobject surface
) {
    if (s_nativeWindow != nullptr) {
        ANativeWindow_release(s_nativeWindow);
        s_nativeWindow = nullptr;
    }
    if (surface != nullptr) {
        s_nativeWindow = ANativeWindow_fromSurface(env, surface);
        LOGI("Native window acquired from SurfaceView: %p", s_nativeWindow);
    } else {
        LOGI("Native window detached");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendPointerMotion(
    JNIEnv* env,
    jobject thiz,
    jfloat dx,
    jfloat dy
) {
    // Relative pointer delta dispatch to Wayland wl_pointer
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendKeyEvent(
    JNIEnv* env,
    jobject thiz,
    jint scanCode,
    jboolean isDown
) {
    // Hardware key event dispatch to Wayland wl_keyboard
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeStopServer(
    JNIEnv* env,
    jobject thiz
) {
    LOGI("Stopping native Wayland server");
    if (s_nativeWindow != nullptr) {
        ANativeWindow_release(s_nativeWindow);
        s_nativeWindow = nullptr;
    }
}
