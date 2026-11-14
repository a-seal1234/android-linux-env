package com.alw.workstation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.alw.workstation.core.session.CompositorOrchestrator
import com.alw.workstation.core.session.ProfileRepository
import com.alw.workstation.core.session.SessionController
import com.alw.workstation.core.session.SessionState
import com.alw.workstation.service.WorkstationService
import com.alw.workstation.ui.dashboard.DashboardScreen
import com.alw.workstation.ui.dashboard.DashboardViewModel
import com.alw.workstation.ui.session.SessionSurfaceScreen
import com.alw.workstation.ui.theme.WorkstationTheme
import com.alw.workstation.ui.wizard.WizardScreen
import com.alw.workstation.ui.wizard.WizardViewModel

enum class AppScreen {
    WIZARD,
    DASHBOARD,
    SESSION
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
    private lateinit var sessionLauncher: ProotSessionLauncher
    private lateinit var orchestrator: CompositorOrchestrator
    private lateinit var audioBridgeManager: AudioBridgeManager
    private lateinit var serverManager: WaylandServerManager
    private lateinit var inputDispatcher: InputDispatcher

    private lateinit var wizardViewModel: WizardViewModel
    private lateinit var dashboardViewModel: DashboardViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize runtime directories and engines
        dirs = RuntimeDirectories(
            filesDir = filesDir,
            nativeLibDir = applicationInfo.nativeLibraryDir
        )
        dirs.ensureDirectoriesExist()

        rootfsManager = RootfsManager(dirs)
        rootfsExtractor = RootfsExtractor(dirs)
        releaseFetcher = GitHubReleaseFetcher()
        driverManager = TurnipDriverManager(dirs, releaseFetcher)
        profileRepository = ProfileRepository(filesDir)
        sessionController = SessionController(profileRepository)

        prootRunner = ProotRunner(dirs)
        processManager = ProotProcessManager()
        provisioner = ArchProvisioner(dirs, rootfsManager, prootRunner, processManager)
        sessionLauncher = ProotSessionLauncher(prootRunner, processManager)
        orchestrator = CompositorOrchestrator(sessionController, sessionLauncher)

        audioBridgeManager = AudioBridgeManager()
        serverManager = WaylandServerManager()
        inputDispatcher = InputDispatcher(serverManager)

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
            audioBridgeManager = audioBridgeManager
        )

        setContent {
            WorkstationTheme {
                WorkstationAppRoot(
                    wizardViewModel = wizardViewModel,
                    dashboardViewModel = dashboardViewModel,
                    serverManager = serverManager,
                    inputDispatcher = inputDispatcher,
                    rootfsManager = rootfsManager,
                    onStartService = { startWorkstationService() },
                    onStopService = { stopWorkstationService() }
                )
            }
        }
    }

    private fun startWorkstationService() {
        val intent = Intent(this, WorkstationService::class.java)
        startForegroundService(intent)
    }

    private fun stopWorkstationService() {
        val intent = Intent(this, WorkstationService::class.java)
        stopService(intent)
    }
}

@Composable
fun WorkstationAppRoot(
    wizardViewModel: WizardViewModel,
    dashboardViewModel: DashboardViewModel,
    serverManager: WaylandServerManager,
    inputDispatcher: InputDispatcher,
    rootfsManager: RootfsManager,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val dashboardState by dashboardViewModel.uiState.collectAsState()

    var currentScreen by remember {
        val initialScreen = if (rootfsManager.listRootfs().isEmpty()) {
            AppScreen.WIZARD
        } else {
            AppScreen.DASHBOARD
        }
        mutableStateOf(initialScreen)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
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
        }
    }
}
