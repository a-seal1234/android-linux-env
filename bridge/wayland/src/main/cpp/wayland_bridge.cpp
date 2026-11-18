#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer.h>

#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/sysmacros.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/file.h>
#include <sys/syscall.h>
#include <linux/dma-buf.h>
#include <time.h>
#include <dlfcn.h>

/* DRM fourcc codes used by zwp_linux_dmabuf_v1 format negotiation */
#define DRM_FORMAT_ARGB8888 0x34325241u
#define DRM_FORMAT_XRGB8888 0x34325258u
#define DRM_FORMAT_MOD_LINEAR 0x0000000000000000ull
/* Android virtual render node advertised as the linux-dmabuf main device */
#define ALW_VIRTUAL_DRM_DEV makedev(226, 128)

#ifndef __NR_memfd_create
#define __NR_memfd_create 279
#endif
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002U
#endif

#include <string>
#include <vector>
#include <unordered_map>
#include <unordered_set>
#include <thread>
#include <atomic>
#include <mutex>
#include <cstring>
#include <memory>
#include <algorithm>
#include <chrono>

#define TAG "ALW::WaylandBridgeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

inline int32_t wl_fixed_from_double(double d) {
    return static_cast<int32_t>(d * 256.0);
}

inline double wl_fixed_to_double(int32_t f) {
    return static_cast<double>(f) / 256.0;
}

// Serialization helper for outgoing Wayland protocol events
class WaylandMessageWriter {
public:
    WaylandMessageWriter(uint32_t objectId, uint16_t opcode) {
        m_buf.resize(8);
        *reinterpret_cast<uint32_t*>(&m_buf[0]) = objectId;
        *reinterpret_cast<uint16_t*>(&m_buf[4]) = opcode;
        *reinterpret_cast<uint16_t*>(&m_buf[6]) = 8;
    }

    void putUint32(uint32_t val) {
        size_t offset = m_buf.size();
        m_buf.resize(offset + 4);
        *reinterpret_cast<uint32_t*>(&m_buf[offset]) = val;
        updateSize();
    }

    void putInt32(int32_t val) {
        size_t offset = m_buf.size();
        m_buf.resize(offset + 4);
        *reinterpret_cast<int32_t*>(&m_buf[offset]) = val;
        updateSize();
    }

    void putFixed(int32_t fixed) {
        putInt32(fixed);
    }

    void putString(const std::string& str) {
        uint32_t lenWithNull = static_cast<uint32_t>(str.length() + 1);
        putUint32(lenWithNull);
        size_t padded = (lenWithNull + 3) & ~3;
        size_t offset = m_buf.size();
        m_buf.resize(offset + padded, 0);
        std::memcpy(&m_buf[offset], str.c_str(), str.length());
        updateSize();
    }

    void putArray(const void* data, size_t size) {
        putUint32(static_cast<uint32_t>(size));
        if (size > 0 && data != nullptr) {
            size_t padded = (size + 3) & ~3;
            size_t offset = m_buf.size();
            m_buf.resize(offset + padded, 0);
            std::memcpy(&m_buf[offset], data, size);
            updateSize();
        }
    }

    const std::vector<uint8_t>& data() const { return m_buf; }

private:
    void updateSize() {
        *reinterpret_cast<uint16_t*>(&m_buf[6]) = static_cast<uint16_t>(m_buf.size());
    }
    std::vector<uint8_t> m_buf;
};

// Deserialization helper for incoming Wayland protocol requests
class WaylandMessageReader {
public:
    WaylandMessageReader(const uint8_t* data, size_t size)
        : m_data(data), m_size(size), m_offset(0) {}

    bool hasRemaining(size_t bytes) const {
        return m_offset + bytes <= m_size;
    }

    uint32_t readUint32() {
        if (!hasRemaining(4)) return 0;
        uint32_t val = *reinterpret_cast<const uint32_t*>(&m_data[m_offset]);
        m_offset += 4;
        return val;
    }

    int32_t readInt32() {
        if (!hasRemaining(4)) return 0;
        int32_t val = *reinterpret_cast<const int32_t*>(&m_data[m_offset]);
        m_offset += 4;
        return val;
    }

    std::string readString() {
        uint32_t len = readUint32();
        if (len == 0 || !hasRemaining(len)) return "";
        size_t padded = (len + 3) & ~3;
        if (!hasRemaining(padded)) return "";
        std::string str(reinterpret_cast<const char*>(&m_data[m_offset]), len > 0 ? len - 1 : 0);
        m_offset += padded;
        return str;
    }

    size_t offset() const { return m_offset; }
    size_t size() const { return m_size; }

private:
    const uint8_t* m_data;
    size_t m_size;
    size_t m_offset;
};

struct WaylandGlobal {
    uint32_t name;
    std::string interface;
    uint32_t version;
};

struct ShmPoolStorage {
    int fd = -1;
    int32_t size = 0;
    uint8_t* mappedData = nullptr;
    std::vector<uint8_t> localData;

    ~ShmPoolStorage() {
        if (mappedData != nullptr && mappedData != localData.data() && size > 0) {
            munmap(mappedData, size);
            mappedData = nullptr;
        }
        if (fd >= 0) {
            close(fd);
            fd = -1;
        }
    }
};

struct ShmPool {
    uint32_t poolId = 0;
    std::shared_ptr<ShmPoolStorage> storage;
};

struct ShmBuffer {
    uint32_t bufferId = 0;
    std::shared_ptr<ShmPoolStorage> storage;
    int32_t offset = 0;
    int32_t width = 0;
    int32_t height = 0;
    int32_t stride = 0;
    uint32_t format = 0;
};

/* One plane of a client-provided DMA-BUF (zwp_linux_buffer_params_v1.add) */
struct DmabufPlane {
    int fd = -1;
    uint32_t planeIdx = 0;
    uint32_t offset = 0;
    uint32_t stride = 0;
    uint64_t modifier = 0;
};

/* Imported DMA-BUF backed wl_buffer (zwp_linux_dmabuf_v1 v4) */
struct DmabufBuffer {
    uint32_t bufferId = 0;
    int32_t width = 0;
    int32_t height = 0;
    uint32_t format = 0;
    uint32_t flags = 0;
    std::vector<DmabufPlane> planes;

    ~DmabufBuffer() {
        for (auto& plane : planes) {
            if (plane.fd >= 0) {
                close(plane.fd);
                plane.fd = -1;
            }
        }
    }
};

/* Accumulating params object between add() and create_immediate()/create() */
struct DmabufParams {
    uint32_t id = 0;
    std::vector<DmabufPlane> planes;
};

struct DmabufFeedback {
    uint32_t id = 0;
};

struct ClientSurface {
    uint32_t surfaceId;
    uint32_t attachedBufferId = 0;
    std::vector<uint32_t> frameCallbacks;
    std::vector<uint32_t> presentationFeedbacks;
    bool isCursor = false;
    bool isXdgSurface = false;
    bool enteredOutput = false;
};

struct XdgPositioner {
    uint32_t id = 0;
};

struct XdgToplevel {
    uint32_t id = 0;
    uint32_t surfaceId = 0;
    std::string title;
    std::string appId;
    bool configured = false;
};

struct XdgPopup {
    uint32_t id = 0;
    uint32_t surfaceId = 0;
};

struct XdgSurface {
    uint32_t id = 0;
    uint32_t surfaceId = 0;
    uint32_t toplevelId = 0;
    uint32_t popupId = 0;
    bool configured = false;
    uint32_t lastConfigureSerial = 0;
};

struct Subsurface {
    uint32_t id = 0;
    uint32_t surfaceId = 0;
    uint32_t parentId = 0;
};

struct ToplevelDecoration {
    uint32_t id = 0;
    uint32_t toplevelId = 0;
    uint32_t mode = 1; // 1 = client-side
};

struct WaylandClient {
    int fd = -1;
    uint32_t registryId = 0;
    uint32_t shmId = 0;
    uint32_t seatId = 0;
    uint32_t outputId = 0;
    uint32_t compositorId = 0;
    uint32_t subcompositorId = 0;
    uint32_t xdgWmBaseId = 0;
    uint32_t pointerId = 0;
    uint32_t keyboardId = 0;
    uint32_t touchId = 0;
    uint32_t tabletManagerId = 0;
    uint32_t decorationManagerId = 0;
    uint32_t dataDeviceManagerId = 0;
    uint32_t singlePixelBufferManagerId = 0;
    uint32_t viewporterId = 0;
    uint32_t pointerConstraintsId = 0;
    uint32_t presentationId = 0;
    uint32_t toplevelIconManagerId = 0;
    uint32_t shortcutsInhibitManagerId = 0;
    uint32_t dmabufId = 0;

    uint32_t activeSurfaceId = 0;
    bool pointerEntered = false;
    bool keyboardEntered = false;

    std::vector<uint8_t> recvBuffer;
    int pendingFd = -1;

    std::unordered_map<uint32_t, ShmPool> shmPools;
    std::unordered_map<uint32_t, ShmBuffer> buffers;
    std::unordered_map<uint32_t, std::shared_ptr<DmabufBuffer>> dmabufBuffers;
    std::unordered_map<uint32_t, DmabufParams> dmabufParams;
    std::unordered_set<uint32_t> dmabufFeedbacks;
    std::unordered_map<uint32_t, ClientSurface> surfaces;
    std::unordered_map<uint32_t, XdgPositioner> xdgPositioners;
    std::unordered_map<uint32_t, XdgSurface> xdgSurfaces;
    std::unordered_map<uint32_t, XdgToplevel> xdgToplevels;
    std::unordered_map<uint32_t, XdgPopup> xdgPopups;
    std::unordered_map<uint32_t, Subsurface> subsurfaces;
    std::unordered_map<uint32_t, ToplevelDecoration> decorations;
    std::unordered_set<uint32_t> viewports;
    std::unordered_set<uint32_t> lockedPointers;
    std::unordered_set<uint32_t> confinedPointers;
    std::unordered_set<uint32_t> dataSources;
    std::unordered_set<uint32_t> dataDevices;

    void cleanup() {
        activeSurfaceId = 0;
        pointerEntered = false;
        keyboardEntered = false;
        singlePixelBufferManagerId = 0;
        viewporterId = 0;
        pointerConstraintsId = 0;
        presentationId = 0;
        toplevelIconManagerId = 0;
        shortcutsInhibitManagerId = 0;
        dmabufId = 0;
        shmPools.clear();
        buffers.clear();
        dmabufBuffers.clear();
        dmabufParams.clear();
        dmabufFeedbacks.clear();
        surfaces.clear();
        xdgPositioners.clear();
        xdgSurfaces.clear();
        xdgToplevels.clear();
        xdgPopups.clear();
        subsurfaces.clear();
        decorations.clear();
        viewports.clear();
        lockedPointers.clear();
        confinedPointers.clear();
        dataSources.clear();
        dataDevices.clear();
        if (pendingFd >= 0) {
            close(pendingFd);
            pendingFd = -1;
        }
        if (fd >= 0) {
            close(fd);
            fd = -1;
        }
    }
};

struct TabletToolState {
    int toolType = 1;
    bool inProximity = false;
    int32_t x_fixed = 0;
    int32_t y_fixed = 0;
    uint32_t pressure = 0;
    int32_t distance_fixed = 0;
    int32_t tilt_x_fixed = 0;
    int32_t tilt_y_fixed = 0;
    int32_t rotation_fixed = 0;
    bool isDown = false;
    uint32_t buttonMask = 0;
};

struct PointerState {
    float x = 0.0f;
    float y = 0.0f;
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
          m_activeClients(0),
          m_serial(1) {
        initGlobals();
    }

    ~WaylandSurfaceServer() {
        stop();
    }

    bool start(const std::string& socketDir, int width, int height, float scale) {
        std::lock_guard<std::recursive_mutex> lock(m_mutex);
        if (m_running.load()) {
            LOGW("Wayland server already running");
            return true;
        }

        m_socketDir = socketDir;
        m_width = width > 0 ? width : 3200;
        m_height = height > 0 ? height : 2136;
        m_scale = scale > 0.1f ? scale : 1.75f;
        m_socketPath = socketDir + "/wayland-0";
        m_lockPath = m_socketPath + ".lock";

        m_lockFd = open(m_lockPath.c_str(), O_CREAT | O_CLOEXEC | O_RDWR, 0660);
        if (m_lockFd >= 0) {
            if (flock(m_lockFd, LOCK_EX | LOCK_NB) < 0) {
                LOGW("Could not acquire exclusive flock on %s: %s", m_lockPath.c_str(), strerror(errno));
            }
        }

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

        LOGI("Wayland protocol server started on %s (%dx%d @ scale %.2f)",
             m_socketPath.c_str(), m_width, m_height, m_scale);
        return true;
    }

    void stop() {
        if (!m_running.exchange(false)) return;

        LOGI("Stopping Wayland surface server...");
        if (m_serverFd >= 0) {
            shutdown(m_serverFd, SHUT_RDWR);
            close(m_serverFd);
            m_serverFd = -1;
        }

        if (m_serverThread.joinable()) {
            m_serverThread.join();
        }

        std::lock_guard<std::recursive_mutex> lock(m_mutex);
        for (auto& pair : m_clients) {
            pair.second.cleanup();
        }
        m_clients.clear();
        m_activeClients.store(0);

        if (m_lockFd >= 0) {
            flock(m_lockFd, LOCK_UN);
            close(m_lockFd);
            m_lockFd = -1;
        }
        if (!m_lockPath.empty()) {
            unlink(m_lockPath.c_str());
            m_lockPath.clear();
        }

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
        std::lock_guard<std::recursive_mutex> lock(m_mutex);
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
        std::lock_guard<std::recursive_mutex> lock(m_mutex);
        m_width = width;
        m_height = height;
        if (m_nativeWindow != nullptr) {
            ANativeWindow_setBuffersGeometry(m_nativeWindow, m_width, m_height, WINDOW_FORMAT_RGBA_8888);
            LOGI("Surface resized to %dx%d", m_width, m_height);
        }
    }

    bool isRunning() const { return m_running.load(); }
    int getActiveClientsCount() const { return m_activeClients.load(); }

    bool isTabletV2Supported() const {
        for (const auto& g : m_globals) {
            if (g.interface == "zwp_tablet_manager_v2") return true;
        }
        return false;
    }

    // Direct pixel blit to ANativeWindow buffer with format-aware color channel mapping & aspect scaling
    void blitBufferToWindow(const uint8_t* src, int srcWidth, int srcHeight, int srcStride, uint32_t format = 0) {
        std::lock_guard<std::recursive_mutex> lock(m_mutex);
        if (m_nativeWindow == nullptr || src == nullptr) return;

        ANativeWindow_Buffer outBuffer;
        if (ANativeWindow_lock(m_nativeWindow, &outBuffer, nullptr) != 0) {
            LOGW("ANativeWindow_lock failed during frame blit");
            return;
        }

        static int s_frameLogCount = 0;
        if (++s_frameLogCount % 60 == 1) {
            LOGI("Blitted frame #%d: %dx%d (stride=%d, fmt=%u) -> ANativeWindow %dx%d (stride=%d)",
                 s_frameLogCount, srcWidth, srcHeight, srcStride, format, outBuffer.width, outBuffer.height, outBuffer.stride);
        }

        auto* dstPixels = reinterpret_cast<uint8_t*>(outBuffer.bits);
        if (srcWidth == outBuffer.width && srcHeight == outBuffer.height) {
            for (int y = 0; y < outBuffer.height; ++y) {
                const uint32_t* srcRow = reinterpret_cast<const uint32_t*>(src + y * srcStride);
                uint32_t* dstRow = reinterpret_cast<uint32_t*>(dstPixels + y * outBuffer.stride * 4);

                if (format == 0) {
                    // WL_SHM_FORMAT_ARGB8888 -> Android RGBA_8888: swap byte 0 (B) and byte 2 (R)
                    for (int x = 0; x < outBuffer.width; ++x) {
                        uint32_t p = srcRow[x];
                        dstRow[x] = ((p & 0x00FF0000) >> 16) |
                                    (p & 0x0000FF00)         |
                                    ((p & 0x000000FF) << 16) |
                                    (p & 0xFF000000);
                    }
                } else if (format == 1) {
                    // WL_SHM_FORMAT_XRGB8888 -> Android RGBA_8888: swap B/R and force Alpha=0xFF
                    for (int x = 0; x < outBuffer.width; ++x) {
                        uint32_t p = srcRow[x];
                        dstRow[x] = ((p & 0x00FF0000) >> 16) |
                                    (p & 0x0000FF00)         |
                                    ((p & 0x000000FF) << 16) |
                                    0xFF000000;
                    }
                } else {
                    std::memcpy(dstRow, srcRow, static_cast<size_t>(outBuffer.width) * 4);
                }
            }
        } else {
            // Nearest-neighbor scaled blit so any guest resolution fills ANativeWindow
            for (int y = 0; y < outBuffer.height; ++y) {
                int srcY = (y * srcHeight) / outBuffer.height;
                const uint32_t* srcRow = reinterpret_cast<const uint32_t*>(src + srcY * srcStride);
                uint32_t* dstRow = reinterpret_cast<uint32_t*>(dstPixels + y * outBuffer.stride * 4);

                for (int x = 0; x < outBuffer.width; ++x) {
                    int srcX = (x * srcWidth) / outBuffer.width;
                    uint32_t p = srcRow[srcX];

                    if (format == 0) {
                        dstRow[x] = ((p & 0x00FF0000) >> 16) |
                                    (p & 0x0000FF00)         |
                                    ((p & 0x000000FF) << 16) |
                                    (p & 0xFF000000);
                    } else if (format == 1) {
                        dstRow[x] = ((p & 0x00FF0000) >> 16) |
                                    (p & 0x0000FF00)         |
                                    ((p & 0x000000FF) << 16) |
                                    0xFF000000;
                    } else {
                        dstRow[x] = p;
                    }
                }
            }
        }

        ANativeWindow_unlockAndPost(m_nativeWindow);

        auto now = std::chrono::steady_clock::now();
        std::chrono::duration<double, std::milli> frameDelta = now - m_lastFrameTime;
        m_lastFrameTime = now;
        double frameMs = frameDelta.count();

        m_frameCount++;
        m_pacingFrameCount++;
        m_totalFrameTimeMs += frameMs;
        if (frameMs < m_minFrameTimeMs) m_minFrameTimeMs = frameMs;
        if (frameMs > m_maxFrameTimeMs) m_maxFrameTimeMs = frameMs;

        std::chrono::duration<double, std::milli> logDelta = now - m_lastPacingLogTime;
        if (logDelta.count() >= 1000.0 && m_pacingFrameCount > 0) {
            double elapsedSec = logDelta.count() / 1000.0;
            double fps = static_cast<double>(m_pacingFrameCount) / elapsedSec;
            double avgFrameMs = m_totalFrameTimeMs / m_pacingFrameCount;
            LOGI("144Hz Frame Pacing: %llu frames (total %llu) in %.1f ms | FPS: %.1f | Frame Time: avg=%.2f ms, min=%.2f ms, max=%.2f ms",
                 static_cast<unsigned long long>(m_pacingFrameCount),
                 static_cast<unsigned long long>(m_frameCount),
                 logDelta.count(), fps, avgFrameMs, m_minFrameTimeMs, m_maxFrameTimeMs);
            m_pacingFrameCount = 0;
            m_totalFrameTimeMs = 0.0;
            m_minFrameTimeMs = 9999.0;
            m_maxFrameTimeMs = 0.0;
            m_lastPacingLogTime = now;
        }
    }

    // --- Tablet-v2 Stylus Dispatchers ---
    void sendTabletProximity(int toolType, bool inProximity) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_tabletState.toolType = toolType;
        m_tabletState.inProximity = inProximity;
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
    }

    void sendTabletButton(uint32_t buttonCode, bool isDown) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        if (isDown) m_tabletState.buttonMask |= buttonCode;
        else m_tabletState.buttonMask &= ~buttonCode;
    }

    void sendTabletDown(float x, float y, uint32_t pressure) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_tabletState.x_fixed = wl_fixed_from_double(x);
        m_tabletState.y_fixed = wl_fixed_from_double(y);
        m_tabletState.pressure = std::min(pressure, 65535u);
        m_tabletState.isDown = true;
    }

    void sendTabletUp() {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_tabletState.isDown = false;
        m_tabletState.pressure = 0;
    }

    // --- Pointer & Keyboard Dispatchers ---
    void dispatchPointerMotionInternal() {
        uint32_t now = nowMillis();
        int32_t x_fixed = wl_fixed_from_double(m_pointerState.x);
        int32_t y_fixed = wl_fixed_from_double(m_pointerState.y);

        for (auto& pair : m_clients) {
            auto& client = pair.second;
            if (client.pointerId > 0 && client.activeSurfaceId > 0) {
                if (!client.pointerEntered) {
                    WaylandMessageWriter enter(client.pointerId, 0); // enter
                    enter.putUint32(nextSerial());
                    enter.putUint32(client.activeSurfaceId);
                    enter.putInt32(x_fixed);
                    enter.putInt32(y_fixed);
                    sendPacket(client.fd, enter);
                    client.pointerEntered = true;
                } else {
                    WaylandMessageWriter motion(client.pointerId, 2); // motion
                    motion.putUint32(now);
                    motion.putInt32(x_fixed);
                    motion.putInt32(y_fixed);
                    sendPacket(client.fd, motion);
                }
                WaylandMessageWriter frame(client.pointerId, 5); // frame
                sendPacket(client.fd, frame);
            }
        }
    }

    void sendPointerAbsolute(float x, float y) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        std::lock_guard<std::recursive_mutex> clientLock(m_mutex);

        m_pointerState.x = std::clamp(x, 0.0f, static_cast<float>(m_width));
        m_pointerState.y = std::clamp(y, 0.0f, static_cast<float>(m_height));
        dispatchPointerMotionInternal();
    }

    void sendPointerMotion(float dx, float dy) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        std::lock_guard<std::recursive_mutex> clientLock(m_mutex);

        m_pointerState.x = std::clamp(m_pointerState.x + dx, 0.0f, static_cast<float>(m_width));
        m_pointerState.y = std::clamp(m_pointerState.y + dy, 0.0f, static_cast<float>(m_height));
        dispatchPointerMotionInternal();
    }

    void sendPointerButton(uint32_t buttonCode, bool isDown) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        std::lock_guard<std::recursive_mutex> clientLock(m_mutex);

        // Map button code to Linux evdev
        uint32_t linuxBtn = 272; // default BTN_LEFT
        if (buttonCode == 1 || buttonCode == 272) linuxBtn = 272;      // BTN_LEFT
        else if (buttonCode == 2 || buttonCode == 273) linuxBtn = 273; // BTN_RIGHT
        else if (buttonCode == 4 || buttonCode == 274) linuxBtn = 274; // BTN_MIDDLE
        else if (buttonCode == 8 || buttonCode == 275) linuxBtn = 275; // BTN_SIDE
        else if (buttonCode == 16 || buttonCode == 276) linuxBtn = 276;// BTN_EXTRA
        else linuxBtn = buttonCode;

        if (isDown) m_pointerState.buttonMask |= buttonCode;
        else m_pointerState.buttonMask &= ~buttonCode;

        uint32_t now = nowMillis();
        uint32_t btnSerial = nextSerial();

        for (auto& pair : m_clients) {
            auto& client = pair.second;
            if (client.pointerId > 0 && client.activeSurfaceId > 0) {
                if (!client.pointerEntered) {
                    WaylandMessageWriter enter(client.pointerId, 0);
                    enter.putUint32(nextSerial());
                    enter.putUint32(client.activeSurfaceId);
                    enter.putInt32(wl_fixed_from_double(m_pointerState.x));
                    enter.putInt32(wl_fixed_from_double(m_pointerState.y));
                    sendPacket(client.fd, enter);
                    client.pointerEntered = true;
                }
                WaylandMessageWriter btn(client.pointerId, 3); // button
                btn.putUint32(btnSerial);
                btn.putUint32(now);
                btn.putUint32(linuxBtn);
                btn.putUint32(isDown ? 1 : 0);
                sendPacket(client.fd, btn);

                WaylandMessageWriter frame(client.pointerId, 5); // frame
                sendPacket(client.fd, frame);
            }
        }
    }

    void sendPointerAxis(int axis, float value) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        std::lock_guard<std::recursive_mutex> clientLock(m_mutex);

        uint32_t now = nowMillis();
        int32_t fixedVal = wl_fixed_from_double(-value * 10.0);

        for (auto& pair : m_clients) {
            auto& client = pair.second;
            if (client.pointerId > 0) {
                WaylandMessageWriter ax(client.pointerId, 4); // axis
                ax.putUint32(now);
                ax.putUint32(static_cast<uint32_t>(axis));
                ax.putInt32(fixedVal);
                sendPacket(client.fd, ax);

                WaylandMessageWriter frame(client.pointerId, 5); // frame
                sendPacket(client.fd, frame);
            }
        }
    }

    void sendKeyEvent(int scanCode, bool isDown) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        std::lock_guard<std::recursive_mutex> clientLock(m_mutex);

        uint32_t modBit = 0;
        if (scanCode == 42 || scanCode == 54) modBit = (1 << 0);       // Shift
        else if (scanCode == 58) modBit = (1 << 1);                    // CapsLock
        else if (scanCode == 29 || scanCode == 97) modBit = (1 << 2);  // Ctrl
        else if (scanCode == 56 || scanCode == 100) modBit = (1 << 3); // Alt
        else if (scanCode == 125 || scanCode == 126) modBit = (1 << 6);// Super / Meta

        if (modBit != 0) {
            if (scanCode == 58) {
                if (isDown) m_lockedModifiers ^= modBit;
            } else {
                if (isDown) m_depressedModifiers |= modBit;
                else m_depressedModifiers &= ~modBit;
            }
        }

        uint32_t now = nowMillis();
        uint32_t keySerial = nextSerial();

        for (auto& pair : m_clients) {
            auto& client = pair.second;
            if (client.keyboardId > 0) {
                if (!client.keyboardEntered && client.activeSurfaceId > 0) {
                    WaylandMessageWriter enter(client.keyboardId, 1);
                    enter.putUint32(nextSerial());
                    enter.putUint32(client.activeSurfaceId);
                    enter.putUint32(0); // empty keys array length
                    sendPacket(client.fd, enter);
                    client.keyboardEntered = true;
                }

                WaylandMessageWriter keyMsg(client.keyboardId, 3); // key
                keyMsg.putUint32(keySerial);
                keyMsg.putUint32(now);
                keyMsg.putUint32(static_cast<uint32_t>(scanCode));
                keyMsg.putUint32(isDown ? 1 : 0);
                sendPacket(client.fd, keyMsg);

                WaylandMessageWriter modMsg(client.keyboardId, 4); // modifiers
                modMsg.putUint32(nextSerial());
                modMsg.putUint32(m_depressedModifiers);
                modMsg.putUint32(0);
                modMsg.putUint32(m_lockedModifiers);
                modMsg.putUint32(0);
                sendPacket(client.fd, modMsg);
            }
        }
    }

    void sendTouchEvent(int action, int touchId, float x, float y) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        std::lock_guard<std::recursive_mutex> clientLock(m_mutex);

        uint32_t now = nowMillis();
        int32_t x_fixed = wl_fixed_from_double(x);
        int32_t y_fixed = wl_fixed_from_double(y);

        for (auto& pair : m_clients) {
            auto& client = pair.second;
            if (client.touchId > 0 && client.activeSurfaceId > 0) {
                if (action == 0) { // ACTION_DOWN
                    WaylandMessageWriter down(client.touchId, 0);
                    down.putUint32(nextSerial());
                    down.putUint32(now);
                    down.putUint32(client.activeSurfaceId);
                    down.putInt32(touchId);
                    down.putInt32(x_fixed);
                    down.putInt32(y_fixed);
                    sendPacket(client.fd, down);
                } else if (action == 2) { // ACTION_MOVE
                    WaylandMessageWriter motion(client.touchId, 2);
                    motion.putUint32(now);
                    motion.putInt32(touchId);
                    motion.putInt32(x_fixed);
                    motion.putInt32(y_fixed);
                    sendPacket(client.fd, motion);
                } else if (action == 1 || action == 3) { // ACTION_UP or CANCEL
                    WaylandMessageWriter up(client.touchId, 1);
                    up.putUint32(nextSerial());
                    up.putUint32(now);
                    up.putInt32(touchId);
                    sendPacket(client.fd, up);
                }
                WaylandMessageWriter frame(client.touchId, 3);
                sendPacket(client.fd, frame);
            }
        }
    }

private:
    void initGlobals() {
        m_globals.clear();
        m_globals.push_back({1, "wl_compositor", 6});
        m_globals.push_back({2, "wl_subcompositor", 1});
        m_globals.push_back({3, "wl_shm", 1});
        m_globals.push_back({4, "xdg_wm_base", 6});
        m_globals.push_back({5, "zwp_tablet_manager_v2", 1});
        m_globals.push_back({6, "wl_seat", 9});
        m_globals.push_back({7, "wl_output", 4});
        m_globals.push_back({8, "wl_data_device_manager", 3});
        m_globals.push_back({9, "zxdg_decoration_manager_v1", 1});
        m_globals.push_back({10, "wp_single_pixel_buffer_manager_v1", 1});
        m_globals.push_back({11, "wp_viewporter", 1});
        m_globals.push_back({12, "zwp_pointer_constraints_v1", 1});
        // NOTE (TASK-023): KWin 6.8 and Aquamarine require zwp_linux_dmabuf_v1 (plus a DRM
        // render node) before they will use any compositing at all, so these optional globals
        // are not the cause of their refusal to start. wp_presentation is required by KWin's
        // nested backend; xdg_toplevel_icon_manager_v1 was bisected harmless and is kept.
        m_globals.push_back({13, "wp_presentation", 1});
        m_globals.push_back({14, "xdg_toplevel_icon_manager_v1", 1});
        m_globals.push_back({15, "zwp_keyboard_shortcuts_inhibit_manager_v1", 1});
        m_globals.push_back({16, "zwp_linux_dmabuf_v1", 4});
    }

    uint32_t nextSerial() {
        return m_serial.fetch_add(1);
    }

    uint32_t nowMillis() const {
        return static_cast<uint32_t>(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()
            ).count()
        );
    }

    ssize_t readClientWithFd(int fd, uint8_t* buf, size_t bufSize, int* outFd) {
        struct msghdr msg;
        std::memset(&msg, 0, sizeof(msg));
        struct iovec iov;
        iov.iov_base = buf;
        iov.iov_len = bufSize;
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;

        char cmsgBuf[CMSG_SPACE(sizeof(int))];
        std::memset(cmsgBuf, 0, sizeof(cmsgBuf));
        msg.msg_control = cmsgBuf;
        msg.msg_controllen = sizeof(cmsgBuf);

        ssize_t n = recvmsg(fd, &msg, MSG_DONTWAIT);
        if (n > 0) {
            struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
            if (cmsg && cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) {
                *outFd = *reinterpret_cast<int*>(CMSG_DATA(cmsg));
            } else {
                *outFd = -1;
            }
        }
        return n;
    }

    void sendPacket(int clientFd, const WaylandMessageWriter& writer) {
        if (clientFd >= 0 && !writer.data().empty()) {
            send(clientFd, writer.data().data(), writer.data().size(), MSG_NOSIGNAL);
        }
    }

    void sendPacketWithFd(int clientFd, const WaylandMessageWriter& writer, int fdToSend) {
        if (clientFd < 0 || writer.data().empty()) return;
        if (fdToSend < 0) {
            sendPacket(clientFd, writer);
            return;
        }

        struct msghdr msg;
        std::memset(&msg, 0, sizeof(msg));
        struct iovec iov;
        iov.iov_base = const_cast<uint8_t*>(writer.data().data());
        iov.iov_len = writer.data().size();
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;

        char cmsgBuf[CMSG_SPACE(sizeof(int))];
        std::memset(cmsgBuf, 0, sizeof(cmsgBuf));
        msg.msg_control = cmsgBuf;
        msg.msg_controllen = sizeof(cmsgBuf);

        struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int));
        *reinterpret_cast<int*>(CMSG_DATA(cmsg)) = fdToSend;

        sendmsg(clientFd, &msg, MSG_NOSIGNAL);
    }

    int createXkbKeymapFd(uint32_t* outSize) {
        const char* keymapStr =
            "xkb_keymap {\n"
            "    xkb_keycodes  { include \"xfree86+aliases(qwerty)\" };\n"
            "    xkb_types     { include \"complete\" };\n"
            "    xkb_compat    { include \"complete\" };\n"
            "    xkb_symbols   { include \"pc+us+inet(evdev)\" };\n"
            "    xkb_geometry  { include \"pc(pc105)\" };\n"
            "};\n";
        size_t len = strlen(keymapStr) + 1;
        *outSize = static_cast<uint32_t>(len);

        int fd = -1;
#if defined(__NR_memfd_create)
        fd = static_cast<int>(syscall(__NR_memfd_create, "alw_xkb_keymap", MFD_CLOEXEC | MFD_ALLOW_SEALING));
#endif
        if (fd < 0) {
            std::string tempPath = m_socketDir.empty() ? "/tmp" : m_socketDir;
            tempPath += "/.alw_keymap_XXXXXX";
            std::vector<char> pathBuf(tempPath.begin(), tempPath.end());
            pathBuf.push_back('\0');
            fd = mkstemp(pathBuf.data());
            if (fd >= 0) {
                unlink(pathBuf.data());
            }
        }
        if (fd >= 0) {
            if (ftruncate(fd, len) == 0) {
                void* ptr = mmap(nullptr, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
                if (ptr != MAP_FAILED) {
                    memcpy(ptr, keymapStr, len);
                    munmap(ptr, len);
                } else {
                    pwrite(fd, keymapStr, len, 0);
                }
            }
        }
        return fd;
    }

    // --- zwp_linux_dmabuf_v1 helpers ---

    int createDmabufFormatTableFd(uint32_t* outSize) {
        struct FormatEntry {
            uint32_t format;
            uint32_t padding;
            uint64_t modifier;
        } __attribute__((packed));
        const FormatEntry entries[2] = {
            { DRM_FORMAT_ARGB8888, 0, DRM_FORMAT_MOD_LINEAR },
            { DRM_FORMAT_XRGB8888, 0, DRM_FORMAT_MOD_LINEAR },
        };
        size_t len = sizeof(entries);
        *outSize = static_cast<uint32_t>(len);

        int fd = -1;
#if defined(__NR_memfd_create)
        fd = static_cast<int>(syscall(__NR_memfd_create, "alw_dmabuf_formats", MFD_CLOEXEC));
#endif
        if (fd < 0) return -1;
        if (ftruncate(fd, len) != 0) {
            close(fd);
            return -1;
        }
        void* ptr = mmap(nullptr, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (ptr == MAP_FAILED) {
            close(fd);
            return -1;
        }
        memcpy(ptr, entries, len);
        munmap(ptr, len);
        return fd;
    }

    // Emits the full zwp_linux_dmabuf_feedback_v1 sequence (v4).
    void sendDmabufFeedback(int clientFd, uint32_t feedbackId) {
        uint32_t tableSize = 0;
        int tableFd = createDmabufFormatTableFd(&tableSize);
        if (tableFd >= 0) {
            WaylandMessageWriter table(feedbackId, 1); // format_table(fd, size)
            table.putUint32(tableSize);
            sendPacketWithFd(clientFd, table, tableFd);
            close(tableFd);
        }

        uint64_t dev = static_cast<uint64_t>(ALW_VIRTUAL_DRM_DEV);
        WaylandMessageWriter mainDev(feedbackId, 2); // main_device(device: array of dev_t)
        mainDev.putArray(&dev, sizeof(dev));
        sendPacket(clientFd, mainDev);

        WaylandMessageWriter trancheDone(feedbackId, 3); // tranche_done()
        sendPacket(clientFd, trancheDone);

        WaylandMessageWriter trancheDev(feedbackId, 4); // tranche_target_device(device: array)
        trancheDev.putArray(&dev, sizeof(dev));
        sendPacket(clientFd, trancheDev);

        // tranche_formats: array of uint16 indices into the format table
        uint16_t indices[2] = { 0, 1 };
        WaylandMessageWriter trancheFmts(feedbackId, 5);
        trancheFmts.putArray(indices, sizeof(indices));
        sendPacket(clientFd, trancheFmts);

        WaylandMessageWriter trancheFlags(feedbackId, 6); // tranche_flags(flags)
        trancheFlags.putUint32(0);
        sendPacket(clientFd, trancheFlags);

        WaylandMessageWriter done(feedbackId, 0); // done()
        sendPacket(clientFd, done);
    }

    // Imports a client DMA-BUF and composites it onto the ANativeWindow.
    // Primary path: AHardwareBuffer import (hypervisor gralloc); fallback: mmap of
    // the linear DMA-BUF. The fallback guarantees a frame is shown even when the
    // vendor gralloc rejects a raw native_handle.
    bool importAndRenderDmabuf(const std::shared_ptr<DmabufBuffer>& buffer) {
        if (!buffer || buffer->planes.empty()) return false;
        const DmabufPlane& plane = buffer->planes[0];
        if (plane.fd < 0 || buffer->width <= 0 || buffer->height <= 0) return false;

        int blitFormat = -1;
        if (buffer->format == DRM_FORMAT_ARGB8888) blitFormat = 0;
        else if (buffer->format == DRM_FORMAT_XRGB8888) blitFormat = 1;
        if (blitFormat < 0) {
            LOGW("dmabuf: unsupported format 0x%x (only ARGB8888/XRGB8888 supported)", buffer->format);
            return false;
        }

        // Primary: import as AHardwareBuffer (zero-copy gralloc path when accepted).
        {
            // Versioned native_handle_t: { version, numFds, numInts, data[] }.
            // HyperOS gralloc may reject a raw dma-buf handle (missing private ints);
            // in that case the mmap fallback below keeps the session rendering.
            struct __attribute__((packed)) RawNativeHandle {
                int32_t version;
                int32_t numFds;
                int32_t numInts;
                int32_t fd;
            } rawHandle = { static_cast<int32_t>(sizeof(RawNativeHandle)), 1, 0, plane.fd };

            AHardwareBuffer_Desc desc = {};
            desc.width = static_cast<uint32_t>(buffer->width);
            desc.height = static_cast<uint32_t>(buffer->height);
            desc.layers = 1;
            desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            desc.usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
            desc.stride = static_cast<uint32_t>(buffer->width);

            AHardwareBuffer* ahb = nullptr;
            // AHardwareBuffer_createFromHandle() is a platform (libandroid) API, not NDK:
            // resolve it at runtime; if unavailable the mmap fallback is used.
            using CreateFromHandleFn = int (*)(const AHardwareBuffer_Desc*, const void*,
                                               uint64_t, AHardwareBuffer**);
            static CreateFromHandleFn s_createFromHandle = []() -> CreateFromHandleFn {
                void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
                if (lib == nullptr) return nullptr;
                return reinterpret_cast<CreateFromHandleFn>(
                    dlsym(lib, "AHardwareBuffer_createFromHandle"));
            }();

            int rc = -1;
            if (s_createFromHandle != nullptr) {
                rc = s_createFromHandle(
                    &desc, reinterpret_cast<const void*>(&rawHandle),
                    AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, &ahb);
            }
            if (rc == 0 && ahb != nullptr) {
                void* data = nullptr;
                int lockRc = AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                                                  -1, nullptr, &data);
                if (lockRc == 0 && data != nullptr) {
                    int stride = static_cast<int>(buffer->width) * 4;
                    const uint8_t* pixels = static_cast<const uint8_t*>(data) + plane.offset;
                    blitBufferToWindow(pixels, buffer->width, buffer->height, stride, blitFormat);
                    AHardwareBuffer_unlock(ahb, nullptr);
                    AHardwareBuffer_release(ahb);
                    static int s_ahbLog = 0;
                    if (++s_ahbLog % 60 == 1) {
                        LOGI("dmabuf: AHardwareBuffer import path active (%dx%d)", buffer->width, buffer->height);
                    }
                    return true;
                }
                AHardwareBuffer_release(ahb);
            }
            static int s_ahbFailLog = 0;
            if (++s_ahbFailLog <= 3) {
                LOGW("dmabuf: AHardwareBuffer_createFromHandle rejected (rc=%d); using mmap fallback", rc);
            }
        }

        // Fallback: mmap the linear DMA-BUF and upload into the BufferQueue.
        size_t length = static_cast<size_t>(plane.stride) * static_cast<size_t>(buffer->height)
                        + plane.offset;
        if (length == 0) length = static_cast<size_t>(buffer->width) * 4 * buffer->height;
        void* mapped = mmap(nullptr, length, PROT_READ, MAP_SHARED, plane.fd, 0);
        if (mapped == MAP_FAILED) {
            LOGW("dmabuf: mmap failed (fd=%d len=%zu): %s", plane.fd, length, strerror(errno));
            return false;
        }
        const uint8_t* pixels = static_cast<const uint8_t*>(mapped) + plane.offset;
        blitBufferToWindow(pixels, buffer->width, buffer->height,
                           static_cast<int>(plane.stride), blitFormat);
        munmap(mapped, length);

        static int s_mmapLog = 0;
        if (++s_mmapLog % 60 == 1) {
            LOGI("dmabuf: mmap fallback blit active (%dx%d stride=%u fmt=0x%x)",
                 buffer->width, buffer->height, plane.stride, buffer->format);
        }
        return true;
    }

    // Sends wl_surface.enter(wl_output) once per surface. Nested compositors such as
    // Aquamarine wait for the output enter event to latch the output scale/refresh and
    // to start driving their render loop (Hyprland's startCompositor -> .socket.sock).
    void sendSurfaceEnterIfNeeded(WaylandClient& client, ClientSurface& surface) {
        if (surface.enteredOutput || client.outputId == 0 || surface.surfaceId == 0)
            return;
        WaylandMessageWriter enter(surface.surfaceId, 0); // wl_surface.enter(output)
        enter.putUint32(client.outputId);
        sendPacket(client.fd, enter);
        surface.enteredOutput = true;
        LOGI("Sent wl_surface.enter(output=%u) to surface %u", client.outputId, surface.surfaceId);
    }

    // Process a single Wayland message from a client
    void dispatchMessage(WaylandClient& client, uint32_t objId, uint16_t opcode,
                         const uint8_t* payload, size_t payloadLen) {
        WaylandMessageReader reader(payload, payloadLen);

        // 1. wl_display requests (objId == 1)
        if (objId == 1) {
            if (opcode == 0) {
                // wl_display.sync(callback_id)
                uint32_t callbackId = reader.readUint32();
                WaylandMessageWriter reply(callbackId, 0); // wl_callback.done(serial)
                reply.putUint32(nextSerial());
                sendPacket(client.fd, reply);
            } else if (opcode == 1) {
                // wl_display.get_registry(registry_id)
                uint32_t registryId = reader.readUint32();
                client.registryId = registryId;

                // Announce all registered globals immediately
                for (const auto& g : m_globals) {
                    WaylandMessageWriter reply(registryId, 0); // wl_registry.global(name, interface, version)
                    reply.putUint32(g.name);
                    reply.putString(g.interface);
                    reply.putUint32(g.version);
                    sendPacket(client.fd, reply);
                }
            }
            return;
        }

        // 2. wl_registry requests (objId == client.registryId)
        if (client.registryId > 0 && objId == client.registryId) {
            if (opcode == 0) {
                // wl_registry.bind(name, interface, version, new_id)
                [[maybe_unused]] uint32_t name = reader.readUint32();
                std::string iface = reader.readString();
                [[maybe_unused]] uint32_t version = reader.readUint32();
                uint32_t newId = reader.readUint32();

                if (iface == "wl_shm") {
                    client.shmId = newId;
                    // Announce supported pixel formats (ARGB8888 = 0, XRGB8888 = 1)
                    WaylandMessageWriter f0(newId, 0); // wl_shm.format
                    f0.putUint32(0); // ARGB8888
                    sendPacket(client.fd, f0);

                    WaylandMessageWriter f1(newId, 0);
                    f1.putUint32(1); // XRGB8888
                    sendPacket(client.fd, f1);
                } else if (iface == "wl_compositor") {
                    client.compositorId = newId;
                } else if (iface == "wl_output") {
                    client.outputId = newId;
                    // Send output geometry (x, y, physical_w_mm, physical_h_mm, subpixel, make, model, transform)
                    WaylandMessageWriter geom(newId, 0);
                    geom.putInt32(0); geom.putInt32(0);
                    geom.putInt32(240); geom.putInt32(160);
                    geom.putInt32(0); // unknown subpixel
                    geom.putString("Xiaomi"); geom.putString("Pad 8 (3200x2136)");
                    geom.putInt32(0); // normal transform
                    sendPacket(client.fd, geom);

                    // Send output mode (flags, width, height, refresh_mHz)
                    WaylandMessageWriter mode(newId, 1);
                    mode.putUint32(0x1 | 0x2); // current | preferred
                    mode.putInt32(m_width);
                    mode.putInt32(m_height);
                    mode.putInt32(144000); // 144Hz
                    sendPacket(client.fd, mode);

                    // Send scale factor
                    WaylandMessageWriter scale(newId, 3);
                    scale.putInt32(std::max(1, static_cast<int>(m_scale)));
                    sendPacket(client.fd, scale);

                    // Send name (v4)
                    WaylandMessageWriter nameMsg(newId, 4);
                    nameMsg.putString("WL-1");
                    sendPacket(client.fd, nameMsg);

                    // Send description (v4)
                    WaylandMessageWriter descMsg(newId, 5);
                    descMsg.putString("Xiaomi Pad 8 3200x2136 144Hz");
                    sendPacket(client.fd, descMsg);

                    // Send done
                    WaylandMessageWriter done(newId, 2);
                    sendPacket(client.fd, done);
                } else if (iface == "wl_seat") {
                    client.seatId = newId;
                    // Capabilities: 1 (pointer) | 2 (keyboard) | 4 (touch)
                    WaylandMessageWriter caps(newId, 0);
                    caps.putUint32(1 | 2 | 4);
                    sendPacket(client.fd, caps);

                    WaylandMessageWriter nameMsg(newId, 1);
                    nameMsg.putString("seat0");
                    sendPacket(client.fd, nameMsg);
                } else if (iface == "xdg_wm_base") {
                    client.xdgWmBaseId = newId;
                } else if (iface == "wl_subcompositor") {
                    client.subcompositorId = newId;
                } else if (iface == "wl_data_device_manager") {
                    client.dataDeviceManagerId = newId;
                } else if (iface == "zxdg_decoration_manager_v1") {
                    client.decorationManagerId = newId;
                } else if (iface == "zwp_tablet_manager_v2") {
                    client.tabletManagerId = newId;
                } else if (iface == "wp_single_pixel_buffer_manager_v1") {
                    client.singlePixelBufferManagerId = newId;
                } else if (iface == "wp_viewporter") {
                    client.viewporterId = newId;
                } else if (iface == "zwp_pointer_constraints_v1") {
                    client.pointerConstraintsId = newId;
                } else if (iface == "wp_presentation") {
                    client.presentationId = newId;
                    WaylandMessageWriter clockMsg(newId, 0); // wp_presentation.clock_id
                    clockMsg.putUint32(1); // CLOCK_MONOTONIC
                    sendPacket(client.fd, clockMsg);
                } else if (iface == "xdg_toplevel_icon_manager_v1") {
                    client.toplevelIconManagerId = newId;
                } else if (iface == "zwp_keyboard_shortcuts_inhibit_manager_v1") {
                    client.shortcutsInhibitManagerId = newId;
                } else if (iface == "zwp_linux_dmabuf_v1") {
                    client.dmabufId = newId;
                    // v1/v2 legacy format + v3 modifier events
                    const uint32_t formats[2] = { DRM_FORMAT_ARGB8888, DRM_FORMAT_XRGB8888 };
                    for (uint32_t fmt : formats) {
                        WaylandMessageWriter f(newId, 0); // format
                        f.putUint32(fmt);
                        sendPacket(client.fd, f);

                        WaylandMessageWriter mod(newId, 1); // modifier(format, hi, lo) (v3+)
                        mod.putUint32(fmt);
                        mod.putUint32(0); // modifier_hi
                        mod.putUint32(0); // modifier_lo
                        sendPacket(client.fd, mod);
                    }
                }
            }
            return;
        }

        // 2b. zwp_linux_dmabuf_v1 requests
        if (client.dmabufId > 0 && objId == client.dmabufId) {
            if (opcode == 0) {
                client.dmabufId = 0;
            } else if (opcode == 1) {
                // create_params(new_id)
                uint32_t paramsId = reader.readUint32();
                DmabufParams params;
                params.id = paramsId;
                client.dmabufParams[paramsId] = params;
            } else if (opcode == 2) {
                // get_default_feedback(new_id)
                uint32_t feedbackId = reader.readUint32();
                client.dmabufFeedbacks.insert(feedbackId);
                sendDmabufFeedback(client.fd, feedbackId);
            } else if (opcode == 3) {
                // get_surface_feedback(new_id, surface)
                uint32_t feedbackId = reader.readUint32();
                [[maybe_unused]] uint32_t surfaceId = reader.readUint32();
                client.dmabufFeedbacks.insert(feedbackId);
                sendDmabufFeedback(client.fd, feedbackId);
            }
            return;
        }

        // 2c. zwp_linux_dmabuf_feedback_v1 requests
        if (client.dmabufFeedbacks.count(objId) > 0) {
            if (opcode == 0) {
                client.dmabufFeedbacks.erase(objId);
            }
            return;
        }

        // 2d. zwp_linux_buffer_params_v1 requests
        auto paramsIt = client.dmabufParams.find(objId);
        if (paramsIt != client.dmabufParams.end()) {
            auto& params = paramsIt->second;
            if (opcode == 0) {
                client.dmabufParams.erase(paramsIt);
            } else if (opcode == 1) {
                // add(fd, plane_idx, offset, stride, modifier_hi, modifier_lo)
                DmabufPlane plane;
                plane.fd = client.pendingFd;
                client.pendingFd = -1; // consumed
                plane.planeIdx = reader.readUint32();
                plane.offset = reader.readUint32();
                plane.stride = reader.readUint32();
                uint32_t modHi = reader.readUint32();
                uint32_t modLo = reader.readUint32();
                plane.modifier = (static_cast<uint64_t>(modHi) << 32) | modLo;
                if (plane.fd < 0) {
                    LOGE("dmabuf params.add without an fd (plane %u)", plane.planeIdx);
                }
                params.planes.push_back(plane);
            } else if (opcode == 2 || opcode == 3) {
                // create(new_id, width, height, format, flags) | create_immediate(...)
                uint32_t bufferId = reader.readUint32();
                int32_t width = reader.readInt32();
                int32_t height = reader.readInt32();
                uint32_t format = reader.readUint32();
                uint32_t flags = reader.readUint32();

                auto buf = std::make_shared<DmabufBuffer>();
                buf->bufferId = bufferId;
                buf->width = width;
                buf->height = height;
                buf->format = format;
                buf->flags = flags;
                buf->planes = std::move(params.planes);
                params.planes.clear();
                client.dmabufBuffers[bufferId] = buf;

                LOGI("dmabuf buffer %u created: %dx%d fmt=0x%x planes=%zu (path=%s)",
                     bufferId, width, height, format, buf->planes.size(),
                     (opcode == 3 ? "immediate" : "deferred"));

                if (opcode == 2) {
                    // zwp_linux_buffer_params_v1.created(new_id)
                    WaylandMessageWriter created(objId, 0);
                    created.putUint32(bufferId);
                    sendPacket(client.fd, created);
                }
                if (opcode == 2) client.dmabufParams.erase(paramsIt);
            }
            return;
        }

        // 3. wl_shm requests
        if (client.shmId > 0 && objId == client.shmId) {
            if (opcode == 0) {
                // wl_shm.create_pool(pool_id, fd, size)
                uint32_t poolId = reader.readUint32();
                int32_t size = reader.readInt32();

                auto storage = std::make_shared<ShmPoolStorage>();
                storage->size = size;
                storage->fd = client.pendingFd;
                client.pendingFd = -1; // Consumed

                if (storage->fd >= 0 && storage->size > 0) {
                    storage->mappedData = reinterpret_cast<uint8_t*>(
                        mmap(nullptr, storage->size, PROT_READ, MAP_SHARED, storage->fd, 0)
                    );
                    if (storage->mappedData == MAP_FAILED) {
                        LOGE("mmap failed for pool %u: %s", poolId, strerror(errno));
                        storage->mappedData = nullptr;
                    } else {
                        LOGI("wl_shm_pool %u created (fd: %d, size: %d, mapped: %p)",
                             poolId, storage->fd, storage->size, storage->mappedData);
                    }
                }
                ShmPool pool;
                pool.poolId = poolId;
                pool.storage = storage;
                client.shmPools[poolId] = pool;
            }
            return;
        }

        // 4. wl_shm_pool requests
        auto poolIt = client.shmPools.find(objId);
        if (poolIt != client.shmPools.end()) {
            if (opcode == 0) {
                // wl_shm_pool.create_buffer(id, offset, width, height, stride, format)
                uint32_t bufferId = reader.readUint32();
                int32_t offset = reader.readInt32();
                int32_t width = reader.readInt32();
                int32_t height = reader.readInt32();
                int32_t stride = reader.readInt32();
                uint32_t format = reader.readUint32();

                ShmBuffer buf;
                buf.bufferId = bufferId;
                buf.storage = poolIt->second.storage;
                buf.offset = offset;
                buf.width = width;
                buf.height = height;
                buf.stride = stride;
                buf.format = format;

                client.buffers[bufferId] = buf;
            } else if (opcode == 1) {
                // wl_shm_pool.destroy: Destroy protocol object, keep storage alive via buffers
                client.shmPools.erase(poolIt);
            }
            return;
        }

        // 4b. wl_buffer requests
        auto bufIt = client.buffers.find(objId);
        if (bufIt != client.buffers.end()) {
            if (opcode == 0) {
                // wl_buffer.destroy
                client.buffers.erase(bufIt);
            }
            return;
        }

        auto dmabufBufIt = client.dmabufBuffers.find(objId);
        if (dmabufBufIt != client.dmabufBuffers.end()) {
            if (opcode == 0) {
                // wl_buffer.destroy: release imported plane fds
                client.dmabufBuffers.erase(dmabufBufIt);
            }
            return;
        }

        // 5. wl_compositor requests
        if (client.compositorId > 0 && objId == client.compositorId) {
            if (opcode == 0) {
                // wl_compositor.create_surface(new_id)
                uint32_t surfaceId = reader.readUint32();
                ClientSurface s;
                s.surfaceId = surfaceId;
                client.surfaces[surfaceId] = s;
                sendSurfaceEnterIfNeeded(client, client.surfaces[surfaceId]);
            }
            return;
        }

        // 6. wl_surface requests
        auto surfIt = client.surfaces.find(objId);
        if (surfIt != client.surfaces.end()) {
            auto& surface = surfIt->second;
            if (opcode == 0) {
                // wl_surface.destroy
                client.surfaces.erase(surfIt);
            } else if (opcode == 1) {
                // wl_surface.attach(buffer_id, x, y)
                uint32_t bufferId = reader.readUint32();
                surface.attachedBufferId = bufferId;
            } else if (opcode == 3) {
                // wl_surface.frame(callback_id)
                uint32_t callbackId = reader.readUint32();
                surface.frameCallbacks.push_back(callbackId);
                LOGI("wl_surface %u: frame callback %u registered", surface.surfaceId, callbackId);
            } else if (opcode == 6) {
                // wl_surface.commit() -> Blit the active window buffer!
                LOGI("wl_surface %u: commit (buffer=%u, frameCallbacks=%zu, xdg=%d)",
                     surface.surfaceId, surface.attachedBufferId,
                     surface.frameCallbacks.size(), surface.isXdgSurface ? 1 : 0);
                sendSurfaceEnterIfNeeded(client, surface);
                if (surface.attachedBufferId > 0) {
                    auto dbIt = client.dmabufBuffers.find(surface.attachedBufferId);
                    if (dbIt != client.dmabufBuffers.end()) {
                        const auto& dbuf = dbIt->second;
                        bool isMainSurface = surface.isXdgSurface
                                             || (!surface.isCursor && dbuf->width >= 320 && dbuf->height >= 240);
                        if (isMainSurface) {
                            importAndRenderDmabuf(dbuf);
                        }
                        // wl_buffer.release: the imported fd set is retained; the client may reuse
                        WaylandMessageWriter releaseMsg(surface.attachedBufferId, 0);
                        sendPacket(client.fd, releaseMsg);
                        LOGI("wl_buffer %u: release sent (dmabuf)", surface.attachedBufferId);
                    }

                    auto bIt = client.buffers.find(surface.attachedBufferId);
                    if (bIt != client.buffers.end()) {
                        const auto& buf = bIt->second;
                        // Never blit cursor surfaces as the fullscreen window!
                        // Only blit if it is an xdg_surface or a main display buffer (>= 320x240)
                        bool isMainSurface = surface.isXdgSurface || (!surface.isCursor && buf.width >= 320 && buf.height >= 240);
                        if (isMainSurface && buf.storage && buf.storage->mappedData != nullptr) {
                            const uint8_t* pixels = buf.storage->mappedData + buf.offset;
                            blitBufferToWindow(pixels, buf.width, buf.height, buf.stride, buf.format);
                        }

                        // Send wl_buffer.release event so client knows buffer is available for reuse
                        WaylandMessageWriter releaseMsg(surface.attachedBufferId, 0);
                        sendPacket(client.fd, releaseMsg);
                    }
                }

                if (!surface.isCursor) {
                    client.activeSurfaceId = objId;
                }

                // Send frame done to drive client presentation loop
                uint32_t now = nowMillis();
                for (uint32_t cb : surface.frameCallbacks) {
                    WaylandMessageWriter frameDone(cb, 0); // wl_callback.done
                    frameDone.putUint32(now);
                    sendPacket(client.fd, frameDone);
                    LOGI("wl_surface %u: wl_callback.done sent to callback %u", surface.surfaceId, cb);
                }
                surface.frameCallbacks.clear();

                // Send presentation feedback events (wp_presentation_feedback.presented)
                if (!surface.presentationFeedbacks.empty()) {
                    struct timespec ts;
                    clock_gettime(CLOCK_MONOTONIC, &ts);
                    uint32_t tv_sec_hi = static_cast<uint32_t>(static_cast<uint64_t>(ts.tv_sec) >> 32);
                    uint32_t tv_sec_lo = static_cast<uint32_t>(ts.tv_sec & 0xFFFFFFFF);
                    uint32_t tv_nsec = static_cast<uint32_t>(ts.tv_nsec);
                    uint32_t refresh_nsec = 1000000000 / 144; // 144Hz (~6.94ms)

                    for (uint32_t fb : surface.presentationFeedbacks) {
                        WaylandMessageWriter p(fb, 1); // presented
                        p.putUint32(tv_sec_hi);
                        p.putUint32(tv_sec_lo);
                        p.putUint32(tv_nsec);
                        p.putUint32(refresh_nsec);
                        p.putUint32(0); // seq_hi
                        p.putUint32(static_cast<uint32_t>(m_frameCount)); // seq_lo
                        p.putUint32(0x1); // KIND_VSYNC
                        sendPacket(client.fd, p);
                    }
                    surface.presentationFeedbacks.clear();
                }
            }
            return;
        }

        // 7. wl_seat requests
        if (client.seatId > 0 && objId == client.seatId) {
            if (opcode == 0) {
                // wl_seat.get_pointer(new_id)
                client.pointerId = reader.readUint32();
                client.pointerEntered = false;
            } else if (opcode == 1) {
                // wl_seat.get_keyboard(new_id)
                client.keyboardId = reader.readUint32();
                client.keyboardEntered = false;

                // Send keymap(format = 1, fd, size): opcode 0
                uint32_t keymapSize = 0;
                int kmFd = createXkbKeymapFd(&keymapSize);
                if (kmFd >= 0) {
                    WaylandMessageWriter kmMsg(client.keyboardId, 0);
                    kmMsg.putUint32(1); // WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1
                    kmMsg.putUint32(keymapSize);
                    sendPacketWithFd(client.fd, kmMsg, kmFd);
                    close(kmFd);
                }

                // Send repeat_info(rate = 25, delay = 300): opcode 5
                WaylandMessageWriter rpt(client.keyboardId, 5);
                rpt.putInt32(25);
                rpt.putInt32(300);
                sendPacket(client.fd, rpt);
            } else if (opcode == 2) {
                // wl_seat.get_touch(new_id)
                client.touchId = reader.readUint32();
            } else if (opcode == 3) {
                // wl_seat.release
                client.seatId = 0;
            }
            return;
        }

        // 7b. wl_pointer requests
        if (client.pointerId > 0 && objId == client.pointerId) {
            if (opcode == 0) {
                // wl_pointer.set_cursor(serial, surface_id, hotspot_x, hotspot_y)
                uint32_t serial = reader.readUint32();
                uint32_t cursorSurfId = reader.readUint32();
                int32_t hx = reader.readInt32();
                int32_t hy = reader.readInt32();
                (void)serial;
                (void)hx;
                (void)hy;
                if (cursorSurfId > 0) {
                    client.surfaces[cursorSurfId].isCursor = true;
                }
            } else if (opcode == 1) {
                // wl_pointer.release
                client.pointerId = 0;
            }
            return;
        }

        // 8. xdg_wm_base requests
        if (client.xdgWmBaseId > 0 && objId == client.xdgWmBaseId) {
            if (opcode == 0) {
                // xdg_wm_base.destroy
                client.xdgWmBaseId = 0;
            } else if (opcode == 1) {
                // xdg_wm_base.create_positioner(new_id)
                uint32_t posId = reader.readUint32();
                client.xdgPositioners[posId] = {posId};
            } else if (opcode == 2) {
                // xdg_wm_base.get_xdg_surface(new_id, surface_id)
                uint32_t newId = reader.readUint32();
                uint32_t surfId = reader.readUint32();
                XdgSurface xs;
                xs.id = newId;
                xs.surfaceId = surfId;
                client.xdgSurfaces[newId] = xs;
                client.surfaces[surfId].isXdgSurface = true;
                client.activeSurfaceId = surfId;
                sendSurfaceEnterIfNeeded(client, client.surfaces[surfId]);
            } else if (opcode == 3) {
                // xdg_wm_base.pong(serial)
            }
            return;
        }

        // 9. xdg_positioner requests
        auto posIt = client.xdgPositioners.find(objId);
        if (posIt != client.xdgPositioners.end()) {
            if (opcode == 0) {
                client.xdgPositioners.erase(posIt);
            }
            return;
        }

        // 10. xdg_surface requests
        auto xdgSurfIt = client.xdgSurfaces.find(objId);
        if (xdgSurfIt != client.xdgSurfaces.end()) {
            auto& xs = xdgSurfIt->second;
            if (opcode == 0) {
                // xdg_surface.destroy
                client.xdgSurfaces.erase(xdgSurfIt);
            } else if (opcode == 1) {
                // xdg_surface.get_toplevel(new_id)
                uint32_t toplevelId = reader.readUint32();
                XdgToplevel tl;
                tl.id = toplevelId;
                tl.surfaceId = xs.surfaceId;
                client.xdgToplevels[toplevelId] = tl;
                xs.toplevelId = toplevelId;
                client.activeSurfaceId = xs.surfaceId;

                // Send xdg_toplevel.configure(width, height, states)
                WaylandMessageWriter tlConfig(toplevelId, 0);
                tlConfig.putInt32(m_width);
                tlConfig.putInt32(m_height);
                uint32_t states[] = {1, 4}; // XDG_TOPLEVEL_STATE_MAXIMIZED (1), XDG_TOPLEVEL_STATE_ACTIVATED (4)
                tlConfig.putArray(states, sizeof(states));
                sendPacket(client.fd, tlConfig);

                // Send xdg_surface.configure(serial)
                uint32_t serial = nextSerial();
                xs.lastConfigureSerial = serial;
                WaylandMessageWriter surfConfig(xs.id, 0);
                surfConfig.putUint32(serial);
                sendPacket(client.fd, surfConfig);
            } else if (opcode == 2) {
                // xdg_surface.get_popup(new_id, parent, positioner)
                uint32_t popupId = reader.readUint32();
                XdgPopup xp;
                xp.id = popupId;
                xp.surfaceId = xs.surfaceId;
                client.xdgPopups[popupId] = xp;
                xs.popupId = popupId;

                WaylandMessageWriter popConfig(popupId, 0);
                popConfig.putInt32(0);
                popConfig.putInt32(0);
                popConfig.putInt32(m_width);
                popConfig.putInt32(m_height);
                sendPacket(client.fd, popConfig);

                uint32_t serial = nextSerial();
                xs.lastConfigureSerial = serial;
                WaylandMessageWriter surfConfig(xs.id, 0);
                surfConfig.putUint32(serial);
                sendPacket(client.fd, surfConfig);
            } else if (opcode == 3) {
                // xdg_surface.set_window_geometry
            } else if (opcode == 4) {
                // xdg_surface.ack_configure(serial)
                xs.configured = true;
            }
            return;
        }

        // 11. xdg_toplevel requests
        auto tlIt = client.xdgToplevels.find(objId);
        if (tlIt != client.xdgToplevels.end()) {
            if (opcode == 0) {
                client.xdgToplevels.erase(tlIt);
            } else if (opcode == 2) {
                tlIt->second.title = reader.readString();
            } else if (opcode == 3) {
                tlIt->second.appId = reader.readString();
            }
            return;
        }

        // 12. xdg_popup requests
        auto popIt = client.xdgPopups.find(objId);
        if (popIt != client.xdgPopups.end()) {
            if (opcode == 0) {
                client.xdgPopups.erase(popIt);
            }
            return;
        }

        // 13. wl_subcompositor requests
        if (client.subcompositorId > 0 && objId == client.subcompositorId) {
            if (opcode == 0) {
                client.subcompositorId = 0;
            } else if (opcode == 1) {
                // get_subsurface(new_id, surface, parent)
                uint32_t newId = reader.readUint32();
                uint32_t surfId = reader.readUint32();
                uint32_t parentId = reader.readUint32();
                Subsurface sub;
                sub.id = newId;
                sub.surfaceId = surfId;
                sub.parentId = parentId;
                client.subsurfaces[newId] = sub;
            }
            return;
        }

        auto subIt = client.subsurfaces.find(objId);
        if (subIt != client.subsurfaces.end()) {
            if (opcode == 0) {
                client.subsurfaces.erase(subIt);
            }
            return;
        }

        // 14. wl_data_device_manager requests
        if (client.dataDeviceManagerId > 0 && objId == client.dataDeviceManagerId) {
            if (opcode == 0) {
                client.dataSources.insert(reader.readUint32());
            } else if (opcode == 1) {
                client.dataDevices.insert(reader.readUint32());
            }
            return;
        }

        if (client.dataSources.count(objId) > 0) {
            if (opcode == 1) {
                client.dataSources.erase(objId);
            }
            return;
        }

        if (client.dataDevices.count(objId) > 0) {
            if (opcode == 2) {
                client.dataDevices.erase(objId);
            }
            return;
        }

        // 15. zxdg_decoration_manager_v1 requests
        if (client.decorationManagerId > 0 && objId == client.decorationManagerId) {
            if (opcode == 0) {
                client.decorationManagerId = 0;
            } else if (opcode == 1) {
                uint32_t newId = reader.readUint32();
                uint32_t tlId = reader.readUint32();
                ToplevelDecoration dec;
                dec.id = newId;
                dec.toplevelId = tlId;
                dec.mode = 1; // client-side decoration
                client.decorations[newId] = dec;

                WaylandMessageWriter decCfg(newId, 0); // configure(mode = 1)
                decCfg.putUint32(1);
                sendPacket(client.fd, decCfg);
            }
            return;
        }

        auto decIt = client.decorations.find(objId);
        if (decIt != client.decorations.end()) {
            if (opcode == 0) {
                client.decorations.erase(decIt);
            } else if (opcode == 1) {
                uint32_t mode = reader.readUint32();
                decIt->second.mode = mode;
                WaylandMessageWriter decCfg(objId, 0);
                decCfg.putUint32(mode);
                sendPacket(client.fd, decCfg);
            } else if (opcode == 2) {
                decIt->second.mode = 1;
                WaylandMessageWriter decCfg(objId, 0);
                decCfg.putUint32(1);
                sendPacket(client.fd, decCfg);
            }
            return;
        }

        // 16. wp_single_pixel_buffer_manager_v1 requests
        if (client.singlePixelBufferManagerId > 0 && objId == client.singlePixelBufferManagerId) {
            if (opcode == 0) {
                client.singlePixelBufferManagerId = 0;
            } else if (opcode == 1) {
                // create_u32_rgba_buffer(new_id id, uint r, uint g, uint b, uint a)
                uint32_t bufId = reader.readUint32();
                uint32_t r = reader.readUint32();
                uint32_t g = reader.readUint32();
                uint32_t b = reader.readUint32();
                uint32_t a = reader.readUint32();

                auto storage = std::make_shared<ShmPoolStorage>();
                storage->size = 4;
                storage->localData.resize(4);
                uint8_t pr = static_cast<uint8_t>(r >> 24);
                uint8_t pg = static_cast<uint8_t>(g >> 24);
                uint8_t pb = static_cast<uint8_t>(b >> 24);
                uint8_t pa = static_cast<uint8_t>(a >> 24);
                storage->localData[0] = pb;
                storage->localData[1] = pg;
                storage->localData[2] = pr;
                storage->localData[3] = pa;
                storage->mappedData = storage->localData.data();

                ShmBuffer buf;
                buf.bufferId = bufId;
                buf.storage = storage;
                buf.offset = 0;
                buf.width = 1;
                buf.height = 1;
                buf.stride = 4;
                buf.format = 0; // ARGB8888
                client.buffers[bufId] = buf;
            }
            return;
        }

        // 17. wp_viewporter requests
        if (client.viewporterId > 0 && objId == client.viewporterId) {
            if (opcode == 0) {
                client.viewporterId = 0;
            } else if (opcode == 1) {
                // get_viewport(new_id id, surface)
                uint32_t vpId = reader.readUint32();
                [[maybe_unused]] uint32_t sId = reader.readUint32();
                client.viewports.insert(vpId);
            }
            return;
        }

        if (client.viewports.find(objId) != client.viewports.end()) {
            if (opcode == 0) {
                client.viewports.erase(objId);
            }
            return;
        }

        // 18. zwp_pointer_constraints_v1 requests
        if (client.pointerConstraintsId > 0 && objId == client.pointerConstraintsId) {
            if (opcode == 0) {
                client.pointerConstraintsId = 0;
            } else if (opcode == 1) {
                // lock_pointer(new_id id, surface, seat, region, lifetime)
                uint32_t lockId = reader.readUint32();
                client.lockedPointers.insert(lockId);
                WaylandMessageWriter lk(lockId, 0); // locked event
                sendPacket(client.fd, lk);
            } else if (opcode == 2) {
                // confine_pointer(new_id id, surface, seat, region, lifetime)
                uint32_t confId = reader.readUint32();
                client.confinedPointers.insert(confId);
                WaylandMessageWriter cf(confId, 0); // confined event
                sendPacket(client.fd, cf);
            }
            return;
        }

        if (client.lockedPointers.find(objId) != client.lockedPointers.end()) {
            if (opcode == 0) {
                client.lockedPointers.erase(objId);
            }
            return;
        }

        if (client.confinedPointers.find(objId) != client.confinedPointers.end()) {
            if (opcode == 0) {
                client.confinedPointers.erase(objId);
            }
            return;
        }

        // 19. wp_presentation requests
        if (client.presentationId > 0 && objId == client.presentationId) {
            if (opcode == 0) {
                client.presentationId = 0;
            } else if (opcode == 1) {
                // feedback(surface, callback_id)
                uint32_t surfaceId = reader.readUint32();
                uint32_t cbId = reader.readUint32();
                auto sIt = client.surfaces.find(surfaceId);
                if (sIt != client.surfaces.end()) {
                    sIt->second.presentationFeedbacks.push_back(cbId);
                } else {
                    struct timespec ts;
                    clock_gettime(CLOCK_MONOTONIC, &ts);
                    uint32_t tv_sec_hi = static_cast<uint32_t>(static_cast<uint64_t>(ts.tv_sec) >> 32);
                    uint32_t tv_sec_lo = static_cast<uint32_t>(ts.tv_sec & 0xFFFFFFFF);
                    uint32_t tv_nsec = static_cast<uint32_t>(ts.tv_nsec);
                    uint32_t refresh_nsec = 1000000000 / 144;

                    WaylandMessageWriter p(cbId, 1);
                    p.putUint32(tv_sec_hi);
                    p.putUint32(tv_sec_lo);
                    p.putUint32(tv_nsec);
                    p.putUint32(refresh_nsec);
                    p.putUint32(0);
                    p.putUint32(static_cast<uint32_t>(m_frameCount));
                    p.putUint32(0x1);
                    sendPacket(client.fd, p);
                }
            }
            return;
        }

        // 20. xdg_toplevel_icon_manager_v1 & zwp_keyboard_shortcuts_inhibit_manager_v1
        if (client.toplevelIconManagerId > 0 && objId == client.toplevelIconManagerId) {
            if (opcode == 0) {
                client.toplevelIconManagerId = 0;
            }
            return;
        }

        if (client.shortcutsInhibitManagerId > 0 && objId == client.shortcutsInhibitManagerId) {
            if (opcode == 0) {
                client.shortcutsInhibitManagerId = 0;
            }
            return;
        }
    }

    void eventLoop() {
        LOGI("Wayland server listener event loop started");
        std::vector<struct pollfd> pollFds;

        while (m_running.load()) {
            pollFds.clear();

            if (m_serverFd >= 0) {
                pollFds.push_back({m_serverFd, POLLIN, 0});
            }

            {
                std::lock_guard<std::recursive_mutex> lock(m_mutex);
                for (const auto& pair : m_clients) {
                    if (pair.first >= 0) {
                        pollFds.push_back({pair.first, POLLIN | POLLHUP | POLLERR, 0});
                    }
                }
            }

            if (pollFds.empty()) {
                usleep(10000);
                continue;
            }

            int ret = poll(pollFds.data(), pollFds.size(), 100);
            if (ret <= 0) continue;

            // Check incoming connections
            if (pollFds[0].revents & POLLIN) {
                struct sockaddr_un clientAddr;
                socklen_t clientLen = sizeof(clientAddr);
                int clientFd = accept4(m_serverFd, reinterpret_cast<struct sockaddr*>(&clientAddr),
                                       &clientLen, SOCK_NONBLOCK | SOCK_CLOEXEC);
                if (clientFd >= 0) {
                    std::lock_guard<std::recursive_mutex> lock(m_mutex);
                    WaylandClient c;
                    c.fd = clientFd;
                    m_clients[clientFd] = c;
                    m_activeClients.store(static_cast<int>(m_clients.size()));
                    LOGI("New Wayland client connected (fd: %d, total: %d)",
                         clientFd, m_activeClients.load());
                }
            }

            // Check client data
            std::vector<int> deadFds;
            for (size_t i = 1; i < pollFds.size(); ++i) {
                const auto& pfd = pollFds[i];
                if (pfd.revents & (POLLHUP | POLLERR)) {
                    deadFds.push_back(pfd.fd);
                } else if (pfd.revents & POLLIN) {
                    uint8_t buffer[4096];
                    int passedFd = -1;
                    ssize_t n = readClientWithFd(pfd.fd, buffer, sizeof(buffer), &passedFd);
                    if (n <= 0) {
                        deadFds.push_back(pfd.fd);
                    } else {
                        std::lock_guard<std::recursive_mutex> lock(m_mutex);
                        auto it = m_clients.find(pfd.fd);
                        if (it != m_clients.end()) {
                            auto& client = it->second;
                            if (passedFd >= 0) {
                                if (client.pendingFd >= 0) close(client.pendingFd);
                                client.pendingFd = passedFd;
                            }

                            client.recvBuffer.insert(client.recvBuffer.end(), buffer, buffer + n);

                            // Parse complete protocol packets: header is 8 bytes
                            while (client.recvBuffer.size() >= 8) {
                                uint32_t objId = *reinterpret_cast<const uint32_t*>(&client.recvBuffer[0]);
                                uint16_t opcode = *reinterpret_cast<const uint16_t*>(&client.recvBuffer[4]);
                                uint16_t msgSize = *reinterpret_cast<const uint16_t*>(&client.recvBuffer[6]);

                                if (msgSize < 8 || client.recvBuffer.size() < msgSize) {
                                    break;
                                }

                                const uint8_t* payload = client.recvBuffer.data() + 8;
                                size_t payloadLen = msgSize - 8;

                                LOGI("Dispatching Wayland msg: objId=%u, opcode=%u, msgSize=%u", objId, opcode, msgSize);
                                dispatchMessage(client, objId, opcode, payload, payloadLen);

                                client.recvBuffer.erase(client.recvBuffer.begin(),
                                                        client.recvBuffer.begin() + msgSize);
                            }
                        }
                    }
                }
            }

            if (!deadFds.empty()) {
                std::lock_guard<std::recursive_mutex> lock(m_mutex);
                for (int fd : deadFds) {
                    auto it = m_clients.find(fd);
                    if (it != m_clients.end()) {
                        it->second.cleanup();
                        m_clients.erase(it);
                    }
                }
                m_activeClients.store(static_cast<int>(m_clients.size()));
                LOGI("Wayland client(s) disconnected, remaining active: %d", m_activeClients.load());
            }
        }

        LOGI("Wayland server listener event loop terminated");
    }

    std::atomic<bool> m_running;
    int m_serverFd;
    int m_lockFd{-1};
    std::string m_socketDir;
    std::string m_socketPath;
    std::string m_lockPath;
    std::thread m_serverThread;
    std::recursive_mutex m_mutex;

    ANativeWindow* m_nativeWindow;
    int m_width;
    int m_height;
    float m_scale;

    // 144Hz Frame pacing telemetry
    uint64_t m_frameCount{0};
    uint64_t m_pacingFrameCount{0};
    std::chrono::steady_clock::time_point m_lastPacingLogTime{std::chrono::steady_clock::now()};
    std::chrono::steady_clock::time_point m_lastFrameTime{std::chrono::steady_clock::now()};
    double m_totalFrameTimeMs{0.0};
    double m_minFrameTimeMs{9999.0};
    double m_maxFrameTimeMs{0.0};

    std::unordered_map<int, WaylandClient> m_clients;
    std::atomic<int> m_activeClients;
    std::vector<WaylandGlobal> m_globals;
    std::atomic<uint32_t> m_serial;

    std::mutex m_inputMutex;
    TabletToolState m_tabletState;
    PointerState m_pointerState;
    uint32_t m_depressedModifiers{0};
    uint32_t m_lockedModifiers{0};
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
            ANativeWindow_release(window);
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
Java_com_alw_workstation_bridge_wayland_WaylandServerManager_nativeSendPointerAbsolute(
    JNIEnv* env,
    jobject thiz,
    jfloat x,
    jfloat y
) {
    getServer()->sendPointerAbsolute(x, y);
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
