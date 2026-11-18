package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * High-level workstation session lifecycle manager and orchestrator.
 */
class SessionController(
    private val profileRepository: ProfileRepository? = null
) {

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var activeProfile: StackProfile? = null
    private var sessionStartTime: Long = 0L

    fun getActiveProfile(): StackProfile? = activeProfile

    fun updateState(state: SessionState) {
        _sessionState.value = state
        if (state is SessionState.Running) {
            activeProfile = state.profile
            sessionStartTime = System.currentTimeMillis()
        } else if (state is SessionState.Stopped || state is SessionState.Idle) {
            activeProfile = null
            sessionStartTime = 0L
        }
    }

    /**
     * Prepares session artifacts non-destructively.
     * Returns the synthesized session config string (e.g. /tmp/android-session.lua).
     */
    fun prepareSession(profile: StackProfile): Result<String> {
        val validation = ProfileValidator.validate(profile)
        if (validation is ProfileValidator.ValidationResult.Invalid) {
            val err = "Cannot prepare session with invalid profile: ${validation.errors.joinToString()}"
            updateState(SessionState.Error(err))
            return Result.failure(IllegalArgumentException(err))
        }

        updateState(SessionState.Starting(profile.id))

        val configContent = if (profile.compositor.type == StackProfile.CompositorType.HYPRLAND) {
            ConfigSynthesizer.synthesizeHyprlandSessionConfig(profile)
        } else {
            "# Non-Hyprland session: ${profile.compositor.type}\n"
        }

        return Result.success(configContent)
    }

    fun generateEnvironment(profile: StackProfile, runtimeDir: File): Map<String, String> {
        return EnvironmentGenerator.generateEnvironment(profile, runtimeDir)
    }

    fun stopSession() {
        updateState(SessionState.Stopped)
    }
}
