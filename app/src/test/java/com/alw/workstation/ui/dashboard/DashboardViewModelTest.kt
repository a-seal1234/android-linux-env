package com.alw.workstation.ui.dashboard

import com.alw.workstation.bridge.wayland.input.InputProfile
import com.alw.workstation.core.proot.RootfsManager
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.driver.GitHubReleaseFetcher
import com.alw.workstation.core.proot.driver.TurnipDriverManager
import com.alw.workstation.core.session.ProfileRepository
import com.alw.workstation.core.session.SessionController
import com.alw.workstation.core.session.SessionState
import com.alw.workstation.core.session.model.StackProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var dirs: RuntimeDirectories
    private lateinit var rootfsManager: RootfsManager
    private lateinit var driverManager: TurnipDriverManager
    private lateinit var profileRepository: ProfileRepository
    private lateinit var sessionController: SessionController
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("alw-dashboard-test")
        dirs = RuntimeDirectories(
            filesDir = filesDir,
            nativeLibDir = File(tempFolder.root, "nativeLib").apply { mkdirs() }.absolutePath
        )
        dirs.ensureDirectoriesExist()

        rootfsManager = RootfsManager(dirs)
        driverManager = TurnipDriverManager(dirs, GitHubReleaseFetcher())
        profileRepository = ProfileRepository(filesDir)
        sessionController = SessionController(profileRepository)

        // Seed default profile
        val defaultProfile = StackProfile.defaultArchXiaomiPad8()
        profileRepository.saveProfile(defaultProfile)

        viewModel = DashboardViewModel(
            dirs = dirs,
            rootfsManager = rootfsManager,
            driverManager = driverManager,
            profileRepository = profileRepository,
            sessionController = sessionController
        )
    }

    @Test
    fun testInitialDashboardState() {
        val state = viewModel.uiState.value
        assertNotNull(state.activeProfile)
        assertEquals("arch-xiaomi-pad-8", state.activeProfile?.id)
        assertEquals(InputProfile.CREATIVE_PEN, state.inputProfile)
        assertEquals(SessionState.Idle, state.sessionState)
    }

    @Test
    fun testWorkspaceManagement() {
        // Create workspace
        viewModel.createWorkspace("workspace-alpha")
        val afterCreate = viewModel.uiState.value.workspaces
        assertTrue(afterCreate.any { it.id == "workspace-alpha" })

        // Clone workspace
        viewModel.cloneWorkspace("workspace-alpha", "workspace-alpha-clone")
        val afterClone = viewModel.uiState.value.workspaces
        assertTrue(afterClone.any { it.id == "workspace-alpha-clone" })

        // Delete workspace
        viewModel.deleteWorkspace("workspace-alpha-clone")
        val afterDelete = viewModel.uiState.value.workspaces
        assertFalse(afterDelete.any { it.id == "workspace-alpha-clone" })
        assertTrue(afterDelete.any { it.id == "workspace-alpha" })
    }

    @Test
    fun testProfileAndInputSelection() {
        val safeProfile = StackProfile.safeModeProfile()
        profileRepository.saveProfile(safeProfile)
        viewModel.refresh()

        viewModel.selectProfile(safeProfile)
        assertEquals("safe-mode", viewModel.uiState.value.activeProfile?.id)

        // Test Input profile switching
        viewModel.switchInputProfile(InputProfile.GAMING_RAW)
        assertEquals(InputProfile.GAMING_RAW, viewModel.uiState.value.inputProfile)

        viewModel.switchInputProfile(InputProfile.CREATIVE_PEN)
        assertEquals(InputProfile.CREATIVE_PEN, viewModel.uiState.value.inputProfile)
    }

    @Test
    fun testSessionLaunchAndTeardown() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        viewModel.launchSession(testScope)
        testScope.advanceUntilIdle()

        val runningState = viewModel.uiState.value.sessionState
        assertTrue(runningState is SessionState.Running)

        viewModel.stopSession()
        testScope.advanceUntilIdle()

        val stoppedState = viewModel.uiState.value.sessionState
        assertEquals(SessionState.Stopped, stoppedState)
    }

    @Test
    fun testDialogStateToggles() {
        viewModel.openCreateDialog()
        assertTrue(viewModel.uiState.value.showCreateDialog)
        viewModel.dismissCreateDialog()
        assertFalse(viewModel.uiState.value.showCreateDialog)

        viewModel.createWorkspace("ws-test")
        val ws = viewModel.uiState.value.workspaces.first { it.id == "ws-test" }

        viewModel.openCloneDialog(ws)
        assertTrue(viewModel.uiState.value.showCloneDialog)
        assertEquals("ws-test", viewModel.uiState.value.selectedWorkspace?.id)
        viewModel.dismissCloneDialog()
        assertFalse(viewModel.uiState.value.showCloneDialog)

        viewModel.openDeleteDialog(ws)
        assertTrue(viewModel.uiState.value.showDeleteDialog)
        viewModel.dismissDeleteDialog()
        assertFalse(viewModel.uiState.value.showDeleteDialog)
    }
}
