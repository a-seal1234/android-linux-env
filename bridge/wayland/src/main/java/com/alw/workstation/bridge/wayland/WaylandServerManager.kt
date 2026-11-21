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

    data class MockTabletState(
        var toolType: Int = 1,
        var inProximity: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
        var pressure: Int = 0,
        var tiltXDeg: Float = 0f,
        var tiltYDeg: Float = 0f,
        var distance: Float = 0f,
        var rotationDeg: Float = 0f,
        var isDown: Boolean = false,
        var buttonMask: Int = 0
    )

    data class MockPointerState(
        var x: Float = 0f,
        var y: Float = 0f,
        var dx: Float = 0f,
        var dy: Float = 0f,
        var buttonMask: Int = 0,
        var lastAxis: Int = 0,
        var lastAxisValue: Float = 0f
    )

    data class MockKeyRecord(
        val scanCode: Int,
        val isDown: Boolean
    )

    data class MockTouchRecord(
        val action: Int,
        val touchId: Int,
        val x: Float,
        val y: Float
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
    private external fun nativeSetRefreshRate(hz: Float)
    private external fun nativeSetRenderScale(scale: Float)
    private external fun nativeGetRenderScale(): Float
    private external fun nativeOnVsync(frameTimeNanos: Long)
    private external fun nativeGetActualFps(): Float
    private external fun nativeIsRunning(): Boolean
    private external fun nativeGetActiveClients(): Int
    private external fun nativeIsTabletV2Supported(): Boolean

    // Tablet-v2 Stylus JNI Native Declarations
    private external fun nativeSendTabletProximity(toolType: Int, inProximity: Boolean)
    private external fun nativeSendTabletMotion(
        x: Float,
        y: Float,
        pressure: Int,
        tiltXDeg: Float,
        tiltYDeg: Float,
        distance: Float,
        rotationDeg: Float
    )
    private external fun nativeSendTabletButton(buttonCode: Int, isDown: Boolean)
    private external fun nativeSendTabletDown(x: Float, y: Float, pressure: Int)
    private external fun nativeSendTabletUp()

    // Pointer, Keyboard & Touch JNI Native Declarations
    private external fun nativeSendPointerMotion(dx: Float, dy: Float)
    private external fun nativeSendPointerAbsolute(x: Float, y: Float)
    private external fun nativeSendPointerButton(buttonCode: Int, isDown: Boolean)
    private external fun nativeSendPointerAxis(axis: Int, value: Float)
    private external fun nativeSendKeyEvent(scanCode: Int, isDown: Boolean, metaState: Int)
    private external fun nativeSendTouchEvent(action: Int, touchId: Int, x: Float, y: Float)
    private external fun nativeClearKeyboardModifiers()
    private external fun nativeStopServer()

    // Host JVM Mock State (active when isNativeLoaded is false)
    private var mockRunning = false
    private var mockSocketPath: String? = null
    private var mockWidth = 3200
    private var mockHeight = 2136
    private var mockScale = 1.75f
    private var mockRefreshRateHz = 60f
    private var mockSurfaceAttached = false
    var mockVsyncTicks = 0
        private set

    val mockTablet = MockTabletState()
    val mockPointer = MockPointerState()
    val mockKeyEvents = mutableListOf<MockKeyRecord>()
    val mockTouchEvents = mutableListOf<MockTouchRecord>()

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
        outputWidth = width
        outputHeight = height
        if (isNativeLoaded) {
            nativeResizeSurface(width, height)
        }
    }

    /**
     * Forwards the host display's current refresh rate so the native bridge paces
     * wl_callback.done at the live VSync cadence (60-144 Hz) and reports it in wl_output.
     */
    /** Measured completed frame blits per second (GPU presenter or CPU fallback). */
    var actualFps: Float = 0f
        private set

    /** Dynamic render scale applied to the guest output (0.35 .. 1.0). */
    var renderScale: Float = 1.0f
        private set

    /** Last surface size reported by the host view. */
    var outputWidth: Int = 3200
        private set
    var outputHeight: Int = 2136
        private set

    fun setRenderScale(scale: Float) {
        val clamped = scale.coerceIn(0.35f, 1.0f)
        renderScale = clamped
        if (isNativeLoaded) {
            nativeSetRenderScale(clamped)
        }
    }

    fun refreshGraphicsStats() {
        if (!isNativeLoaded) return
        actualFps = nativeGetActualFps()
        renderScale = nativeGetRenderScale()
    }

    /**
     * Forwards a host VSync tick so the native bridge can flush wl_callback.done at the
     * real presentation cadence. Without these ticks the pacer falls back to a 250 ms
     * timer, capping the session at ~4 FPS.
     */
    fun onVsync(frameTimeNanos: Long) {
        if (isNativeLoaded) {
            nativeOnVsync(frameTimeNanos)
        } else {
            mockVsyncTicks++
        }
    }

    fun setRefreshRate(hz: Float) {
        mockRefreshRateHz = if (hz > 0f) hz else 60f
        if (isNativeLoaded) {
            nativeSetRefreshRate(mockRefreshRateHz)
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

    // Tablet-v2 Public API
    fun sendTabletProximity(toolType: Int, inProximity: Boolean) {
        if (isNativeLoaded) {
            nativeSendTabletProximity(toolType, inProximity)
        } else {
            mockTablet.toolType = toolType
            mockTablet.inProximity = inProximity
        }
    }

    fun sendTabletMotion(
        x: Float,
        y: Float,
        pressure: Int,
        tiltXDeg: Float,
        tiltYDeg: Float,
        distance: Float,
        rotationDeg: Float
    ) {
        if (isNativeLoaded) {
            nativeSendTabletMotion(x, y, pressure, tiltXDeg, tiltYDeg, distance, rotationDeg)
        } else {
            mockTablet.x = x
            mockTablet.y = y
            mockTablet.pressure = pressure
            mockTablet.tiltXDeg = tiltXDeg
            mockTablet.tiltYDeg = tiltYDeg
            mockTablet.distance = distance
            mockTablet.rotationDeg = rotationDeg
        }
    }

    fun sendTabletButton(buttonCode: Int, isDown: Boolean) {
        if (isNativeLoaded) {
            nativeSendTabletButton(buttonCode, isDown)
        } else {
            if (isDown) {
                mockTablet.buttonMask = mockTablet.buttonMask or buttonCode
            } else {
                mockTablet.buttonMask = mockTablet.buttonMask and buttonCode.inv()
            }
        }
    }

    fun sendTabletDown(x: Float, y: Float, pressure: Int) {
        if (isNativeLoaded) {
            nativeSendTabletDown(x, y, pressure)
        } else {
            mockTablet.x = x
            mockTablet.y = y
            mockTablet.pressure = pressure
            mockTablet.isDown = true
        }
    }

    fun sendTabletUp() {
        if (isNativeLoaded) {
            nativeSendTabletUp()
        } else {
            mockTablet.isDown = false
            mockTablet.pressure = 0
        }
    }

    // Pointer, Keyboard & Touch Public API
    fun sendPointerAbsolute(x: Float, y: Float) {
        if (isNativeLoaded) {
            nativeSendPointerAbsolute(x, y)
        } else {
            mockPointer.x = x
            mockPointer.y = y
        }
    }

    fun sendPointerDelta(dx: Float, dy: Float) {
        if (isNativeLoaded) {
            nativeSendPointerMotion(dx, dy)
        } else {
            mockPointer.dx = dx
            mockPointer.dy = dy
        }
    }

    fun sendPointerButton(buttonCode: Int, isDown: Boolean) {
        if (isNativeLoaded) {
            nativeSendPointerButton(buttonCode, isDown)
        } else {
            if (isDown) {
                mockPointer.buttonMask = mockPointer.buttonMask or buttonCode
            } else {
                mockPointer.buttonMask = mockPointer.buttonMask and buttonCode.inv()
            }
        }
    }

    fun sendPointerAxis(axis: Int, value: Float) {
        if (isNativeLoaded) {
            nativeSendPointerAxis(axis, value)
        } else {
            mockPointer.lastAxis = axis
            mockPointer.lastAxisValue = value
        }
    }

    fun sendKey(scanCode: Int, isDown: Boolean, metaState: Int = 0) {
        if (isNativeLoaded) {
            nativeSendKeyEvent(scanCode, isDown, metaState)
        } else {
            mockKeyEvents.add(MockKeyRecord(scanCode, isDown))
        }
    }

    fun sendTouchEvent(action: Int, touchId: Int, x: Float, y: Float) {
        if (isNativeLoaded) {
            nativeSendTouchEvent(action, touchId, x, y)
        } else {
            mockTouchEvents.add(MockTouchRecord(action, touchId, x, y))
        }
    }

    /**
     * Releases every tracked depressed XKB modifier (Shift 0x1, Ctrl 0x4, Alt 0x8,
     * Super 0x40) and re-broadcasts `wl_keyboard.modifiers`. Used on session focus
     * loss so a modifier key-up swallowed before the event reached us can never
     * leave a stuck bit in the guest (TASK-027).
     */
    fun clearKeyboardModifiers() {
        if (isNativeLoaded) {
            nativeClearKeyboardModifiers()
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
