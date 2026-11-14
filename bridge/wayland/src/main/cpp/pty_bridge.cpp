#include <jni.h>
#include <android/log.h>
#include <pty.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <string>

#define TAG "ALW::PtyBridgeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jintArray JNICALL
Java_com_alw_workstation_bridge_wayland_PtyBridge_nativeCreatePty(
        JNIEnv* env,
        jobject /* thiz */,
        jint cols,
        jint rows) {
    int masterFd = -1;
    int slaveFd = -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);

    if (openpty(&masterFd, &slaveFd, nullptr, nullptr, &ws) < 0) {
        LOGE("openpty failed: %s", strerror(errno));
        return nullptr;
    }

    // Set non-blocking or standard flags on master FD
    int flags = fcntl(masterFd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(masterFd, F_SETFL, flags | O_CLOEXEC);
    }

    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        close(masterFd);
        close(slaveFd);
        return nullptr;
    }

    jint fds[2] = { masterFd, slaveFd };
    env->SetIntArrayRegion(result, 0, 2, fds);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_alw_workstation_bridge_wayland_PtyBridge_nativeSetWindowSize(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint masterFd,
        jint cols,
        jint rows) {
    if (masterFd < 0) return JNI_FALSE;

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);

    if (ioctl(masterFd, TIOCSWINSZ, &ws) < 0) {
        LOGW("ioctl(TIOCSWINSZ) failed for fd %d: %s", masterFd, strerror(errno));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_PtyBridge_nativeCloseFd(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint fd) {
    if (fd >= 0) {
        close(fd);
    }
}

JNIEXPORT jintArray JNICALL
Java_com_alw_workstation_bridge_wayland_PtyBridge_nativeForkSubprocess(
        JNIEnv* env,
        jobject /* thiz */,
        jstring jCmd,
        jobjectArray jArgs,
        jobjectArray jEnv,
        jstring jCwd,
        jint cols,
        jint rows) {

    const char* cmdStr = env->GetStringUTFChars(jCmd, nullptr);
    const char* cwdStr = jCwd != nullptr ? env->GetStringUTFChars(jCwd, nullptr) : nullptr;

    // Convert args
    std::vector<std::string> argsVec;
    std::vector<char*> argvList;
    if (jArgs != nullptr) {
        jsize argCount = env->GetArrayLength(jArgs);
        for (jsize i = 0; i < argCount; ++i) {
            auto jArg = static_cast<jstring>(env->GetObjectArrayElement(jArgs, i));
            if (jArg != nullptr) {
                const char* argChars = env->GetStringUTFChars(jArg, nullptr);
                argsVec.emplace_back(argChars);
                env->ReleaseStringUTFChars(jArg, argChars);
                env->DeleteLocalRef(jArg);
            }
        }
    }
    if (argsVec.empty()) {
        argsVec.emplace_back(cmdStr);
    }
    for (auto& a : argsVec) {
        argvList.push_back(const_cast<char*>(a.c_str()));
    }
    argvList.push_back(nullptr);

    // Convert env
    std::vector<std::string> envVec;
    std::vector<char*> envpList;
    if (jEnv != nullptr) {
        jsize envCount = env->GetArrayLength(jEnv);
        for (jsize i = 0; i < envCount; ++i) {
            auto jEntry = static_cast<jstring>(env->GetObjectArrayElement(jEnv, i));
            if (jEntry != nullptr) {
                const char* entryChars = env->GetStringUTFChars(jEntry, nullptr);
                envVec.emplace_back(entryChars);
                env->ReleaseStringUTFChars(jEntry, entryChars);
                env->DeleteLocalRef(jEntry);
            }
        }
    }
    for (auto& e : envVec) {
        envpList.push_back(const_cast<char*>(e.c_str()));
    }
    envpList.push_back(nullptr);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);

    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, nullptr, nullptr, &ws);

    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        env->ReleaseStringUTFChars(jCmd, cmdStr);
        if (cwdStr != nullptr) env->ReleaseStringUTFChars(jCwd, cwdStr);
        return nullptr;
    }

    if (pid == 0) {
        // Child process
        if (cwdStr != nullptr && strlen(cwdStr) > 0) {
            chdir(cwdStr);
        }

        // Apply environment variables
        clearenv();
        for (char* e : envpList) {
            if (e != nullptr) {
                putenv(e);
            }
        }

        execv(cmdStr, argvList.data());
        // If execv returns, it failed
        _exit(127);
    }

    // Parent process
    env->ReleaseStringUTFChars(jCmd, cmdStr);
    if (cwdStr != nullptr) env->ReleaseStringUTFChars(jCwd, cwdStr);

    int flags = fcntl(masterFd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(masterFd, F_SETFL, flags | O_CLOEXEC);
    }

    LOGI("forkpty spawned pid %d with masterFd %d", pid, masterFd);

    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        close(masterFd);
        return nullptr;
    }

    jint data[2] = { static_cast<jint>(pid), masterFd };
    env->SetIntArrayRegion(result, 0, 2, data);
    return result;
}

} // extern "C"
