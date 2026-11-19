package com.alw.workstation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.alw.workstation.bridge.wayland.audio.AudioBridgeManager
import com.alw.workstation.bridge.wayland.InputDispatcher
import com.alw.workstation.bridge.wayland.WaylandServerManager
import com.alw.workstation.core.proot.ProotProcessManager
import com.alw.workstation.core.proot.ProotRunner
import com.alw.workstation.core.proot.ProotSessionLauncher
import com.alw.workstation.core.proot.RootfsExtractor
import com.alw.workstation.core.proot.RootfsManager
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.driver.GitHubReleaseFetcher
import com.alw.workstation.core.proot.driver.TurnipDriverManager
import com.alw.workstation.core.proot.provisioning.ArchProvisioner
import com.alw.workstation.core.proot.shizuku.ShizukuCompanionManager
import com.alw.workstation.core.session.CompositorOrchestrator
import com.alw.workstation.core.session.ProfileRepository
import com.alw.workstation.core.session.SessionController
import com.alw.workstation.core.session.SessionState
import com.alw.workstation.service.WorkstationService
import com.alw.workstation.service.AccessibilityHealer
import com.alw.workstation.ui.configurator.StackConfiguratorScreen
import com.alw.workstation.ui.configurator.StackConfiguratorViewModel
import com.alw.workstation.ui.dashboard.DashboardScreen
import com.alw.workstation.ui.dashboard.DashboardViewModel
import com.alw.workstation.ui.driverstore.DriverStoreScreen
import com.alw.workstation.ui.driverstore.DriverStoreViewModel
import com.alw.workstation.ui.session.SessionSurfaceScreen
import com.alw.workstation.ui.terminal.TerminalScreen
import com.alw.workstation.ui.terminal.TerminalViewModel
import com.alw.workstation.ui.theme.WorkstationTheme
import com.alw.workstation.ui.wizard.WizardScreen
import com.alw.workstation.ui.wizard.WizardViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class AppScreen {
    WIZARD,
    DASHBOARD,
    SESSION,
    CONFIGURATOR,
    DRIVER_STORE,
    TERMINAL
}

class MainActivity : ComponentActivity() {

    private lateinit var dirs: RuntimeDirectories
    private lateinit var rootfsManager: RootfsManager
    private lateinit var rootfsExtractor: RootfsExtractor
    private lateinit var driverManager: TurnipDriverManager
    private lateinit var releaseFetcher: GitHubReleaseFetcher
    private lateinit var profileRepository: ProfileRepository
    private lateinit var sessionController: SessionController
    private lateinit var prootRunner: ProotRunner
    private lateinit var processManager: ProotProcessManager
    private lateinit var provisioner: ArchProvisioner
    private lateinit var sessionLauncher: com.alw.workstation.core.session.SessionProcessLauncher
    private lateinit var orchestrator: CompositorOrchestrator
    private lateinit var audioBridgeManager: AudioBridgeManager
    private lateinit var serverManager: WaylandServerManager
    private lateinit var inputDispatcher: InputDispatcher
    private lateinit var shizukuCompanionManager: ShizukuCompanionManager
    private lateinit var accessibilityHealer: AccessibilityHealer

    private val healerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var accessibilityNotice by mutableStateOf<AccessibilityHealer.HealResult.NeedsAdb?>(null)

    private lateinit var wizardViewModel: WizardViewModel
    private lateinit var dashboardViewModel: DashboardViewModel
    private lateinit var stackConfiguratorViewModel: StackConfiguratorViewModel
    private lateinit var driverStoreViewModel: DriverStoreViewModel
    private lateinit var terminalViewModel: TerminalViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure transparent window background so embedded SurfaceView punches through
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        // Initialize runtime directories and engines
        dirs = RuntimeDirectories(
            filesDir = filesDir,
            nativeLibDir = applicationInfo.nativeLibraryDir
        )
        dirs.ensureDirectoriesExist()

        rootfsManager = RootfsManager(dirs)
        android.util.Log.i("ALW_CHECK", "hasAnyRootfs = ${rootfsManager.hasAnyRootfs()}, rootfsBaseDir = ${dirs.rootfsBaseDir.absolutePath}, listFiles = ${dirs.rootfsBaseDir.listFiles()?.map { it.name }}")
        rootfsExtractor = RootfsExtractor(dirs)
        releaseFetcher = GitHubReleaseFetcher()
        driverManager = TurnipDriverManager(dirs, releaseFetcher)
        profileRepository = ProfileRepository(filesDir)
        sessionController = SessionController(profileRepository)

        prootRunner = ProotRunner(dirs)
        processManager = ProotProcessManager()
        provisioner = ArchProvisioner(dirs, rootfsManager, prootRunner, processManager)
        sessionLauncher = com.alw.workstation.launcher.PtySessionLauncher(prootRunner)
        orchestrator = CompositorOrchestrator(sessionController, sessionLauncher)

        audioBridgeManager = AudioBridgeManager()
        serverManager = WaylandServerManager()
        inputDispatcher = InputDispatcher(serverManager)

        // Initialize optional Shizuku Companion (UID 2000)
        shizukuCompanionManager = ShizukuCompanionManager(context = this)
        shizukuCompanionManager.registerListeners()

        // TASK-027: keep the key-intercept AccessibilityService enabled
        accessibilityHealer = AccessibilityHealer(this, shizukuCompanionManager)

        wizardViewModel = WizardViewModel(
            dirs = dirs,
            rootfsManager = rootfsManager,
            rootfsExtractor = rootfsExtractor,
            driverManager = driverManager,
            releaseFetcher = releaseFetcher,
            provisioner = provisioner,
            profileRepository = profileRepository
        )

        dashboardViewModel = DashboardViewModel(
            dirs = dirs,
            rootfsManager = rootfsManager,
            rootfsExtractor = rootfsExtractor,
            driverManager = driverManager,
            profileRepository = profileRepository,
            sessionController = sessionController,
            orchestrator = orchestrator,
            audioBridgeManager = audioBridgeManager,
            shizukuManager = shizukuCompanionManager,
            serverManager = serverManager,
            appContext = applicationContext,
            sessionWindowProvider = { window },
            guestProcessReaper = com.alw.workstation.core.session.GuestProcessReaper()
        )

        stackConfiguratorViewModel = StackConfiguratorViewModel(
            dirs = dirs,
            profileRepository = profileRepository,
            rootfsManager = rootfsManager,
            driverManager = driverManager
        )

        driverStoreViewModel = DriverStoreViewModel(
            dirs = dirs,
            driverManager = driverManager,
            releaseFetcher = releaseFetcher
        )

        terminalViewModel = TerminalViewModel(
            dirs = dirs,
            rootfsManager = rootfsManager,
            rootfsExtractor = rootfsExtractor,
            prootRunner = prootRunner,
            profileRepository = profileRepository
        )

        setContent {
            WorkstationTheme {
                WorkstationAppRoot(
                    wizardViewModel = wizardViewModel,
                    dashboardViewModel = dashboardViewModel,
                    stackConfiguratorViewModel = stackConfiguratorViewModel,
                    driverStoreViewModel = driverStoreViewModel,
                    terminalViewModel = terminalViewModel,
                    serverManager = serverManager,
                    inputDispatcher = inputDispatcher,
                    rootfsManager = rootfsManager,
                    onStartService = { startWorkstationService() },
                    onStopService = { stopWorkstationService() },
                    accessibilityNotice = accessibilityNotice,
                    onDismissAccessibilityNotice = { accessibilityNotice = null }
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (::inputDispatcher.isInitialized && sessionController.sessionState.value is SessionState.Running) {
            if (inputDispatcher.handleKeyEvent(event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setSessionRefreshRate(rateHz: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val params = window.attributes
            params.preferredRefreshRate = rateHz
            window.attributes = params
        }
    }

    private fun startWorkstationService() {
        setSessionRefreshRate(144.0f)
        val intent = Intent(this, WorkstationService::class.java)
        startForegroundService(intent)
    }

    private fun stopWorkstationService() {
        setSessionRefreshRate(0.0f)
        val intent = Intent(this, WorkstationService::class.java)
        stopService(intent)
    }

    override fun onResume() {
        super.onResume()
        healerScope.launch {
            val result = accessibilityHealer.heal()
            accessibilityNotice = result as? AccessibilityHealer.HealResult.NeedsAdb
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        healerScope.cancel()
        if (::dashboardViewModel.isInitialized) {
            dashboardViewModel.destroy()
        }
        shizukuCompanionManager.unregisterListeners()
    }
}

@Composable
fun WorkstationAppRoot(
    wizardViewModel: WizardViewModel,
    dashboardViewModel: DashboardViewModel,
    stackConfiguratorViewModel: StackConfiguratorViewModel,
    driverStoreViewModel: DriverStoreViewModel,
    terminalViewModel: TerminalViewModel,
    serverManager: WaylandServerManager,
    inputDispatcher: InputDispatcher,
    rootfsManager: RootfsManager,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    accessibilityNotice: AccessibilityHealer.HealResult.NeedsAdb?,
    onDismissAccessibilityNotice: () -> Unit
) {
    val dashboardState by dashboardViewModel.uiState.collectAsState()

    var currentScreen by remember {
        val initialScreen = if (!rootfsManager.hasAnyRootfs()) {
            AppScreen.WIZARD
        } else {
            AppScreen.DASHBOARD
        }
        mutableStateOf(initialScreen)
    }

    val surfaceColor = if (currentScreen == AppScreen.SESSION) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background
    Column(modifier = Modifier.fillMaxSize()) {
        accessibilityNotice?.let { notice ->
            AccessibilityGrantBanner(
                grantCommand = notice.grantCommand,
                manualCommands = notice.manualCommands,
                onDismiss = onDismissAccessibilityNotice
            )
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = surfaceColor
        ) {
            when (currentScreen) {
                AppScreen.WIZARD -> {
                    WizardScreen(
                        viewModel = wizardViewModel,
                        onComplete = {
                            dashboardViewModel.refresh()
                            currentScreen = AppScreen.DASHBOARD
                        }
                    )
                }
                AppScreen.DASHBOARD -> {
                    DashboardScreen(
                        viewModel = dashboardViewModel,
                        onLaunchSession = {
                            onStartService()
                            currentScreen = AppScreen.SESSION
                        },
                        onOpenWizard = {
                            currentScreen = AppScreen.WIZARD
                        },
                        onOpenTerminal = {
                            terminalViewModel.refreshRootfsStatus()
                            currentScreen = AppScreen.TERMINAL
                        },
                        onOpenConfigurator = { profileId ->
                            stackConfiguratorViewModel.loadProfile(profileId)
                            currentScreen = AppScreen.CONFIGURATOR
                        },
                        onOpenDriverStore = {
                            driverStoreViewModel.refresh()
                            currentScreen = AppScreen.DRIVER_STORE
                        }
                    )
                }
                AppScreen.SESSION -> {
                    SessionSurfaceScreen(
                        dashboardViewModel = dashboardViewModel,
                        serverManager = serverManager,
                        inputDispatcher = inputDispatcher,
                        onStopSession = {
                            onStopService()
                            currentScreen = AppScreen.DASHBOARD
                        }
                    )
                }
                AppScreen.CONFIGURATOR -> {
                    StackConfiguratorScreen(
                        viewModel = stackConfiguratorViewModel,
                        onBack = {
                            dashboardViewModel.refresh()
                            currentScreen = AppScreen.DASHBOARD
                        },
                        onOpenDriverStore = {
                            driverStoreViewModel.refresh()
                            currentScreen = AppScreen.DRIVER_STORE
                        }
                    )
                }
                AppScreen.DRIVER_STORE -> {
                    DriverStoreScreen(
                        viewModel = driverStoreViewModel,
                        onBack = {
                            dashboardViewModel.refresh()
                            currentScreen = AppScreen.DASHBOARD
                        }
                    )
                }
                AppScreen.TERMINAL -> {
                    TerminalScreen(
                        viewModel = terminalViewModel,
                        onBack = {
                            dashboardViewModel.refresh()
                            currentScreen = AppScreen.DASHBOARD
                        },
                        onOpenWizard = {
                            currentScreen = AppScreen.WIZARD
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AccessibilityGrantBanner(
    grantCommand: String,
    manualCommands: List<String>,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "ALW keyboard bridge is not enabled",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    grantCommand,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Run once, then reopen — the key bridge heals itself. " +
                        "Copy also includes the full manual settings block.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = {
                clipboard.setText(
                    AnnotatedString((listOf(grantCommand) + manualCommands).joinToString("\n"))
                )
            }) {
                Text("Copy")
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}
