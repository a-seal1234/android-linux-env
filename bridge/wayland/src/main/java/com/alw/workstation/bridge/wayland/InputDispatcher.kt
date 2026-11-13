package com.alw.workstation.bridge.wayland

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Normalizes Android hardware input to Linux Wayland protocols.
 * Handles View.requestPointerCapture relative coordinates and scancode remapping.
 */
class InputDispatcher(
    private val serverManager: WaylandServerManager,
    private val rebindCapsToSuper: Boolean = true
) {
    fun handleMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                serverManager.sendPointerDelta(dx, dy)
                return true
            }
        }
        return false
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        var scanCode = event.scanCode
        // Prevent Xiaomi HyperOS Super key interception by remapping CapsLock (58) to Super (125)
        if (rebindCapsToSuper && scanCode == 58) {
            scanCode = 125
        }
        val isDown = event.action == KeyEvent.ACTION_DOWN
        serverManager.sendKey(scanCode, isDown)
        return true
    }
}
