#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>

#include <string>
#include <vector>
#include <unordered_map>
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
        *reinterpret_cast<uint16_t*>(&m_buf[4]) = 8;
        *reinterpret_cast<uint16_t*>(&m_buf[6]) = opcode;
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

    const std::vector<uint8_t>& data() const { return m_buf; }

private:
    void updateSize() {
        *reinterpret_cast<uint16_t*>(&m_buf[4]) = static_cast<uint16_t>(m_buf.size());
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

struct ShmPool {
    uint32_t poolId;
    int fd = -1;
    int32_t size = 0;
    uint8_t* mappedData = nullptr;

    void cleanup() {
        if (mappedData != nullptr && size > 0) {
            munmap(mappedData, size);
            mappedData = nullptr;
        }
        if (fd >= 0) {
            close(fd);
            fd = -1;
        }
    }
};

struct ShmBuffer {
    uint32_t bufferId;
    uint32_t poolId;
    int32_t offset;
    int32_t width;
    int32_t height;
    int32_t stride;
    uint32_t format;
};

struct ClientSurface {
    uint32_t surfaceId;
    uint32_t attachedBufferId = 0;
    std::vector<uint32_t> frameCallbacks;
};

struct WaylandClient {
    int fd = -1;
    uint32_t registryId = 0;
    uint32_t shmId = 0;
    uint32_t seatId = 0;
    uint32_t outputId = 0;
    uint32_t compositorId = 0;
    uint32_t pointerId = 0;
    uint32_t keyboardId = 0;
    uint32_t touchId = 0;
    uint32_t tabletManagerId = 0;

    std::vector<uint8_t> recvBuffer;
    int pendingFd = -1;

    std::unordered_map<uint32_t, ShmPool> shmPools;
    std::unordered_map<uint32_t, ShmBuffer> buffers;
    std::unordered_map<uint32_t, ClientSurface> surfaces;

    void cleanup() {
        for (auto& pair : shmPools) {
            pair.second.cleanup();
        }
        shmPools.clear();
        buffers.clear();
        surfaces.clear();
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
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_running.load()) {
            LOGW("Wayland server already running");
            return true;
        }

        m_socketDir = socketDir;
        m_width = width > 0 ? width : 3200;
        m_height = height > 0 ? height : 2136;
        m_scale = scale > 0.1f ? scale : 1.75f;
        m_socketPath = socketDir + "/wayland-0";

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

        std::lock_guard<std::mutex> lock(m_mutex);
        for (auto& pair : m_clients) {
            pair.second.cleanup();
        }
        m_clients.clear();
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

    bool isRunning() const { return m_running.load(); }
    int getActiveClientsCount() const { return m_activeClients.load(); }

    bool isTabletV2Supported() const {
        for (const auto& g : m_globals) {
            if (g.interface == "zwp_tablet_manager_v2") return true;
        }
        return false;
    }

    // Direct pixel blit to ANativeWindow buffer
    void blitBufferToWindow(const uint8_t* src, int srcWidth, int srcHeight, int srcStride) {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_nativeWindow == nullptr || src == nullptr) return;

        ANativeWindow_Buffer outBuffer;
        if (ANativeWindow_lock(m_nativeWindow, &outBuffer, nullptr) != 0) {
            LOGW("ANativeWindow_lock failed during frame blit");
            return;
        }

        const int copyWidth = std::min(srcWidth, outBuffer.width);
        const int copyHeight = std::min(srcHeight, outBuffer.height);
        const size_t rowBytes = static_cast<size_t>(copyWidth) * 4;

        auto* dstPixels = reinterpret_cast<uint8_t*>(outBuffer.bits);
        for (int y = 0; y < copyHeight; ++y) {
            const uint8_t* srcRow = src + y * srcStride;
            uint8_t* dstRow = dstPixels + y * outBuffer.stride * 4;
            std::memcpy(dstRow, srcRow, rowBytes);
        }

        ANativeWindow_unlockAndPost(m_nativeWindow);
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
    void sendPointerMotion(float dx, float dy) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_pointerState.dx_fixed = wl_fixed_from_double(dx);
        m_pointerState.dy_fixed = wl_fixed_from_double(dy);
    }

    void sendPointerButton(uint32_t buttonCode, bool isDown) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        if (isDown) m_pointerState.buttonMask |= buttonCode;
        else m_pointerState.buttonMask &= ~buttonCode;
    }

    void sendPointerAxis(int axis, float value) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
    }

    void sendKeyEvent(int scanCode, bool isDown) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
    }

    void sendTouchEvent(int action, int touchId, float x, float y) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
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
                    // xdg_wm_base instantiated
                } else if (iface == "zwp_tablet_manager_v2") {
                    client.tabletManagerId = newId;
                }
            }
            return;
        }

        // 3. wl_shm requests
        if (client.shmId > 0 && objId == client.shmId) {
            if (opcode == 0) {
                // wl_shm.create_pool(pool_id, fd, size)
                uint32_t poolId = reader.readUint32();
                int32_t size = reader.readInt32();

                ShmPool pool;
                pool.poolId = poolId;
                pool.size = size;
                pool.fd = client.pendingFd;
                client.pendingFd = -1; // Consumed

                if (pool.fd >= 0 && pool.size > 0) {
                    pool.mappedData = reinterpret_cast<uint8_t*>(
                        mmap(nullptr, pool.size, PROT_READ, MAP_SHARED, pool.fd, 0)
                    );
                    if (pool.mappedData == MAP_FAILED) {
                        LOGE("mmap failed for pool %u: %s", poolId, strerror(errno));
                        pool.mappedData = nullptr;
                    } else {
                        LOGI("wl_shm_pool %u created (fd: %d, size: %d, mapped: %p)",
                             poolId, pool.fd, pool.size, pool.mappedData);
                    }
                }
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
                buf.poolId = objId;
                buf.offset = offset;
                buf.width = width;
                buf.height = height;
                buf.stride = stride;
                buf.format = format;

                client.buffers[bufferId] = buf;
            } else if (opcode == 1) {
                // wl_shm_pool.destroy
                poolIt->second.cleanup();
                client.shmPools.erase(poolIt);
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
            } else if (opcode == 6) {
                // wl_surface.commit() -> Blit the active buffer!
                if (surface.attachedBufferId > 0) {
                    auto bIt = client.buffers.find(surface.attachedBufferId);
                    if (bIt != client.buffers.end()) {
                        const auto& buf = bIt->second;
                        auto pIt = client.shmPools.find(buf.poolId);
                        if (pIt != client.shmPools.end() && pIt->second.mappedData != nullptr) {
                            const uint8_t* pixels = pIt->second.mappedData + buf.offset;
                            blitBufferToWindow(pixels, buf.width, buf.height, buf.stride);
                        }
                    }
                }

                // Send frame done to drive client presentation loop
                uint32_t now = nowMillis();
                for (uint32_t cb : surface.frameCallbacks) {
                    WaylandMessageWriter frameDone(cb, 0); // wl_callback.done
                    frameDone.putUint32(now);
                    sendPacket(client.fd, frameDone);
                }
                surface.frameCallbacks.clear();
            }
            return;
        }

        // 7. xdg_wm_base requests
        if (opcode == 3) {
            // xdg_wm_base.pong(serial)
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
                std::lock_guard<std::mutex> lock(m_mutex);
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
                    std::lock_guard<std::mutex> lock(m_mutex);
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
                        std::lock_guard<std::mutex> lock(m_mutex);
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
                                uint16_t msgSize = *reinterpret_cast<const uint16_t*>(&client.recvBuffer[4]);
                                uint16_t opcode = *reinterpret_cast<const uint16_t*>(&client.recvBuffer[6]);

                                if (msgSize < 8 || client.recvBuffer.size() < msgSize) {
                                    break;
                                }

                                const uint8_t* payload = client.recvBuffer.data() + 8;
                                size_t payloadLen = msgSize - 8;

                                dispatchMessage(client, objId, opcode, payload, payloadLen);

                                client.recvBuffer.erase(client.recvBuffer.begin(),
                                                        client.recvBuffer.begin() + msgSize);
                            }
                        }
                    }
                }
            }

            if (!deadFds.empty()) {
                std::lock_guard<std::mutex> lock(m_mutex);
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
    std::string m_socketDir;
    std::string m_socketPath;
    std::thread m_serverThread;
    std::mutex m_mutex;

    ANativeWindow* m_nativeWindow;
    int m_width;
    int m_height;
    float m_scale;

    std::unordered_map<int, WaylandClient> m_clients;
    std::atomic<int> m_activeClients;
    std::vector<WaylandGlobal> m_globals;
    std::atomic<uint32_t> m_serial;

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
