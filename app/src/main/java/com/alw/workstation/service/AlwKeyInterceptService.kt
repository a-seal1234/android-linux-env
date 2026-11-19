package com.alw.workstation.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.alw.workstation.bridge.wayland.input.SessionInputBus

/**
 * Session-scoped hardware key interceptor (TASK-027 §2.1).
 *
 * Bound only when the user enables it (auto-healed by [AccessibilityHealer]).
 * While the Wayland session surface is focused, hardware keys are forwarded to
 * the bridge InputDispatcher and consumed so HyperOS cannot steal Super /
 * Alt+Tab / Esc. Outside the session [onKeyEvent] returns `false` immediately
 * and Android keeps full system key handling.
 */
class AlwKeyInterceptService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!SessionInputBus.isSessionFocused) return false
        return SessionInputBus.dispatchKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
