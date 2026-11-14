package com.alw.workstation.bridge.wayland

import android.util.Log
import android.view.Surface
import java.io.File
import java.io.IOException

/**
 * Manages native in-process Wayland server lifecycle and surface presentation.
 * Adapted from the embedded surface architecture of Beauty114514/trierarch-app.
 */
class WaylandServerManager {

    companion object {
        private const val TAG = "ALW::WaylandServerMgr"
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

    data class ServerStatus(
        val isRunning: Boolean,
        val socketPath: String?,
        val width: Int,
        val height: Int,
        val scale: Float,
        val activeClients: Int,
        val tabletV2Supported: Boolean
    )

    // JNI Native Declarations
    private external fun nativeInitServer(
        socketDir: String,
        width: Int,
        height: Int,
        scale: Float
    ): Boolean

    private external fun nativeSetSurface(surface: Surface?)
    private external fun nativeResizeSurface(width: Int, height: Int)
    private external fun nativeIsRunning(): Boolean
    private external fun nativeGetActiveClients(): Int
    private external fun nativeIsTabletV2Supported(): Boolean
    private external fun nativeSendPointerMotion(dx: Float, dy: Float)
    private external fun nativeSendKeyEvent(scanCode: Int, isDown: Boolean)
    private external fun nativeStopServer()

    // Host JVM Mock State (active when isNativeLoaded is false)
    private var mockRunning = false
    private var mockSocketPath: String? = null
    private var mockWidth = 3200
    private var mockHeight = 2136
    private var mockScale = 1.75f
    private var mockSurfaceAttached = false

    fun startServer(socketDir: File, width: Int, height: Int, scale: Float): Boolean {
        if (!socketDir.exists()) {
            socketDir.mkdirs()
        }

        if (isNativeLoaded) {
            val success = nativeInitServer(socketDir.absolutePath, width, height, scale)
            if (success) {
                mockWidth = width
                mockHeight = height
                mockScale = scale
                mockSocketPath = File(socketDir, "wayland-0").absolutePath
            }
            return success
        } else {
            // Mock server behavior for JVM test harness
            val socketFile = File(socketDir, "wayland-0")
            try {
                if (!socketFile.exists()) {
                    socketFile.createNewFile()
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed creating mock socket: ${e.message}")
            }
            mockRunning = true
            mockSocketPath = socketFile.absolutePath
            mockWidth = width
            mockHeight = height
            mockScale = scale
            return true
        }
    }

    fun attachSurface(surface: Surface) {
        if (isNativeLoaded) {
            nativeSetSurface(surface)
        } else {
            mockSurfaceAttached = true
        }
    }

    fun resizeSurface(width: Int, height: Int) {
        mockWidth = width
        mockHeight = height
        if (isNativeLoaded) {
            nativeResizeSurface(width, height)
        }
    }

    fun detachSurface() {
        if (isNativeLoaded) {
            nativeSetSurface(null)
        } else {
            mockSurfaceAttached = false
        }
    }

    fun isRunning(): Boolean {
        return if (isNativeLoaded) nativeIsRunning() else mockRunning
    }

    fun getActiveClients(): Int {
        return if (isNativeLoaded) nativeGetActiveClients() else 0
    }

    fun isTabletV2Supported(): Boolean {
        return if (isNativeLoaded) nativeIsTabletV2Supported() else true
    }

    fun sendPointerDelta(dx: Float, dy: Float) {
        if (isNativeLoaded) {
            nativeSendPointerMotion(dx, dy)
        }
    }

    fun sendKey(scanCode: Int, isDown: Boolean) {
        if (isNativeLoaded) {
            nativeSendKeyEvent(scanCode, isDown)
        }
    }

    fun stopServer() {
        if (isNativeLoaded) {
            nativeStopServer()
        } else {
            mockRunning = false
            mockSocketPath?.let { path ->
                val socketFile = File(path)
                if (socketFile.exists()) {
                    socketFile.delete()
                }
            }
            mockSocketPath = null
            mockSurfaceAttached = false
        }
    }

    fun getStatus(): ServerStatus {
        return ServerStatus(
            isRunning = isRunning(),
            socketPath = mockSocketPath,
            width = mockWidth,
            height = mockHeight,
            scale = mockScale,
            activeClients = getActiveClients(),
            tabletV2Supported = isTabletV2Supported()
        )
    }
}
