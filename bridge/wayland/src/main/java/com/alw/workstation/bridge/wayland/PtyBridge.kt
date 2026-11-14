package com.alw.workstation.bridge.wayland

import android.util.Log
import java.io.FileDescriptor

/**
 * JNI bridge to native POSIX pseudoterminal (PTY) facilities (openpty, forkpty, ioctl TIOCSWINSZ).
 */
object PtyBridge {

    private const val TAG = "ALW::PtyBridge"

    var isNativeLoaded: Boolean = false
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

    data class PtyFds(
        val masterFd: Int,
        val slaveFd: Int
    )

    data class SubprocessResult(
        val pid: Int,
        val masterFd: Int
    )

    // JNI External Functions
    @JvmStatic
    private external fun nativeCreatePty(cols: Int, rows: Int): IntArray?

    @JvmStatic
    private external fun nativeSetWindowSize(masterFd: Int, cols: Int, rows: Int): Boolean

    @JvmStatic
    private external fun nativeCloseFd(fd: Int)

    @JvmStatic
    private external fun nativeForkSubprocess(
        cmd: String,
        args: Array<String>?,
        env: Array<String>?,
        cwd: String?,
        cols: Int,
        rows: Int
    ): IntArray?

    /**
     * Creates an open PTY master/slave pair.
     */
    fun createPty(cols: Int = 80, rows: Int = 24): PtyFds? {
        if (!isNativeLoaded) {
            // Mock FDs for JVM test harness
            return PtyFds(masterFd = 100, slaveFd = 101)
        }
        val result = nativeCreatePty(cols, rows) ?: return null
        return PtyFds(masterFd = result[0], slaveFd = result[1])
    }

    /**
     * Resizes the PTY window size via TIOCSWINSZ.
     */
    fun setWindowSize(masterFd: Int, cols: Int, rows: Int): Boolean {
        if (!isNativeLoaded) return true
        return nativeSetWindowSize(masterFd, cols, rows)
    }

    /**
     * Closes an open native file descriptor.
     */
    fun closeFd(fd: Int) {
        if (!isNativeLoaded) return
        nativeCloseFd(fd)
    }

    /**
     * Forks and executes a child process directly attached to a newly created PTY.
     */
    fun forkSubprocess(
        command: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        cwd: String? = null,
        cols: Int = 80,
        rows: Int = 24
    ): SubprocessResult? {
        if (!isNativeLoaded) {
            // Mock result for JVM test harness
            return SubprocessResult(pid = 12345, masterFd = 100)
        }

        val envArray = env.map { "${it.key}=${it.value}" }.toTypedArray()
        val argsArray = if (args.isEmpty()) arrayOf(command) else args.toTypedArray()

        val result = nativeForkSubprocess(
            cmd = command,
            args = argsArray,
            env = envArray,
            cwd = cwd,
            cols = cols,
            rows = rows
        ) ?: return null

        return SubprocessResult(pid = result[0], masterFd = result[1])
    }
}
