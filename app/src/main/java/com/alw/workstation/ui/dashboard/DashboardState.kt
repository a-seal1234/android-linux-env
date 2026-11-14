package com.alw.workstation.ui.dashboard

import com.alw.workstation.bridge.wayland.audio.AudioStatus
import com.alw.workstation.bridge.wayland.input.InputProfile
import com.alw.workstation.core.proot.RootfsManager.RootfsInfo
import com.alw.workstation.core.proot.driver.TurnipDriverManifest
import com.alw.workstation.core.session.SessionState
import com.alw.workstation.core.session.model.StackProfile

data class DashboardUiState(
    val workspaces: List<RootfsInfo> = emptyList(),
    val profiles: List<StackProfile> = emptyList(),
    val activeProfile: StackProfile? = null,
    val activeDriver: TurnipDriverManifest? = null,
    val sessionState: SessionState = SessionState.Idle,
    val audioStatus: AudioStatus = AudioStatus(
        isRunning = false,
        isNullSink = false,
        sampleRate = 48000,
        channels = 2,
        socketPath = null,
        latencyMs = 0f
    ),
    val inputProfile: InputProfile = InputProfile.CREATIVE_PEN,
    val showCreateDialog: Boolean = false,
    val showCloneDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val selectedWorkspace: RootfsInfo? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
