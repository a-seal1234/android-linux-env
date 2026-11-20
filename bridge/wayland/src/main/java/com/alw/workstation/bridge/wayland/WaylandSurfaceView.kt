package com.alw.workstation.bridge.wayland

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.AttributeSet
import android.view.InputDevice
import android.view.Choreographer
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.alw.workstation.bridge.wayland.input.DefaultMotionEventStylusProvider
import com.alw.workstation.bridge.wayland.input.InputProfile
import com.alw.workstation.bridge.wayland.input.SessionInputBus
import com.alw.workstation.bridge.wayland.input.XiaomiPenStylusProvider

/**
 * SurfaceView hosting the embedded Wayland display surface.
 * Intercepts relative pointer capture, hardware key events, and tablet stylus digitizer events.
 */
class WaylandSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    var serverManager: WaylandServerManager? = null
    var inputDispatcher: InputDispatcher? = null
        set(value) {
            field = value
            value?.inputProfile = inputProfile
            applyInputProfile(inputProfile)
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val display = displayManager?.getDisplay(displayId) ?: return
            if (display.displayId == this@WaylandSurfaceView.display?.displayId) {
                pushRefreshRate(display)
            }
        }
    }

    private val displayManager: DisplayManager?
        get() = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    /**
     * Drives the bridge's frame pacing from the host VSync.
     *
     * Without this the native pacer has no presentation clock and falls back to its
     * 250 ms dead-man timer, which caps the whole session at ~4 FPS regardless of
     * rendering work. Re-armed on every frame, on the main thread (the native side
     * must be called with a valid JNIEnv, so an NDK AChoreographer callback is not
     * usable here).
     */
    private val choreographer by lazy { Choreographer.getInstance() }
    @Volatile private var vsyncRunning = false

    private val vsyncCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!vsyncRunning) return
            serverManager?.onVsync(frameTimeNanos)
            choreographer.postFrameCallback(this)
        }
    }

    private fun startVsyncPacing() {
        if (vsyncRunning) return
        vsyncRunning = true
        choreographer.postFrameCallback(vsyncCallback)
    }

    private fun stopVsyncPacing() {
        if (!vsyncRunning) return
        vsyncRunning = false
        choreographer.removeFrameCallback(vsyncCallback)
    }

    var inputProfile: InputProfile = InputProfile.CREATIVE_PEN
        set(value) {
            field = value
            inputDispatcher?.inputProfile = value
            applyInputProfile(value)
        }

    /** True from [surfaceCreated] until [surfaceDestroyed] (TASK-027 focus gating). */
    private var surfaceActive = false

    /**
     * Session-focus gate for the AccessibilityService key bridge: published to
     * [SessionInputBus] so `AlwKeyInterceptService` only consumes keys while this
     * surface is live AND the window holds focus. A focus loss also releases every
     * tracked XKB modifier (stuck-modifier hygiene) and GAMING_RAW pointer capture.
     */
    private fun updateSessionFocus() {
        val focused = surfaceActive && hasWindowFocus()
        val wasFocused = SessionInputBus.isSessionFocused
        SessionInputBus.setSessionFocused(focused)
        if (wasFocused && !focused) {
            serverManager?.clearKeyboardModifiers()
            if (hasPointerCapture()) {
                releasePointerCapture()
            }
        }
    }

    init {
        setZOrderOnTop(true)
        holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        applyInputProfile(inputProfile)
    }

    private fun applyInputProfile(profile: InputProfile) {
        when (profile) {
            InputProfile.CREATIVE_PEN -> {
                // Suppress Android system hover circle cursor so creative apps (Krita/Blender) draw their own cursor
                pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
                inputDispatcher?.stylusProvider = XiaomiPenStylusProvider()
            }
            InputProfile.GAMING_RAW -> {
                pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ARROW)
                inputDispatcher?.stylusProvider = DefaultMotionEventStylusProvider()
                if (hasWindowFocus()) {
                    requestPointerCapture()
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SessionInputBus.keyEventDispatcher = { event ->
            inputDispatcher?.handleKeyEvent(event) ?: false
        }
        displayManager?.registerDisplayListener(displayListener, null)
        display?.let { pushRefreshRate(it) }
    }

    override fun onDetachedFromWindow() {
        SessionInputBus.keyEventDispatcher = null
        displayManager?.unregisterDisplayListener(displayListener)
        super.onDetachedFromWindow()
    }

    /**
     * Session refresh rate pinned by the session host (0 = follow the panel).
     * A fixed rate also makes the native pacer run a clocked 144 Hz cadence instead
     * of only forwarding live VSync ticks.
     */
    @Volatile
    var forcedRefreshRateHz: Float = 0f

    private fun pushRefreshRate(display: Display, applySurfaceFrameRate: Boolean = false) {
        val panelHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display.refreshRate else 60f
        val hz = if (forcedRefreshRateHz > 0f) forcedRefreshRateHz else panelHz
        serverManager?.setRefreshRate(hz)
        if (!applySurfaceFrameRate || !holder.surface.isValid) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                holder.surface.setFrameRate(hz, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }

    /** Called by the session screen when the profile's refresh rate changes. */
    fun applyForcedRefreshRate(rateHz: Float) {
        forcedRefreshRateHz = rateHz
        display?.let { pushRefreshRate(it, applySurfaceFrameRate = true) }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        updateSessionFocus()
        if (hasWindowFocus) {
            applyInputProfile(inputProfile)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceActive = true
        updateSessionFocus()
        display?.let { pushRefreshRate(it, applySurfaceFrameRate = true) }
        serverManager?.attachSurface(holder.surface)
        startVsyncPacing()
        if (hasWindowFocus()) {
            applyInputProfile(inputProfile)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        display?.let { pushRefreshRate(it, applySurfaceFrameRate = true) }
        serverManager?.resizeSurface(width, height)
        serverManager?.attachSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceActive = false
        updateSessionFocus()
        stopVsyncPacing()
        serverManager?.detachSurface()
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!hasFocus()) requestFocus()
        return inputDispatcher?.handleMotionEvent(event, width, height) ?: super.onHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasFocus()) requestFocus()
        return inputDispatcher?.handleMotionEvent(event, width, height) ?: super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!hasFocus()) requestFocus()
        // Deliver mouse/hover moves as soon as they arrive instead of batching them to
        // the next VSync: cuts the pointer-to-photon latency on high-refresh panels.
        if (event.isPointerMotion()) {
            requestUnbufferedDispatch(event)
        }
        return inputDispatcher?.handleMotionEvent(event, width, height) ?: super.onGenericMotionEvent(event)
    }

    private fun MotionEvent.isPointerMotion(): Boolean {
        val isMouse = (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
        if (!isMouse) return false
        return action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        if (!hasFocus()) requestFocus()
        return inputDispatcher?.handleMotionEvent(event, width, height) ?: super.onCapturedPointerEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return inputDispatcher?.handleKeyEvent(event) ?: super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return inputDispatcher?.handleKeyEvent(event) ?: super.onKeyUp(keyCode, event)
    }
}
