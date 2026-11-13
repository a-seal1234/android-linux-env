package com.alw.workstation.bridge.wayland

import android.view.Surface
import java.io.File

/**
 * Manages native in-process Wayland server lifecycle.
 * Adapted from the embedded surface architecture of Beauty114514/trierarch-app.
 */
class WaylandServerManager {

    companion object {
        init {
            System.loadLibrary("wayland_bridge")
        }
    }

    private external fun nativeInitServer(
        socketDir: String,
        width: Int,
        height: Int,
        scale: Float
    ): Boolean

    private external fun nativeSetSurface(surface: Surface?)
    private external fun nativeSendPointerMotion(dx: Float, dy: Float)
    private external fun nativeSendKeyEvent(scanCode: Int, isDown: Boolean)
    private external fun nativeStopServer()

    fun startServer(socketDir: File, width: Int, height: Int, scale: Float): Boolean {
        if (!socketDir.exists()) {
            socketDir.mkdirs()
        }
        return nativeInitServer(socketDir.absolutePath, width, height, scale)
    }

    fun attachSurface(surface: Surface) {
        nativeSetSurface(surface)
    }

    fun detachSurface() {
        nativeSetSurface(null)
    }

    fun sendPointerDelta(dx: Float, dy: Float) {
        nativeSendPointerMotion(dx, dy)
    }

    fun sendKey(scanCode: Int, isDown: Boolean) {
        nativeSendKeyEvent(scanCode, isDown)
    }

    fun stopServer() {
        nativeStopServer()
    }
}
