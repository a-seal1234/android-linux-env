package com.alw.workstation.ui.dashboard

import com.alw.workstation.bridge.wayland.audio.AudioBridgeManager
import com.alw.workstation.bridge.wayland.input.InputProfile
import com.alw.workstation.core.proot.RootfsExtractor
import com.alw.workstation.core.proot.RootfsManager
import com.alw.workstation.core.proot.RootfsManager.RootfsInfo
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.driver.TurnipDriverManager
import com.alw.workstation.core.session.CompositorOrchestrator
import com.alw.workstation.core.session.ProfileRepository
import com.alw.workstation.core.session.SessionController
import com.alw.workstation.core.session.SessionState
import com.alw.workstation.core.session.model.StackProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coordinates workstation workspace management, StackProfile configuration,
 * audio telemetry, and session lifecycle from the primary Dashboard.
 */
class DashboardViewModel(
    private val dirs: RuntimeDirectories,
    private val rootfsManager: RootfsManager = RootfsManager(dirs),
    private val rootfsExtractor: RootfsExtractor = RootfsExtractor(dirs),
    private val driverManager: TurnipDriverManager = TurnipDriverManager(dirs),
    private val profileRepository: ProfileRepository = ProfileRepository(dirs.filesDir),
    val sessionController: SessionController = SessionController(profileRepository),
    private val orchestrator: CompositorOrchestrator? = null,
    val audioBridgeManager: AudioBridgeManager = AudioBridgeManager(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val workspaces = rootfsManager.listRootfs()
        val profiles = profileRepository.listProfiles()
        val defaultProfile = profiles.firstOrNull() ?: StackProfile.defaultArchXiaomiPad8()
        val activeDriver = driverManager.getActiveDriver()
        val sessionState = sessionController.sessionState.value
        val audioStatus = audioBridgeManager.getStatus()

        _uiState.update { current ->
            current.copy(
                workspaces = workspaces,
                profiles = profiles,
                activeProfile = current.activeProfile ?: defaultProfile,
                activeDriver = activeDriver,
                sessionState = sessionState,
                audioStatus = audioStatus,
                isLoading = false,
                errorMessage = null
            )
        }
    }

    fun selectProfile(profile: StackProfile) {
        _uiState.update { it.copy(activeProfile = profile) }
    }

    fun switchInputProfile(profile: InputProfile) {
        _uiState.update { it.copy(inputProfile = profile) }
    }

    fun openCreateDialog() {
        _uiState.update { it.copy(showCreateDialog = true) }
    }

    fun dismissCreateDialog() {
        _uiState.update { it.copy(showCreateDialog = false) }
    }

    fun createWorkspace(id: String, baseArchive: File? = null) {
        try {
            val targetDir = rootfsManager.createRootfs(id)
            if (baseArchive != null && baseArchive.exists()) {
                val result = rootfsExtractor.extractArchiveFile(baseArchive, targetDir)
                if (!result.isSuccess) {
                    throw IllegalStateException("Failed to extract base archive: ${result.errorMessage}")
                }
            }
            rootfsManager.markProvisioned(id)
            dismissCreateDialog()
            refresh()
        } catch (e: Throwable) {
            _uiState.update { it.copy(errorMessage = "Failed creating workspace: ${e.message}") }
        }
    }

    fun openCloneDialog(workspace: RootfsInfo) {
        _uiState.update { it.copy(showCloneDialog = true, selectedWorkspace = workspace) }
    }

    fun dismissCloneDialog() {
        _uiState.update { it.copy(showCloneDialog = false, selectedWorkspace = null) }
    }

    fun cloneWorkspace(sourceId: String, targetId: String) {
        try {
            rootfsManager.cloneRootfs(sourceId, targetId)
            dismissCloneDialog()
            refresh()
        } catch (e: Throwable) {
            _uiState.update { it.copy(errorMessage = "Failed cloning workspace: ${e.message}") }
        }
    }

    fun openDeleteDialog(workspace: RootfsInfo) {
        _uiState.update { it.copy(showDeleteDialog = true, selectedWorkspace = workspace) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false, selectedWorkspace = null) }
    }

    fun deleteWorkspace(id: String) {
        try {
            rootfsManager.deleteRootfs(id)
            dismissDeleteDialog()
            refresh()
        } catch (e: Throwable) {
            _uiState.update { it.copy(errorMessage = "Failed deleting workspace: ${e.message}") }
        }
    }

    fun launchSession(scope: CoroutineScope) {
        val profile = _uiState.value.activeProfile ?: StackProfile.defaultArchXiaomiPad8()
        val rootfsDir = dirs.getRootfsDir(profile.distro.rootfsId)
        val runtimeDir = dirs.getRuntimeSocketDir("session-${profile.id}")

        if (!runtimeDir.exists()) {
            runtimeDir.mkdirs()
        }

        // Start audio bridge and attach to session
        audioBridgeManager.coordinateWith(sessionController, runtimeDir, scope)

        sessionController.updateState(SessionState.Running(profile, uptimeMillis = 0L))
        _uiState.update { it.copy(sessionState = SessionState.Running(profile, 0L)) }

        if (orchestrator != null) {
            scope.launch(ioDispatcher) {
                val outcome = orchestrator.launchSession(
                    profile = profile,
                    sessionRuntimeDir = runtimeDir,
                    rootfsDir = rootfsDir
                )
                withContext(mainDispatcher) {
                    when (outcome) {
                        is CompositorOrchestrator.LaunchOutcome.Success -> {
                            sessionController.stopSession()
                            refresh()
                        }
                        is CompositorOrchestrator.LaunchOutcome.FallbackTriggered -> {
                            _uiState.update {
                                it.copy(
                                    sessionState = SessionState.SafeModeFallback(
                                        outcome.originalProfile,
                                        outcome.fallbackProfile,
                                        outcome.reason
                                    )
                                )
                            }
                        }
                        is CompositorOrchestrator.LaunchOutcome.Error -> {
                            sessionController.updateState(SessionState.Error(outcome.message))
                            _uiState.update { it.copy(errorMessage = outcome.message) }
                        }
                    }
                }
            }
        }
    }

    fun stopSession() {
        sessionController.stopSession()
        audioBridgeManager.stopAudioServer()
        _uiState.update { it.copy(sessionState = SessionState.Stopped) }
        refresh()
    }

    fun switchCompositor(type: StackProfile.CompositorType) {
        val currentProfile = _uiState.value.activeProfile ?: StackProfile.defaultArchXiaomiPad8()
        val updated = currentProfile.copy(
            compositor = currentProfile.compositor.copy(type = type)
        )
        profileRepository.saveProfile(updated)
        _uiState.update { it.copy(activeProfile = updated) }
        refresh()
    }

    fun triggerSafeMode() {
        val safeProfile = StackProfile.safeModeProfile()
        profileRepository.saveProfile(safeProfile)
        _uiState.update { it.copy(activeProfile = safeProfile) }
        refresh()
    }

    fun regenerateDefaultDotfiles(scope: CoroutineScope) {
        scope.launch(ioDispatcher) {
            try {
                val profile = _uiState.value.activeProfile ?: StackProfile.defaultArchXiaomiPad8()
                val rootfsDir = dirs.getRootfsDir(profile.distro.rootfsId)
                com.alw.workstation.core.session.ConfigSynthesizer.ensureDefaultConfigsExist(
                    rootfsDir = rootfsDir,
                    defaultUser = profile.distro.defaultUser,
                    profile = profile
                )
                withContext(mainDispatcher) {
                    _uiState.update { it.copy(errorMessage = "Default compositor dotfiles regenerated non-destructively in guest ~/.config/") }
                }
            } catch (e: Exception) {
                withContext(mainDispatcher) {
                    _uiState.update { it.copy(errorMessage = "Failed regenerating dotfiles: ${e.message}") }
                }
            }
        }
    }

    fun sendHyprlandIpc(command: String, scope: CoroutineScope) {
        scope.launch(ioDispatcher) {
            val profile = _uiState.value.activeProfile ?: StackProfile.defaultArchXiaomiPad8()
            val runtimeDir = dirs.getRuntimeSocketDir("session-${profile.id}")
            val client = com.alw.workstation.core.session.HyprlandIpcClient(runtimeDir)
            val result = client.sendCommand(command)
            withContext(mainDispatcher) {
                result.onSuccess { res ->
                    _uiState.update { it.copy(errorMessage = "Hyprland IPC: $res") }
                }.onFailure { err ->
                    _uiState.update { it.copy(errorMessage = "Hyprland IPC error: ${err.message}") }
                }
            }
        }
    }
}
