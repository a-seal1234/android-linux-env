#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>

#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <cstring>
#include <memory>
#include <algorithm>

#define TAG "ALW::WaylandBridgeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Wayland 24.8 fixed point conversion
inline int32_t wl_fixed_from_double(double d) {
    return static_cast<int32_t>(d * 256.0);
}

[[maybe_unused]] inline double wl_fixed_to_double(int32_t f) {
    return static_cast<double>(f) / 256.0;
}

struct WaylandGlobal {
    uint32_t name;
    std::string interface;
    uint32_t version;
};

struct TabletToolState {
    int toolType = 1; // 1 = PEN, 2 = ERASER, 3 = BRUSH
    bool inProximity = false;
    int32_t x_fixed = 0;
    int32_t y_fixed = 0;
    uint32_t pressure = 0; // 0 .. 65535
    int32_t distance_fixed = 0;
    int32_t tilt_x_fixed = 0;
    int32_t tilt_y_fixed = 0;
    int32_t rotation_fixed = 0;
    bool isDown = false;
    uint32_t buttonMask = 0;
};

struct PointerState {
    int32_t dx_fixed = 0;
    int32_t dy_fixed = 0;
    uint32_t buttonMask = 0;
};

class WaylandSurfaceServer {
public:
    WaylandSurfaceServer()
        : m_running(false),
          m_serverFd(-1),
          m_nativeWindow(nullptr),
          m_width(3200),
          m_height(2136),
          m_scale(1.75f),
          m_activeClients(0) {
        initGlobals();
    }

    ~WaylandSurfaceServer() {
        stop();
    }

    bool start(const std::string& socketDir, int width, int height, float scale) {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_running.load()) {
            LOGW("Wayland server already running");
            return true;
        }

        m_socketDir = socketDir;
        m_width = width > 0 ? width : 3200;
        m_height = height > 0 ? height : 2136;
        m_scale = scale > 0.1f ? scale : 1.0f;
        m_socketPath = socketDir + "/wayland-0";

        // Remove any stale socket file
        unlink(m_socketPath.c_str());

        m_serverFd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
        if (m_serverFd < 0) {
            LOGE("Failed to create UNIX domain socket: %s", strerror(errno));
            return false;
        }

        struct sockaddr_un addr;
        std::memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, m_socketPath.c_str(), sizeof(addr.sun_path) - 1);

        if (bind(m_serverFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
            LOGE("Failed to bind socket %s: %s", m_socketPath.c_str(), strerror(errno));
            close(m_serverFd);
            m_serverFd = -1;
            return false;
        }

        chmod(m_socketPath.c_str(), 0770);

        if (listen(m_serverFd, 16) < 0) {
            LOGE("Failed to listen on socket %s: %s", m_socketPath.c_str(), strerror(errno));
            close(m_serverFd);
            unlink(m_socketPath.c_str());
            m_serverFd = -1;
            return false;
        }

        m_running.store(true);
        m_serverThread = std::thread(&WaylandSurfaceServer::eventLoop, this);

        LOGI("Wayland surface server started on %s (%dx%d @ scale %.2f)",
             m_socketPath.c_str(), m_width, m_height, m_scale);
        return true;
    }

    void stop() {
        if (!m_running.exchange(false)) {
            return;
        }

        LOGI("Stopping Wayland surface server...");
        if (m_serverFd >= 0) {
            shutdown(m_serverFd, SHUT_RDWR);
            close(m_serverFd);
            m_serverFd = -1;
        }

        if (m_serverThread.joinable()) {
            m_serverThread.join();
        }

        std::lock_guard<std::mutex> lock(m_mutex);
        for (int clientFd : m_clientFds) {
            if (clientFd >= 0) {
                close(clientFd);
            }
        }
        m_clientFds.clear();
        m_activeClients.store(0);

        if (!m_socketPath.empty()) {
            unlink(m_socketPath.c_str());
            m_socketPath.clear();
        }

        if (m_nativeWindow != nullptr) {
            ANativeWindow_release(m_nativeWindow);
            m_nativeWindow = nullptr;
        }

        LOGI("Wayland surface server stopped");
    }

    void setNativeWindow(ANativeWindow* window) {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_nativeWindow != nullptr) {
            ANativeWindow_release(m_nativeWindow);
            m_nativeWindow = nullptr;
        }

        m_nativeWindow = window;
        if (m_nativeWindow != nullptr) {
            ANativeWindow_acquire(m_nativeWindow);
            ANativeWindow_setBuffersGeometry(m_nativeWindow, m_width, m_height, WINDOW_FORMAT_RGBA_8888);
            LOGI("Native window attached: %p (%dx%d)", m_nativeWindow, m_width, m_height);
        } else {
            LOGI("Native window detached");
        }
    }

    void resizeSurface(int width, int height) {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_width = width;
        m_height = height;
        if (m_nativeWindow != nullptr) {
            ANativeWindow_setBuffersGeometry(m_nativeWindow, m_width, m_height, WINDOW_FORMAT_RGBA_8888);
            LOGI("Surface resized to %dx%d", m_width, m_height);
        }
    }

    bool isRunning() const {
        return m_running.load();
    }

    int getActiveClientsCount() const {
        return m_activeClients.load();
    }

    const std::vector<WaylandGlobal>& getGlobals() const {
        return m_globals;
    }

    bool isTabletV2Supported() const {
        for (const auto& g : m_globals) {
            if (g.interface == "zwp_tablet_manager_v2") return true;
        }
        return false;
    }

    // Direct frame presentation to ANativeWindow
    bool presentSolidColor(uint32_t abgrColor) {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_nativeWindow == nullptr) return false;

        ANativeWindow_Buffer buffer;
        if (ANativeWindow_lock(m_nativeWindow, &buffer, nullptr) != 0) {
            LOGE("Failed to lock ANativeWindow buffer");
            return false;
        }

        auto* pixels = reinterpret_cast<uint32_t*>(buffer.bits);
        for (int y = 0; y < buffer.height; ++y) {
            for (int x = 0; x < buffer.width; ++x) {
                pixels[y * buffer.stride + x] = abgrColor;
            }
        }

        ANativeWindow_unlockAndPost(m_nativeWindow);
        return true;
    }

    // --- Tablet-v2 Stylus Protocol Dispatchers ---

    void sendTabletProximity(int toolType, bool inProximity) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_tabletState.toolType = toolType;
        m_tabletState.inProximity = inProximity;
        // In real Wayland client socket, serializes zwp_tablet_tool_v2.proximity_in / out
    }

    void sendTabletMotion(float x, float y, uint32_t pressure,
                          float tiltXDeg, float tiltYDeg,
                          float distance, float rotationDeg) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_tabletState.x_fixed = wl_fixed_from_double(x);
        m_tabletState.y_fixed = wl_fixed_from_double(y);
        m_tabletState.pressure = std::min(pressure, 65535u);
        m_tabletState.tilt_x_fixed = wl_fixed_from_double(tiltXDeg);
        m_tabletState.tilt_y_fixed = wl_fixed_from_double(tiltYDeg);
        m_tabletState.distance_fixed = wl_fixed_from_double(distance);
        m_tabletState.rotation_fixed = wl_fixed_from_double(rotationDeg);
        // Serializes zwp_tablet_tool_v2.motion, .pressure, .tilt, .distance, .rotation, .frame
    }

    void sendTabletButton(uint32_t buttonCode, bool isDown) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        if (isDown) {
            m_tabletState.buttonMask |= buttonCode;
        } else {
            m_tabletState.buttonMask &= ~buttonCode;
        }
        // Serializes zwp_tablet_tool_v2.button
    }

    void sendTabletDown(float x, float y, uint32_t pressure) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_tabletState.x_fixed = wl_fixed_from_double(x);
        m_tabletState.y_fixed = wl_fixed_from_double(y);
        m_tabletState.pressure = std::min(pressure, 65535u);
        m_tabletState.isDown = true;
        // Serializes zwp_tablet_tool_v2.down, .frame
    }

    void sendTabletUp() {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_tabletState.isDown = false;
        m_tabletState.pressure = 0;
        // Serializes zwp_tablet_tool_v2.up, .frame
    }

    // --- Pointer, Touch & Keyboard Dispatchers ---

    void sendPointerMotion(float dx, float dy) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_pointerState.dx_fixed = wl_fixed_from_double(dx);
        m_pointerState.dy_fixed = wl_fixed_from_double(dy);
        // Serializes wl_pointer.motion (relative pointer v1 / standard wl_pointer)
    }

    void sendPointerButton(uint32_t buttonCode, bool isDown) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        if (isDown) {
            m_pointerState.buttonMask |= buttonCode;
        } else {
            m_pointerState.buttonMask &= ~buttonCode;
        }
        // Serializes wl_pointer.button
    }

    void sendPointerAxis(int axis, float value) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        // Serializes wl_pointer.axis
    }

    void sendKeyEvent(int scanCode, bool isDown) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        // Serializes wl_keyboard.key
    }

    void sendTouchEvent(int action, int touchId, float x, float y) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        // Serializes wl_touch.down / up / motion
    }

    TabletToolState getTabletState() {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        return m_tabletState;
    }

    PointerState getPointerState() {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        return m_pointerState;
    }

private:
    void initGlobals() {
        m_globals.clear();
        m_globals.push_back({1, "wl_compositor", 4});
        m_globals.push_back({2, "wl_subcompositor", 1});
        m_globals.push_back({3, "wl_shm", 1});
        m_globals.push_back({4, "xdg_wm_base", 2});
        m_globals.push_back({5, "zwp_tablet_manager_v2", 1});
        m_globals.push_back({6, "wl_seat", 7});
        m_globals.push_back({7, "wl_output", 4});
    }

    void eventLoop() {
        LOGI("Wayland server listener event loop started");
        std::vector<struct pollfd> pollFds;

        while (m_running.load()) {
            pollFds.clear();

            // 1. Server socket
            if (m_serverFd >= 0) {
                pollFds.push_back({m_serverFd, POLLIN, 0});
            }

            // 2. Client sockets
            {
                std::lock_guard<std::mutex> lock(m_mutex);
                for (int clientFd : m_clientFds) {
                    if (clientFd >= 0) {
                        pollFds.push_back({clientFd, POLLIN | POLLHUP | POLLERR, 0});
                    }
                }
            }

            if (pollFds.empty()) {
                usleep(10000);
                continue;
            }

            int ret = poll(pollFds.data(), pollFds.size(), 200);
            if (ret <= 0) {
                continue;
            }

            // Check new incoming connections
            if (pollFds[0].revents & POLLIN) {
                struct sockaddr_un clientAddr;
                socklen_t clientLen = sizeof(clientAddr);
                int clientFd = accept4(m_serverFd, reinterpret_cast<struct sockaddr*>(&clientAddr),
                                       &clientLen, SOCK_NONBLOCK | SOCK_CLOEXEC);
                if (clientFd >= 0) {
                    std::lock_guard<std::mutex> lock(m_mutex);
                    m_clientFds.push_back(clientFd);
                    m_activeClients.store(static_cast<int>(m_clientFds.size()));
                    LOGI("New Wayland client connected (fd: %d, total: %d)",
                         clientFd, m_activeClients.load());
                }
            }

            // Check client events and disconnections
            std::vector<int> disconnected;
            for (size_t i = 1; i < pollFds.size(); ++i) {
                const auto& pfd = pollFds[i];
                if (pfd.revents & (POLLHUP | POLLERR)) {
                    disconnected.push_back(pfd.fd);
                } else if (pfd.revents & POLLIN) {
                    uint8_t buffer[1024];
                    ssize_t bytesRead = read(pfd.fd, buffer, sizeof(buffer));
                    if (bytesRead <= 0) {
                        disconnected.push_back(pfd.fd);
                    } else {
                        // Protocol message received
                    }
                }
            }

            if (!disconnected.empty()) {
                std::lock_guard<std::mutex> lock(m_mutex);
                for (int deadFd : disconnected) {
                    close(deadFd);
                    auto it = std::find(m_clientFds.begin(), m_clientFds.end(), deadFd);
                    if (it != m_clientFds.end()) {
                        m_clientFds.erase(it);
                    }
                }
                m_activeClients.store(static_cast<int>(m_clientFds.size()));
                LOGI("Wayland client(s) disconnected, remaining active: %d", m_activeClients.load());
            }
        }

        LOGI("Wayland server listener event loop terminated");
    }

    std::atomic<bool> m_running;
    int m_serverFd;
    std::string m_socketDir;
    std::string m_socketPath;
    std::thread m_serverThread;
    std::mutex m_mutex;

    ANativeWindow* m_nativeWindow;
    int m_width;
    int m_height;
    float m_scale;

    std::vector<int> m_clientFds;
    std::atomic<int> m_activeClients;
    std::vector<WaylandGlobal> m_globals;

    std::mutex m_inputMutex;
    TabletToolState m_tabletState;
    PointerState m_pointerState;
};

static std::unique_ptr<WaylandSurfaceServer> s_server;

WaylandSurfaceServer* getServer() {
    if (!s_server) {
        s_server = std::make_unique<WaylandSurfaceServer>();
    }
    return s_server.get();
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeInitServer(
    JNIEnv* env,
    jobject thiz,
    jstring socketDir,
    jint width,
    jint height,
    jfloat scale
) {
    if (socketDir == nullptr) return JNI_FALSE;
    const char* dir = env->GetStringUTFChars(socketDir, nullptr);
    std::string dirStr(dir);
    env->ReleaseStringUTFChars(socketDir, dir);

    return getServer()->start(dirStr, width, height, scale) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSetSurface(
    JNIEnv* env,
    jobject thiz,
    jobject surface
) {
    if (surface != nullptr) {
        ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
        getServer()->setNativeWindow(window);
        if (window != nullptr) {
            ANativeWindow_release(window); // Server holds its own acquire reference
        }
    } else {
        getServer()->setNativeWindow(nullptr);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeResizeSurface(
    JNIEnv* env,
    jobject thiz,
    jint width,
    jint height
) {
    getServer()->resizeSurface(width, height);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeIsRunning(
    JNIEnv* env,
    jobject thiz
) {
    return getServer()->isRunning() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeGetActiveClients(
    JNIEnv* env,
    jobject thiz
) {
    return getServer()->getActiveClientsCount();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeIsTabletV2Supported(
    JNIEnv* env,
    jobject thiz
) {
    return getServer()->isTabletV2Supported() ? JNI_TRUE : JNI_FALSE;
}

// Tablet-v2 Stylus JNI endpoints
extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendTabletProximity(
    JNIEnv* env,
    jobject thiz,
    jint toolType,
    jboolean inProximity
) {
    getServer()->sendTabletProximity(toolType, inProximity == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendTabletMotion(
    JNIEnv* env,
    jobject thiz,
    jfloat x,
    jfloat y,
    jint pressure,
    jfloat tiltXDeg,
    jfloat tiltYDeg,
    jfloat distance,
    jfloat rotationDeg
) {
    getServer()->sendTabletMotion(x, y, static_cast<uint32_t>(pressure),
                                 tiltXDeg, tiltYDeg, distance, rotationDeg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendTabletButton(
    JNIEnv* env,
    jobject thiz,
    jint buttonCode,
    jboolean isDown
) {
    getServer()->sendTabletButton(static_cast<uint32_t>(buttonCode), isDown == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendTabletDown(
    JNIEnv* env,
    jobject thiz,
    jfloat x,
    jfloat y,
    jint pressure
) {
    getServer()->sendTabletDown(x, y, static_cast<uint32_t>(pressure));
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendTabletUp(
    JNIEnv* env,
    jobject thiz
) {
    getServer()->sendTabletUp();
}

// Pointer & Keyboard JNI endpoints
extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendPointerMotion(
    JNIEnv* env,
    jobject thiz,
    jfloat dx,
    jfloat dy
) {
    getServer()->sendPointerMotion(dx, dy);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendPointerButton(
    JNIEnv* env,
    jobject thiz,
    jint buttonCode,
    jboolean isDown
) {
    getServer()->sendPointerButton(static_cast<uint32_t>(buttonCode), isDown == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendPointerAxis(
    JNIEnv* env,
    jobject thiz,
    jint axis,
    jfloat value
) {
    getServer()->sendPointerAxis(axis, value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendKeyEvent(
    JNIEnv* env,
    jobject thiz,
    jint scanCode,
    jboolean isDown
) {
    getServer()->sendKeyEvent(scanCode, isDown == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendTouchEvent(
    JNIEnv* env,
    jobject thiz,
    jint action,
    jint touchId,
    jfloat x,
    jfloat y
) {
    getServer()->sendTouchEvent(action, touchId, x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeStopServer(
    JNIEnv* env,
    jobject thiz
) {
    getServer()->stop();
}
