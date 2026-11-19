package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile

sealed interface SessionState {
    data object Idle : SessionState
    data class Provisioning(val progressPercent: Int, val message: String) : SessionState
    data class Starting(val profileId: String) : SessionState
    data class Running(val profile: StackProfile, val uptimeMillis: Long = 0L) : SessionState
    data class SafeModeFallback(
        val originalProfile: StackProfile,
        val fallbackProfile: StackProfile,
        val reason: String
    ) : SessionState
    data class Error(val message: String, val cause: Throwable? = null) : SessionState
    data object Stopped : SessionState
}

/**
 * True while a desktop session is alive or booting and must never be stacked with
 * a second compositor on the same bridge output (TASK-035).
 */
fun SessionState.isLiveSession(): Boolean = when (this) {
    is SessionState.Starting, is SessionState.Running, is SessionState.SafeModeFallback -> true
    else -> false
}
