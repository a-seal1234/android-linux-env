package com.alw.workstation.ui.dashboard

import android.content.Context
import android.util.Log
import android.view.Window
import com.alw.workstation.bridge.wayland.audio.AudioBridgeManager
import com.alw.workstation.bridge.wayland.input.InputProfile
import com.alw.workstation.bridge.wayland.input.SessionInputBus
import com.alw.workstation.core.proot.RootfsExtractor
import com.alw.workstation.core.proot.RootfsManager
import com.alw.workstation.core.proot.RootfsManager.RootfsInfo
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.driver.TurnipDriverManager
import com.alw.workstation.core.proot.shizuku.ShizukuCompanionManager
import com.alw.workstation.core.proot.shizuku.ShizukuCompanionState
import com.alw.workstation.core.session.CompositorOrchestrator
import com.alw.workstation.core.session.GuestProcessReaper
import com.alw.workstation.core.session.ProfileRepository
import com.alw.workstation.core.session.SessionController
import com.alw.workstation.core.session.SessionCleanupCoordinator
import com.alw.workstation.core.session.SessionState
import com.alw.workstation.core.session.isLiveSession
import com.alw.workstation.core.session.model.StackProfile
import com.alw.workstation.core.session.rice.RiceHostBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    val shizukuManager: ShizukuCompanionManager? = null,
    val serverManager: com.alw.workstation.bridge.wayland.WaylandServerManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val appContext: Context? = null,
    private val sessionWindowProvider: (() -> Window?)? = null,
    private val guestProcessReaper: GuestProcessReaper? = null,
    private val sessionCleanupCoordinator: SessionCleanupCoordinator = SessionCleanupCoordinator()
) {
    private var riceHostBridge: RiceHostBridge? = null

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioCoordinateJob: Job? = null
    private var launchJob: Job? = null

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        refresh()
        observeShizukuState()
    }

    private fun observeShizukuState() {
        val manager = shizukuManager ?: return
        sessionScope.launch(ioDispatcher) {
            manager.companionState.collect { companionState ->
                withContext(mainDispatcher) {
                    _uiState.update { it.copy(shizukuState = companionState) }
                }
            }
        }
    }

    fun refresh() {
        val workspaces = rootfsManager.listRootfs()
        val profiles = profileRepository.listProfiles()
        val defaultProfile = profiles.find { it.id == "arch-kde-plasma" } ?: profiles.firstOrNull() ?: StackProfile.defaultArchXiaomiPad8()
        val activeDriver = driverManager.getActiveDriver()
        val sessionState = sessionController.sessionState.value
        val audioStatus = audioBridgeManager.getStatus()
        val currentShizuku = shizukuManager?.companionState?.value ?: _uiState.value.shizukuState

        _uiState.update { current ->
            current.copy(
                workspaces = workspaces,
                profiles = profiles,
                activeProfile = current.activeProfile ?: defaultProfile,
                activeDriver = activeDriver,
                sessionState = sessionState,
                audioStatus = audioStatus,
                shizukuState = currentShizuku,
                isLoading = false,
                errorMessage = null
            )
        }
        shizukuManager?.refreshState()
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

    fun launchSession(scope: CoroutineScope = sessionScope) {
        // TASK-035: never stack a second compositor on one bridge output. A live
        // session is stopped first, and guests orphaned by a previous app instance
        // (installer SIGKILL / crash) are reaped before the new tree spawns. The
        // native server state survives Activity recreation (where a fresh
        // sessionController reads Idle), so it is the authoritative liveness signal.
        val sessionAlive = sessionController.sessionState.value.isLiveSession() ||
            serverManager?.isRunning() == true
        if (sessionAlive) {
            stopSession()
        }
        val reaped = guestProcessReaper?.reapOrphanedGuests().orEmpty()
        if (reaped.isNotEmpty()) {
            Log.i("ALW::Dashboard", "Reaped orphaned guest processes: $reaped")
        }

        val profile = _uiState.value.activeProfile ?: StackProfile.defaultArchXiaomiPad8()
        val rootfsDir = dirs.getRootfsDir(profile.distro.rootfsId)
        val runtimeDir = dirs.getRuntimeSocketDir("session-${profile.id}")

        if (!runtimeDir.exists()) {
            runtimeDir.mkdirs()
        }

        val width = profile.display.customWidth ?: 3200
        val height = profile.display.customHeight ?: 2136
        val scale = profile.display.scaleFactor

        // Start Wayland protocol server
        serverManager?.startServer(runtimeDir, width, height, scale)

        // Start audio bridge and attach to session (one live collector per session launch)
        audioCoordinateJob?.cancel()
        audioCoordinateJob = audioBridgeManager.coordinateWith(sessionController, runtimeDir, scope)

        // TASK-025: expose battery / brightness / volume / network / open to guest rices.
        startRiceHostBridge(runtimeDir)

        sessionController.updateState(SessionState.Running(profile, uptimeMillis = 0L))
        _uiState.update { it.copy(sessionState = SessionState.Running(profile, 0L)) }

        if (orchestrator != null) {
            launchJob = scope.launch(ioDispatcher) {
                val outcome = orchestrator.launchSession(
                    profile = profile,
                    sessionRuntimeDir = runtimeDir,
                    rootfsDir = rootfsDir,
                    onStdoutLine = { Log.i("ALW_Proot", it) },
                    onStderrLine = { Log.e("ALW_Proot", it) }
                )
                withContext(mainDispatcher) {
                    when (outcome) {
                        is CompositorOrchestrator.LaunchOutcome.Success -> {
                            stopRiceHostBridge()
                            serverManager?.stopServer()
                            sessionController.stopSession()
                            refresh()
                        }
                        is CompositorOrchestrator.LaunchOutcome.FallbackTriggered -> {
                            stopRiceHostBridge()
                            serverManager?.stopServer()
                            sessionController.stopSession()
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
                            stopRiceHostBridge()
                            serverManager?.stopServer()
                            sessionController.updateState(SessionState.Error(outcome.message))
                            _uiState.update { it.copy(errorMessage = outcome.message) }
                        }
                    }
                }
            }
        }
    }

    private fun startRiceHostBridge(runtimeDir: File) {
        stopRiceHostBridge()
        val context = appContext ?: return
        val bridge = RiceHostBridge(
            context = context,
            socketFile = File(runtimeDir, RiceHostBridge.SOCKET_NAME),
            sysfsRoot = dirs.fakefsBaseDir,
            windowProvider = sessionWindowProvider,
            graphics = object : com.alw.workstation.core.session.rice.RiceGraphicsControl {
                override fun actualFps(): Float = serverManager?.let {
                    it.refreshGraphicsStats()
                    it.actualFps
                } ?: 0f

                override fun renderScale(): Float = serverManager?.renderScale ?: 0.75f

                override fun setRenderScale(scale: Float) {
                    serverManager?.setRenderScale(scale)
                }

                override fun outputSize(): Pair<Int, Int> {
                    val manager = serverManager ?: return 3200 to 2136
                    val scale = manager.renderScale
                    return (manager.outputWidth * scale).toInt() to
                        (manager.outputHeight * scale).toInt()
                }
            }
        )
        try {
            bridge.start()
            riceHostBridge = bridge
        } catch (e: Throwable) {
            Log.w("ALW::Dashboard", "Rice host bridge start failed: ${e.message}")
        }
    }

    private fun stopRiceHostBridge() {
        riceHostBridge?.stop()
        riceHostBridge = null
    }

    fun stopSession() {
        launchJob?.cancel()
        launchJob = null
        SessionInputBus.setSessionFocused(false)
        serverManager?.clearKeyboardModifiers()
        stopRiceHostBridge()
        serverManager?.stopServer()
        sessionController.stopSession()
        audioBridgeManager.stopAudioServer()
        _uiState.update { it.copy(sessionState = SessionState.Stopped) }
        refresh()
    }

    /**
     * TASK-036 Phase 1: explicit "Close All Sessions". Unlike [stopSession] this also
     * terminates guests orphaned by a crashed/killed app instance and purges stale
     * runtime artifacts (wayland sockets/locks, alw-host.sock, .hyprland.lock).
     */
    fun closeAllSessions() {
        launchJob?.cancel()
        launchJob = null
        SessionInputBus.setSessionFocused(false)
        serverManager?.clearKeyboardModifiers()
        stopRiceHostBridge()
        serverManager?.stopServer()
        sessionController.stopSession()
        audioBridgeManager.stopAudioServer()

        val report = sessionCleanupCoordinator.closeAllSessions(
            listOfNotNull(dirs.fakefsBaseDir.resolve("tmp/rt"))
        )
        if (report.hadWork) {
            Log.i(
                "ALW::Dashboard",
                "Close All Sessions: terminated=${report.terminatedPids} " +
                    "killed=${report.killedPids} purged=${report.purgedArtifacts.size}"
            )
        }
        _uiState.update {
            it.copy(sessionState = SessionState.Idle)
        }
        refresh()
    }

    /**
     * Releases ViewModel-scoped coroutines (launch monitor, Shizuku collector,
     * audio coordinator). Called from `MainActivity.onDestroy`; a running session
     * and its native singletons are intentionally left alive so an Activity
     * recreation re-attaches to them.
     */
    fun destroy() {
        sessionScope.cancel()
    }

    fun switchCompositor(type: StackProfile.CompositorType) {
        val currentProfile = _uiState.value.activeProfile ?: StackProfile.defaultArchXiaomiPad8()
        val exec = when (type) {
            StackProfile.CompositorType.PLASMA -> "startplasma-wayland"
            StackProfile.CompositorType.LABWC -> "labwc"
            StackProfile.CompositorType.HYPRLAND -> "Hyprland"
            StackProfile.CompositorType.CUSTOM -> currentProfile.compositor.execCommand
        }
        val compositor = if (type == StackProfile.CompositorType.HYPRLAND) {
            // Use the synthesized /tmp/android-session.conf (sources the user config and layers
            // the PRoot/bridge overrides: no Xwayland, software cursors, nested Aquamarine env).
            currentProfile.compositor.copy(
                type = type,
                execCommand = exec,
                configMode = StackProfile.ConfigMode.WRAP_AND_OVERRIDE,
                userConfigPath = "/home/${currentProfile.distro.defaultUser}/.config/hypr/hyprland.conf",
                sessionConfigPath = "/tmp/android-session.lua",
                extraArgs = listOf("-c", "/tmp/android-session.lua", "--i-am-really-stupid")
            )
        } else {
            currentProfile.compositor.copy(
                type = type,
                execCommand = exec
            )
        }
        val updated = currentProfile.copy(compositor = compositor)
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

    fun requestShizukuPermission() {
        shizukuManager?.requestPermission()
    }

    fun applyPhantomProcessBypass(scope: CoroutineScope) {
        val manager = shizukuManager ?: return
        scope.launch(ioDispatcher) {
            val success = manager.applyPhantomProcessBypass()
            val msg = if (success) {
                "Phantom Process limit lifted to 2.14B! HyperOS PowerKeeper whitelisted."
            } else {
                manager.companionState.value.lastError ?: "Failed applying Phantom Process bypass"
            }
            withContext(mainDispatcher) {
                _uiState.update { it.copy(errorMessage = msg) }
            }
        }
    }

    fun refreshShizukuStatus() {
        shizukuManager?.refreshState()
    }
}
