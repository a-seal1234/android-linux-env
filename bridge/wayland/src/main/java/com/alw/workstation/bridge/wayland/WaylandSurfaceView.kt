package com.alw.workstation.bridge.wayland

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * SurfaceView hosting the embedded Wayland display surface.
 * Intercepts relative pointer capture and hardware key events.
 */
class WaylandSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    var serverManager: WaylandServerManager? = null
    var inputDispatcher: InputDispatcher? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        serverManager?.attachSurface(holder.surface)
        if (hasWindowFocus()) {
            requestPointerCapture()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        serverManager?.attachSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        serverManager?.detachSurface()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return inputDispatcher?.handleMotionEvent(event) ?: super.onGenericMotionEvent(event)
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        return inputDispatcher?.handleMotionEvent(event) ?: super.onCapturedPointerEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return inputDispatcher?.handleKeyEvent(event) ?: super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return inputDispatcher?.handleKeyEvent(event) ?: super.onKeyUp(keyCode, event)
    }
}
