package com.alw.workstation.bridge.wayland.audio

import android.util.Log
import com.alw.workstation.core.session.SessionController
import com.alw.workstation.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Diagnostic metrics and operational state of the low-latency audio bridge.
 */
data class AudioStatus(
    val isRunning: Boolean,
    val isNullSink: Boolean,
    val sampleRate: Int,
    val channels: Int,
    val socketPath: String?,
    val latencyMs: Float,
    val underrunCount: Int = 0
)

/**
 * Manages the native low-latency PulseAudio / PipeWire audio bridge server.
 * Listens on a UNIX domain socket ($XDG_RUNTIME_DIR/pulse/native) and streams
 * guest audio output into Android AAudio (48000Hz stereo PCM16).
 *
 * Supports seamless lifecycle coordination with SessionController and provides
 * a null-sink fallback mode for headless and host JVM test environments.
 */
class AudioBridgeManager {

    companion object {
        private const val TAG = "ALW::AudioBridgeMgr"
        var isNativeLoaded = false
            private set

        init {
            try {
                System.loadLibrary("wayland_bridge")
                isNativeLoaded = true
            } catch (e: Throwable) {
                Log.d(TAG, "Native library wayland_bridge not loaded in host JVM test harness")
                isNativeLoaded = false
            }
        }
    }

    private var running = false
    private var mockNullSink = false
    private var activeSocketFile: File? = null
    private var activeSampleRate = 48000
    private var activeChannels = 2
    private var currentVolume = 1.0f

    // JNI Native Declarations
    private external fun nativeStartAudioServer(socketPath: String, sampleRate: Int, channels: Int): Boolean
    private external fun nativeStopAudioServer()
    private external fun nativeIsAudioRunning(): Boolean
    private external fun nativeIsAudioNullSink(): Boolean
    private external fun nativeGetAudioLatencyMs(): Float
    private external fun nativeGetAudioUnderruns(): Int
    private external fun nativeSetAudioVolume(volume: Float)

    /**
     * Starts the native audio bridge server on the given session runtime directory.
     * Creates `<runtimeDir>/pulse/native` UNIX domain socket.
     */
    @Synchronized
    fun startAudioServer(
        runtimeDir: File,
        sampleRate: Int = 48000,
        channels: Int = 2
    ): Boolean {
        if (running) {
            Log.w(TAG, "Audio bridge server is already running")
            return true
        }

        val pulseDir = File(runtimeDir, "pulse")
        if (!pulseDir.exists()) {
            pulseDir.mkdirs()
        }
        val socketFile = File(pulseDir, "native")
        activeSocketFile = socketFile
        activeSampleRate = sampleRate
        activeChannels = channels

        if (isNativeLoaded) {
            val started = nativeStartAudioServer(socketFile.absolutePath, sampleRate, channels)
            running = started
            return started
        } else {
            // Null sink / mock mode for host JVM test harness
            mockNullSink = true
            try {
                if (socketFile.exists()) {
                    socketFile.delete()
                }
                socketFile.createNewFile()
            } catch (e: IOException) {
                Log.w(TAG, "Could not create mock socket file", e)
            }
            running = true
            return true
        }
    }

    /**
     * Stops the audio bridge server and cleans up active sockets and streams.
     */
    @Synchronized
    fun stopAudioServer() {
        if (!running) return

        if (isNativeLoaded) {
            nativeStopAudioServer()
        } else {
            activeSocketFile?.let {
                if (it.exists()) {
                    it.delete()
                }
            }
        }
        running = false
        activeSocketFile = null
    }

    fun isRunning(): Boolean {
        return if (isNativeLoaded) {
            nativeIsAudioRunning()
        } else {
            running
        }
    }

    fun isNullSink(): Boolean {
        return if (isNativeLoaded) {
            nativeIsAudioNullSink()
        } else {
            mockNullSink
        }
    }

    fun getStatus(): AudioStatus {
        val runningNow = isRunning()
        val nullSinkNow = isNullSink()
        val latency = if (isNativeLoaded && runningNow) {
            nativeGetAudioLatencyMs()
        } else if (runningNow) {
            5.0f // Simulated 5ms buffer latency in test harness
        } else {
            0.0f
        }
        val underruns = if (isNativeLoaded && runningNow) {
            nativeGetAudioUnderruns()
        } else {
            0
        }

        return AudioStatus(
            isRunning = runningNow,
            isNullSink = nullSinkNow,
            sampleRate = activeSampleRate,
            channels = activeChannels,
            socketPath = activeSocketFile?.absolutePath,
            latencyMs = latency,
            underrunCount = underruns
        )
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, 1.0f)
        currentVolume = clamped
        if (isNativeLoaded) {
            nativeSetAudioVolume(clamped)
        }
    }

    fun getVolume(): Float = currentVolume

    /**
     * Coordinates the audio bridge server lifecycle with a SessionController.
     * Automatically starts the audio server when session is Running, and stops when Idle/Stopped/Error.
     */
    fun coordinateWith(
        sessionController: SessionController,
        runtimeDir: File,
        scope: CoroutineScope
    ): Job {
        return scope.launch {
            sessionController.sessionState.collectLatest { state ->
                when (state) {
                    is SessionState.Running -> {
                        startAudioServer(runtimeDir)
                    }
                    is SessionState.Stopped,
                    is SessionState.Idle,
                    is SessionState.Error -> {
                        stopAudioServer()
                    }
                    else -> {
                        // Starting, Preparing, Restoring: keep current state
                    }
                }
            }
        }
    }
}
