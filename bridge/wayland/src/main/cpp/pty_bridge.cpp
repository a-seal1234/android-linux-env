#include <jni.h>
#include <android/log.h>
#include <pty.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/ptrace.h>
#include <sys/prctl.h>

#ifndef PR_SET_PTRACER
#define PR_SET_PTRACER 0x59616d61
#define PR_SET_PTRACER_ANY ((unsigned long)-1)
#endif
#include <unistd.h>
#include <fcntl.h>
#include <csignal>
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
    // argv[0] must always be the command binary per POSIX standard convention
    argvList.push_back(const_cast<char*>(cmdStr));
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

    // Ensure host PATH is present for dynamic loaders and system utilities
    bool hasPath = false;
    for (const auto& e : envVec) {
        if (e.rfind("PATH=", 0) == 0) {
            hasPath = true;
            break;
        }
    }
    if (!hasPath) {
        envVec.emplace_back("PATH=/system/bin:/system/xbin");
    }

    for (auto& e : envVec) {
        envpList.push_back(strdup(e.c_str()));
    }
    envpList.push_back(nullptr);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);

    int ptraceRet = ptrace(PTRACE_TRACEME, 0, nullptr, nullptr);
    LOGI("ptrace(PTRACE_TRACEME) in parent returned %d (errno=%d: %s)", ptraceRet, errno, strerror(errno));

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
        // Restore dumpable so PRoot and its tracees can use ptrace(PTRACE_TRACEME)
        int dRet = prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        int pRet = prctl(PR_SET_PTRACER, PR_SET_PTRACER_ANY, 0, 0, 0);
        LOGI("child pid %d: prctl PR_SET_DUMPABLE=%d, PR_SET_PTRACER=%d", getpid(), dRet, pRet);

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

        LOGI("child pid %d executing %s (argc=%zu)", getpid(), cmdStr, argvList.size() - 1);

        // Prevent Zygote linker-namespace leakage (LD_LIBRARY_PATH / LD_PRELOAD) across
        // fork()/exec(): the guest glibc loader must never traverse Android app library paths.
        unsetenv("LD_LIBRARY_PATH");
        unsetenv("LD_PRELOAD");

        for (size_t i = 0; argvList[i] != nullptr; ++i) {
            LOGI("  argv[%zu] = %s", i, argvList[i]);
        }
        for (size_t i = 0; envpList[i] != nullptr; ++i) {
            LOGI("  envp[%zu] = %s", i, envpList[i]);
        }

        execv(cmdStr, argvList.data());
        LOGE("execv %s failed: %s (errno=%d)", cmdStr, strerror(errno), errno);
        _exit(127);
    }

    // Parent process
    env->ReleaseStringUTFChars(jCmd, cmdStr);
    if (cwdStr != nullptr) env->ReleaseStringUTFChars(jCwd, cwdStr);
    for (char* e : envpList) {
        if (e != nullptr) free(e);
    }

    int flags = fcntl(masterFd, F_GETFD, 0);
    if (flags >= 0) {
        fcntl(masterFd, F_SETFD, flags | FD_CLOEXEC);
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

JNIEXPORT jint JNICALL
Java_com_alw_workstation_bridge_wayland_PtyBridge_nativeWaitPid(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint pid,
        jboolean noHang) {
    int status = 0;
    int options = noHang ? WNOHANG : 0;
    pid_t ret = waitpid(pid, &status, options);
    if (ret < 0) {
        if (errno == ECHILD) return -2; // No child / reaped
        LOGW("waitpid(%d) error: %s (errno=%d)", pid, strerror(errno), errno);
        return -1;
    }
    if (ret == 0) {
        return -3; // Still running (WNOHANG)
    }
    LOGI("waitpid(%d) reaped: ret=%d status=0x%x WIFEXITED=%d code=%d WIFSIGNALED=%d sig=%d",
         pid, ret, status, WIFEXITED(status), WEXITSTATUS(status), WIFSIGNALED(status), WTERMSIG(status));
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return 0;
}

} // extern "C"
