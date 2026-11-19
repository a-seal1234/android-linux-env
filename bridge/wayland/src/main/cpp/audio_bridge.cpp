#include "audio_bridge.h"

#include <jni.h>
#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <arpa/inet.h>
#include <pthread.h>

#include <cstring>
#include <algorithm>
#include <map>

#define TAG "ALW::AudioBridgeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace alw {

namespace {

// PulseAudio Protocol Constants
constexpr uint32_t PA_PACKET_HEADER_SIZE = 20;
constexpr uint32_t PA_COMMAND_REPLY = 2;
constexpr uint32_t PA_COMMAND_AUTH = 0;
constexpr uint32_t PA_COMMAND_SET_CLIENT_NAME = 1;
constexpr uint32_t PA_COMMAND_GET_SERVER_INFO = 15;
constexpr uint32_t PA_COMMAND_GET_SINK_INFO = 16;
constexpr uint32_t PA_COMMAND_GET_SINK_INFO_LIST = 17;
constexpr uint32_t PA_COMMAND_CREATE_PLAYBACK_STREAM = 21;
constexpr uint32_t PA_CHANNEL_CONTROL = 0xFFFFFFFF;

// Helper to write big-endian tagstruct values
class PaTagStructWriter {
public:
    void writeU32(uint32_t val) {
        m_bytes.push_back('u');
        uint32_t netVal = htonl(val);
        appendRaw(&netVal, sizeof(netVal));
    }

    void writeU64(uint64_t val) {
        m_bytes.push_back('U');
        uint32_t hi = htonl(static_cast<uint32_t>(val >> 32));
        uint32_t lo = htonl(static_cast<uint32_t>(val & 0xFFFFFFFF));
        appendRaw(&hi, sizeof(hi));
        appendRaw(&lo, sizeof(lo));
    }

    void writeString(const std::string& str) {
        m_bytes.push_back('s');
        appendRaw(str.c_str(), str.length() + 1);
    }

    void writeSampleSpec(uint8_t format, uint8_t channels, uint32_t rate) {
        m_bytes.push_back('a');
        m_bytes.push_back(format);
        m_bytes.push_back(channels);
        uint32_t netRate = htonl(rate);
        appendRaw(&netRate, sizeof(netRate));
    }

    void writeChannelMap(uint8_t channels, const std::vector<uint8_t>& map) {
        m_bytes.push_back('c');
        m_bytes.push_back(channels);
        for (uint8_t ch : map) {
            m_bytes.push_back(ch);
        }
    }

    void writeCVolume(uint8_t channels, uint32_t volume) {
        m_bytes.push_back('v');
        m_bytes.push_back(channels);
        uint32_t netVol = htonl(volume);
        for (uint8_t i = 0; i < channels; ++i) {
            appendRaw(&netVol, sizeof(netVol));
        }
    }

    void writeBoolean(bool val) {
        m_bytes.push_back('B');
        m_bytes.push_back(val ? 1 : 0);
    }

    void writeNull() {
        m_bytes.push_back('N');
    }

    const std::vector<uint8_t>& data() const { return m_bytes; }

private:
    void appendRaw(const void* src, size_t size) {
        const uint8_t* p = static_cast<const uint8_t*>(src);
        m_bytes.insert(m_bytes.end(), p, p + size);
    }

    std::vector<uint8_t> m_bytes;
};

} // namespace

AudioBridgeServer::AudioBridgeServer() = default;

AudioBridgeServer::~AudioBridgeServer() {
    stop();
}

bool AudioBridgeServer::start(const std::string& socketPath, int sampleRate, int channels) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_running.load()) {
        LOGW("AudioBridgeServer already running");
        return true;
    }

    m_socketPath = socketPath;
    m_sampleRate = sampleRate > 0 ? sampleRate : 48000;
    m_channels = channels > 0 ? channels : 2;

    // Ensure parent directory exists
    size_t slashPos = m_socketPath.find_last_of('/');
    if (slashPos != std::string::npos && slashPos > 0) {
        std::string parentDir = m_socketPath.substr(0, slashPos);
        mkdir(parentDir.c_str(), 0755);
    }

    // Unlink any stale socket file
    unlink(m_socketPath.c_str());

    // Create UNIX domain stream socket
    m_serverFd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (m_serverFd < 0) {
        LOGE("Failed to create audio UNIX socket: %s", strerror(errno));
        return false;
    }

    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, m_socketPath.c_str(), sizeof(addr.sun_path) - 1);

    if (bind(m_serverFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        LOGE("Failed to bind audio UNIX socket (%s): %s", m_socketPath.c_str(), strerror(errno));
        close(m_serverFd);
        m_serverFd = -1;
        return false;
    }

    // Set socket permissions so guest PRoot user can read/write
    chmod(m_socketPath.c_str(), 0777);

    if (listen(m_serverFd, 8) < 0) {
        LOGE("Failed to listen on audio socket: %s", strerror(errno));
        close(m_serverFd);
        unlink(m_socketPath.c_str());
        m_serverFd = -1;
        return false;
    }

    // Initialize stop pipe
    if (pipe2(m_stopPipe, O_CLOEXEC | O_NONBLOCK) < 0) {
        LOGE("Failed to create audio stop pipe: %s", strerror(errno));
        close(m_serverFd);
        unlink(m_socketPath.c_str());
        m_serverFd = -1;
        return false;
    }

    // Initialize AAudio output stream
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result == AAUDIO_OK && builder != nullptr) {
        AAudioStreamBuilder_setSampleRate(builder, m_sampleRate);
        AAudioStreamBuilder_setChannelCount(builder, m_channels);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);

        result = AAudioStreamBuilder_openStream(builder, &m_stream);
        AAudioStreamBuilder_delete(builder);

        if (result == AAUDIO_OK && m_stream != nullptr) {
            result = AAudioStream_requestStart(m_stream);
            if (result == AAUDIO_OK) {
                m_isNullSink.store(false);
                LOGI("AAudio stream active: sampleRate=%d, channels=%d, low-latency", m_sampleRate, m_channels);
            } else {
                LOGW("AAudioStream_requestStart failed (%d), falling back to null sink", result);
                AAudioStream_close(m_stream);
                m_stream = nullptr;
                m_isNullSink.store(true);
            }
        } else {
            LOGW("AAudioStreamBuilder_openStream failed (%d), falling back to null sink", result);
            m_stream = nullptr;
            m_isNullSink.store(true);
        }
    } else {
        LOGW("AAudio_createStreamBuilder failed, falling back to null sink");
        m_stream = nullptr;
        m_isNullSink.store(true);
    }

    m_running.store(true);
    m_serverThread = std::thread(&AudioBridgeServer::serverLoop, this);
    LOGI("Audio bridge server listening on %s (nullSink=%d)", m_socketPath.c_str(), m_isNullSink.load());
    return true;
}

void AudioBridgeServer::stop() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_running.load()) {
        return;
    }

    m_running.store(false);

    // Wake up serverLoop thread via stop pipe
    if (m_stopPipe[1] >= 0) {
        char dummy = 1;
        write(m_stopPipe[1], &dummy, 1);
    }

    if (m_serverThread.joinable()) {
        m_serverThread.join();
    }

    if (m_stopPipe[0] >= 0) {
        close(m_stopPipe[0]);
        m_stopPipe[0] = -1;
    }
    if (m_stopPipe[1] >= 0) {
        close(m_stopPipe[1]);
        m_stopPipe[1] = -1;
    }

    if (m_serverFd >= 0) {
        close(m_serverFd);
        m_serverFd = -1;
    }

    if (!m_socketPath.empty()) {
        unlink(m_socketPath.c_str());
    }

    if (m_stream != nullptr) {
        AAudioStream_requestStop(m_stream);
        AAudioStream_close(m_stream);
        m_stream = nullptr;
    }

    m_isNullSink.store(false);
    LOGI("Audio bridge server stopped");
}

void AudioBridgeServer::serverLoop() {
    pthread_setname_np(pthread_self(), "alw-audio-loop");
    std::map<int, std::vector<uint8_t>> clientBuffers;

    while (m_running.load()) {
        std::vector<struct pollfd> pollFds;
        pollFds.push_back({m_stopPipe[0], POLLIN, 0});
        pollFds.push_back({m_serverFd, POLLIN, 0});

        for (const auto& pair : clientBuffers) {
            pollFds.push_back({pair.first, POLLIN | POLLHUP | POLLERR, 0});
        }

        int ret = poll(pollFds.data(), pollFds.size(), -1);
        if (ret < 0) {
            if (errno == EINTR) continue;
            LOGE("poll() failed in audio serverLoop: %s", strerror(errno));
            break;
        }

        // Check for stop signal
        if (pollFds[0].revents & POLLIN) {
            break;
        }

        // Check for incoming client connections
        if (pollFds[1].revents & POLLIN) {
            int clientFd = accept4(m_serverFd, nullptr, nullptr, SOCK_NONBLOCK | SOCK_CLOEXEC);
            if (clientFd >= 0) {
                clientBuffers[clientFd] = std::vector<uint8_t>();
                LOGI("PulseAudio client connected (fd=%d)", clientFd);
            }
        }

        // Process client reads
        std::vector<int> clientsToRemove;
        for (size_t i = 2; i < pollFds.size(); ++i) {
            int cFd = pollFds[i].fd;
            short revents = pollFds[i].revents;

            if (revents & (POLLHUP | POLLERR)) {
                clientsToRemove.push_back(cFd);
                continue;
            }

            if (revents & POLLIN) {
                uint8_t readBuf[16384];
                ssize_t bytesRead = read(cFd, readBuf, sizeof(readBuf));
                if (bytesRead > 0) {
                    auto& buf = clientBuffers[cFd];
                    buf.insert(buf.end(), readBuf, readBuf + bytesRead);
                    handleClientData(cFd, buf);
                } else if (bytesRead == 0 || (bytesRead < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) {
                    clientsToRemove.push_back(cFd);
                }
            }
        }

        for (int cFd : clientsToRemove) {
            close(cFd);
            clientBuffers.erase(cFd);
            LOGI("PulseAudio client disconnected (fd=%d)", cFd);
        }
    }

    for (const auto& pair : clientBuffers) {
        close(pair.first);
    }
}

void AudioBridgeServer::handleClientData(int clientFd, std::vector<uint8_t>& buffer) {
    while (buffer.size() >= PA_PACKET_HEADER_SIZE) {
        const uint32_t* hdr = reinterpret_cast<const uint32_t*>(buffer.data());
        uint32_t length = ntohl(hdr[0]);
        uint32_t channel = ntohl(hdr[1]);

        // Guard against corrupt packet lengths
        if (length > 4 * 1024 * 1024) {
            LOGW("Corrupt packet length %u, dropping client data", length);
            buffer.clear();
            return;
        }

        uint32_t totalPacketSize = PA_PACKET_HEADER_SIZE + length;
        if (buffer.size() < totalPacketSize) {
            // Need more data
            return;
        }

        if (channel == PA_CHANNEL_CONTROL) {
            // Control packet tagstruct
            if (length >= 5) {
                const uint8_t* payload = buffer.data() + PA_PACKET_HEADER_SIZE;
                size_t offset = 0;

                if (payload[offset] == 'u') {
                    offset++;
                    uint32_t command = ntohl(*reinterpret_cast<const uint32_t*>(payload + offset));
                    offset += 4;

                    uint32_t reqTag = 0;
                    if (offset < length && payload[offset] == 'u') {
                        offset++;
                        reqTag = ntohl(*reinterpret_cast<const uint32_t*>(payload + offset));
                        offset += 4;
                    }

                    PaTagStructWriter reply;
                    if (command == PA_COMMAND_AUTH) {
                        reply.writeU32(35); // Protocol version
                    } else if (command == PA_COMMAND_SET_CLIENT_NAME) {
                        reply.writeU32(0); // Client index
                    } else if (command == PA_COMMAND_GET_SERVER_INFO) {
                        reply.writeString("pulseaudio");
                        reply.writeString("16.1");
                        reply.writeString("alw");
                        reply.writeString("alw-workstation");
                        reply.writeSampleSpec(3, static_cast<uint8_t>(m_channels), static_cast<uint32_t>(m_sampleRate));
                        reply.writeString("alw_output");
                        reply.writeString("alw_input");
                        reply.writeU32(0x414C5700);
                        reply.writeChannelMap(2, {1, 2});
                    } else if (command == PA_COMMAND_GET_SINK_INFO || command == PA_COMMAND_GET_SINK_INFO_LIST) {
                        reply.writeU32(0); // Sink index
                        reply.writeString("alw_output");
                        reply.writeString("Android Low-Latency AAudio Output");
                        reply.writeSampleSpec(3, static_cast<uint8_t>(m_channels), static_cast<uint32_t>(m_sampleRate));
                        reply.writeChannelMap(2, {1, 2});
                        reply.writeU32(0); // Module index
                        reply.writeCVolume(2, 65536); // 100% volume
                        reply.writeBoolean(false); // Not muted
                        reply.writeU32(1); // Monitor source index
                        reply.writeString("alw_output.monitor");
                        reply.writeU64(5000); // 5ms latency
                        reply.writeString("module-aaudio-sink");
                        reply.writeU32(0x0004); // PA_SINK_LATENCY
                        reply.writeNull();
                    } else if (command == PA_COMMAND_CREATE_PLAYBACK_STREAM) {
                        reply.writeU32(0); // Stream index
                        reply.writeU32(0); // Audio data channel ID
                        reply.writeU32(16384); // Requested bytes
                        reply.writeU32(65536); // Maxlength
                        reply.writeU32(16384); // Tlength
                        reply.writeU32(4096);  // Prebuf
                        reply.writeU32(1920);  // Minreq
                        reply.writeU64(0);
                        reply.writeChannelMap(2, {1, 2});
                        reply.writeU32(0); // Sink index
                        reply.writeString("alw_output");
                        reply.writeBoolean(false); // Suspended
                    }

                    sendPulseReply(clientFd, reqTag, reply.data());
                }
            }
        } else {
            // Audio PCM data stream packet
            const uint8_t* pcmBytes = buffer.data() + PA_PACKET_HEADER_SIZE;
            int numFrames = length / (m_channels * sizeof(int16_t));
            if (numFrames > 0) {
                writeAudio(reinterpret_cast<const int16_t*>(pcmBytes), numFrames);
            }
        }

        buffer.erase(buffer.begin(), buffer.begin() + totalPacketSize);
    }
}

void AudioBridgeServer::sendPulseReply(int clientFd, uint32_t reqTag, const std::vector<uint8_t>& payload) {
    PaTagStructWriter writer;
    writer.writeU32(PA_COMMAND_REPLY);
    writer.writeU32(reqTag);

    std::vector<uint8_t> body = writer.data();
    body.insert(body.end(), payload.begin(), payload.end());

    uint32_t header[5];
    header[0] = htonl(static_cast<uint32_t>(body.size()));
    header[1] = htonl(PA_CHANNEL_CONTROL);
    header[2] = 0;
    header[3] = 0;
    header[4] = 0;

    std::vector<uint8_t> fullPacket;
    fullPacket.resize(PA_PACKET_HEADER_SIZE + body.size());
    std::memcpy(fullPacket.data(), header, PA_PACKET_HEADER_SIZE);
    std::memcpy(fullPacket.data() + PA_PACKET_HEADER_SIZE, body.data(), body.size());

    send(clientFd, fullPacket.data(), fullPacket.size(), MSG_NOSIGNAL);
}

void AudioBridgeServer::writeAudio(const int16_t* pcmData, int32_t numFrames) {
    if (numFrames <= 0 || pcmData == nullptr) return;

    float vol = m_volume.load();
    std::vector<int16_t> adjusted;
    const int16_t* writePtr = pcmData;

    if (vol < 0.999f) {
        adjusted.resize(numFrames * m_channels);
        for (size_t i = 0; i < adjusted.size(); ++i) {
            adjusted[i] = static_cast<int16_t>(pcmData[i] * vol);
        }
        writePtr = adjusted.data();
    }

    if (m_stream != nullptr && !m_isNullSink.load()) {
        int64_t timeoutNanoseconds = 10000000; // 10ms
        aaudio_result_t res = AAudioStream_write(m_stream, writePtr, numFrames, timeoutNanoseconds);
        if (res > 0) {
            m_framesWritten.fetch_add(res);
        } else if (res < 0) {
            LOGW("AAudioStream_write returned %d", res);
        }
    } else {
        m_framesWritten.fetch_add(numFrames);
    }
}

float AudioBridgeServer::getLatencyMs() const {
    if (m_stream != nullptr && !m_isNullSink.load()) {
        int64_t written = AAudioStream_getFramesWritten(m_stream);
        int64_t read = AAudioStream_getFramesRead(m_stream);
        int64_t delta = written - read;
        if (delta < 0) delta = 0;
        return (static_cast<float>(delta) / static_cast<float>(m_sampleRate)) * 1000.0f;
    }
    return 5.0f; // 5ms simulated latency for null sink
}

int32_t AudioBridgeServer::getUnderrunCount() const {
    if (m_stream != nullptr && !m_isNullSink.load()) {
        return AAudioStream_getXRunCount(m_stream);
    }
    return 0;
}

void AudioBridgeServer::setVolume(float volume) {
    m_volume.store(std::clamp(volume, 0.0f, 1.0f));
}

AudioBridgeServer* getAudioServer() {
    static AudioBridgeServer s_server;
    return &s_server;
}

} // namespace alw

// JNI Bindings for com.alw.workstation.bridge.wayland.audio.AudioBridgeManager
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_alw_workstation_bridge_wayland_audio_AudioBridgeManager_nativeStartAudioServer(
    JNIEnv* env,
    jobject thiz,
    jstring socketPath,
    jint sampleRate,
    jint channels
) {
    const char* pathStr = env->GetStringUTFChars(socketPath, nullptr);
    bool ok = alw::getAudioServer()->start(pathStr, sampleRate, channels);
    env->ReleaseStringUTFChars(socketPath, pathStr);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_audio_AudioBridgeManager_nativeStopAudioServer(
    JNIEnv* env,
    jobject thiz
) {
    alw::getAudioServer()->stop();
}

JNIEXPORT jboolean JNICALL
Java_com_alw_workstation_bridge_wayland_audio_AudioBridgeManager_nativeIsAudioRunning(
    JNIEnv* env,
    jobject thiz
) {
    return alw::getAudioServer()->isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_alw_workstation_bridge_wayland_audio_AudioBridgeManager_nativeIsAudioNullSink(
    JNIEnv* env,
    jobject thiz
) {
    return alw::getAudioServer()->isNullSink() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_alw_workstation_bridge_wayland_audio_AudioBridgeManager_nativeGetAudioLatencyMs(
    JNIEnv* env,
    jobject thiz
) {
    return alw::getAudioServer()->getLatencyMs();
}

JNIEXPORT jint JNICALL
Java_com_alw_workstation_bridge_wayland_audio_AudioBridgeManager_nativeGetAudioUnderruns(
    JNIEnv* env,
    jobject thiz
) {
    return alw::getAudioServer()->getUnderrunCount();
}

JNIEXPORT void JNICALL
Java_com_alw_workstation_bridge_wayland_audio_AudioBridgeManager_nativeSetAudioVolume(
    JNIEnv* env,
    jobject thiz,
    jfloat volume
) {
    alw::getAudioServer()->setVolume(volume);
}

}
