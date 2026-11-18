package com.alw.workstation.bridge.wayland

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.AttributeSet
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.Choreographer
import android.view.SurfaceView
import com.alw.workstation.bridge.wayland.input.DefaultMotionEventStylusProvider
import com.alw.workstation.bridge.wayland.input.InputProfile
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

    private var vsyncLoopActive = false

    private val vsyncCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!vsyncLoopActive) return
            serverManager?.notifyVsync(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startVsyncLoop() {
        if (vsyncLoopActive) return
        vsyncLoopActive = true
        Choreographer.getInstance().postFrameCallback(vsyncCallback)
    }

    private fun stopVsyncLoop() {
        vsyncLoopActive = false
        Choreographer.getInstance().removeFrameCallback(vsyncCallback)
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

    var inputProfile: InputProfile = InputProfile.CREATIVE_PEN
        set(value) {
            field = value
            inputDispatcher?.inputProfile = value
            applyInputProfile(value)
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
        displayManager?.registerDisplayListener(displayListener, null)
        display?.let { pushRefreshRate(it) }
    }

    override fun onDetachedFromWindow() {
        displayManager?.unregisterDisplayListener(displayListener)
        stopVsyncLoop()
        super.onDetachedFromWindow()
    }

    private fun pushRefreshRate(display: Display, applySurfaceFrameRate: Boolean = false) {
        val hz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display.refreshRate else 60f
        serverManager?.setRefreshRate(hz)
        if (!applySurfaceFrameRate || !holder.surface.isValid) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                holder.surface.setFrameRate(hz, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            applyInputProfile(inputProfile)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        display?.let { pushRefreshRate(it, applySurfaceFrameRate = true) }
        serverManager?.attachSurface(holder.surface)
        startVsyncLoop()
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
        stopVsyncLoop()
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
        return inputDispatcher?.handleMotionEvent(event, width, height) ?: super.onGenericMotionEvent(event)
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
