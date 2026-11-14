package com.alw.workstation.bridge.wayland

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.alw.workstation.bridge.wayland.input.InputProfile

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
        }

    var inputProfile: InputProfile = InputProfile.CREATIVE_PEN
        set(value) {
            field = value
            inputDispatcher?.inputProfile = value
            applyInputProfile(value)
        }

    init {
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
            }
            InputProfile.GAMING_RAW -> {
                pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ARROW)
                if (hasWindowFocus()) {
                    requestPointerCapture()
                }
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
        serverManager?.attachSurface(holder.surface)
        if (hasWindowFocus()) {
            applyInputProfile(inputProfile)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        serverManager?.resizeSurface(width, height)
        serverManager?.attachSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        serverManager?.detachSurface()
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        return inputDispatcher?.handleMotionEvent(event, width, height) ?: super.onHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return inputDispatcher?.handleMotionEvent(event, width, height) ?: super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return inputDispatcher?.handleMotionEvent(event, width, height) ?: super.onGenericMotionEvent(event)
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        return inputDispatcher?.handleMotionEvent(event, width, height) ?: super.onCapturedPointerEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return inputDispatcher?.handleKeyEvent(event) ?: super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return inputDispatcher?.handleKeyEvent(event) ?: super.onKeyUp(keyCode, event)
    }
}
