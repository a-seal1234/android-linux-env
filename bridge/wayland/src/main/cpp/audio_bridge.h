#pragma once

#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <cstdint>

#include <aaudio/AAudio.h>

namespace alw {

/**
 * Native low-latency audio bridge server for PulseAudio and PipeWire clients.
 * Listens on a UNIX domain socket (default: $XDG_RUNTIME_DIR/pulse/native)
 * and streams audio into Android AAudio low-latency sink (48000Hz stereo PCM16).
 */
class AudioBridgeServer {
public:
    AudioBridgeServer();
    ~AudioBridgeServer();

    bool start(const std::string& socketPath, int sampleRate = 48000, int channels = 2);
    void stop();

    bool isRunning() const { return m_running.load(); }
    bool isNullSink() const { return m_isNullSink.load(); }
    int getSampleRate() const { return m_sampleRate; }
    int getChannels() const { return m_channels; }
    float getLatencyMs() const;
    int32_t getUnderrunCount() const;
    void setVolume(float volume);
    float getVolume() const { return m_volume.load(); }

    void writeAudio(const int16_t* pcmData, int32_t numFrames);

private:
    void serverLoop();
    void handleClientData(int clientFd, std::vector<uint8_t>& buffer);
    void sendPulseReply(int clientFd, uint32_t reqTag, const std::vector<uint8_t>& payload);

    std::atomic<bool> m_running{false};
    std::atomic<bool> m_isNullSink{false};
    std::string m_socketPath;
    int m_sampleRate{48000};
    int m_channels{2};
    std::atomic<float> m_volume{1.0f};

    int m_serverFd{-1};
    int m_stopPipe[2]{-1, -1};
    std::thread m_serverThread;
    std::mutex m_mutex;

    AAudioStream* m_stream{nullptr};
    std::atomic<int64_t> m_framesWritten{0};
};

AudioBridgeServer* getAudioServer();

} // namespace alw
