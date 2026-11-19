package com.alw.workstation.bridge.wayland.input

import android.view.KeyEvent

/**
 * Process-wide hub between the AccessibilityService key filter (TASK-027) and the
 * live Wayland session.
 *
 * `AlwKeyInterceptService.onKeyEvent` reads [isSessionFocused] and forwards events
 * to [keyEventDispatcher]; `WaylandSurfaceView` owns both sides — it publishes the
 * focus state from its surface/window lifecycle and registers its InputDispatcher
 * as the event sink. When no session surface is focused the service must return
 * `false` immediately so HyperOS keeps handling Super / Alt+Tab / Back.
 */
object SessionInputBus {

    @Volatile
    var isSessionFocused: Boolean = false
        private set

    @Volatile
    var keyEventDispatcher: ((KeyEvent) -> Boolean)? = null

    fun setSessionFocused(focused: Boolean) {
        isSessionFocused = focused
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isSessionFocused) return false
        return keyEventDispatcher?.invoke(event) ?: false
    }
}
