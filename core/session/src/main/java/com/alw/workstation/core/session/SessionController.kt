package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-level workstation session lifecycle manager.
 */
class SessionController {

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    fun updateState(state: SessionState) {
        _sessionState.value = state
    }

    fun prepareSession(profile: StackProfile): String {
        return if (profile.compositor.type == StackProfile.CompositorType.HYPRLAND) {
            ConfigSynthesizer.synthesizeHyprlandConfig(profile)
        } else {
            ""
        }
    }
}
