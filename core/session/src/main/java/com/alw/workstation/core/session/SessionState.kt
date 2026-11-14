package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile

sealed interface SessionState {
    data object Idle : SessionState
    data class Provisioning(val progressPercent: Int, val message: String) : SessionState
    data class Starting(val profileId: String) : SessionState
    data class Running(val profile: StackProfile, val uptimeMillis: Long) : SessionState
    data class SafeModeFallback(
        val originalProfile: StackProfile,
        val fallbackProfile: StackProfile,
        val reason: String
    ) : SessionState
    data class Error(val message: String, val cause: Throwable? = null) : SessionState
    data object Stopped : SessionState
}
