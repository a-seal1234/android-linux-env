package com.alw.workstation.ui.wizard

import com.alw.workstation.core.proot.RootfsManager
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.driver.GitHubReleaseFetcher
import com.alw.workstation.core.proot.driver.TurnipDriverManager
import com.alw.workstation.core.session.ProfileRepository
import com.alw.workstation.core.session.model.StackProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class WizardViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var dirs: RuntimeDirectories
    private lateinit var rootfsManager: RootfsManager
    private lateinit var driverManager: TurnipDriverManager
    private lateinit var releaseFetcher: GitHubReleaseFetcher
    private lateinit var profileRepository: ProfileRepository
    private lateinit var viewModel: WizardViewModel

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("alw-wizard-test")
        dirs = RuntimeDirectories(
            filesDir = filesDir,
            nativeLibDir = File(tempFolder.root, "nativeLib").apply { mkdirs() }.absolutePath
        )
        dirs.ensureDirectoriesExist()

        releaseFetcher = GitHubReleaseFetcher()
        rootfsManager = RootfsManager(dirs)
        driverManager = TurnipDriverManager(dirs, releaseFetcher)
        profileRepository = ProfileRepository(filesDir)

        viewModel = WizardViewModel(
            dirs = dirs,
            rootfsManager = rootfsManager,
            driverManager = driverManager,
            releaseFetcher = releaseFetcher,
            profileRepository = profileRepository
        )
    }

    @Test
    fun testInitialStateAndPresets() {
        val state = viewModel.uiState.value
        assertEquals(WizardStep.DISTRO_PROFILE, state.step)
        assertEquals(WizardPreset.CREATIVE_WORKSTATION, state.selectedPreset)
        assertEquals("alw", state.username)
        assertEquals(1.75f, state.scaleFactor, 0.001f)
        assertFalse(state.availableReleases.isEmpty())

        // Test Preset switching
        viewModel.selectPreset(WizardPreset.MINIMAL_DEV)
        assertEquals(WizardPreset.MINIMAL_DEV, viewModel.uiState.value.selectedPreset)

        viewModel.selectPreset(WizardPreset.SAFE_MODE)
        assertEquals(WizardPreset.SAFE_MODE, viewModel.uiState.value.selectedPreset)

        // Test Username sanitization
        viewModel.setUsername("dev-user_99!")
        assertEquals("dev-user_99", viewModel.uiState.value.username)

        viewModel.setUsername("   ")
        assertEquals("alw", viewModel.uiState.value.username)

        // Test Scale clamping
        viewModel.setScaleFactor(3.0f)
        assertEquals(2.5f, viewModel.uiState.value.scaleFactor, 0.001f)

        viewModel.setScaleFactor(0.5f)
        assertEquals(1.0f, viewModel.uiState.value.scaleFactor, 0.001f)
    }

    @Test
    fun testStepNavigation() {
        assertEquals(WizardStep.DISTRO_PROFILE, viewModel.uiState.value.step)

        viewModel.nextStep()
        assertEquals(WizardStep.DRIVER_STORE, viewModel.uiState.value.step)

        viewModel.nextStep()
        assertEquals(WizardStep.PROVISIONING, viewModel.uiState.value.step)

        viewModel.previousStep()
        assertEquals(WizardStep.DRIVER_STORE, viewModel.uiState.value.step)

        viewModel.previousStep()
        assertEquals(WizardStep.DISTRO_PROFILE, viewModel.uiState.value.step)
    }

    @Test
    fun testDriverSelectionAndValidation() {
        val releases = viewModel.uiState.value.availableReleases
        assertTrue(releases.isNotEmpty())

        val targetRelease = releases.first()
        viewModel.selectRelease(targetRelease.releaseTag)

        val updatedState = viewModel.uiState.value
        assertEquals(targetRelease.releaseTag, updatedState.selectedReleaseTag)
        assertEquals(targetRelease.sha256Checksum, updatedState.driverSha256)
        assertEquals(DriverVerificationState.VERIFIED, updatedState.driverVerificationState)

        // Software fallback
        viewModel.setDriverOption(DriverStoreOption.SOFTWARE_FALLBACK)
        assertEquals(DriverStoreOption.SOFTWARE_FALLBACK, viewModel.uiState.value.driverOption)
        assertEquals(DriverVerificationState.VERIFIED, viewModel.uiState.value.driverVerificationState)

        // Missing file error
        val missingFile = File(tempFolder.root, "nonexistent.so")
        viewModel.selectLocalDriver(missingFile)
        assertEquals(DriverVerificationState.FAILED, viewModel.uiState.value.driverVerificationState)
        assertNotNull(viewModel.uiState.value.driverErrorMessage)
    }

    @Test
    fun testBuildTargetProfile() {
        viewModel.selectPreset(WizardPreset.CREATIVE_WORKSTATION)
        viewModel.setUsername("artist")
        viewModel.setScaleFactor(2.0f)
        viewModel.setDriverOption(DriverStoreOption.ONLINE_RELEASE)

        val profile = viewModel.buildTargetProfile()
        assertEquals("archlinux-default", profile.distro.rootfsId)
        assertEquals("artist", profile.distro.defaultUser)
        assertEquals(2.0f, profile.display.scaleFactor, 0.001f)
        assertEquals(StackProfile.DriverMode.TURNIP_KGSL, profile.gpu.driverMode)
        assertEquals(StackProfile.CompositorType.HYPRLAND, profile.compositor.type)

        // Safe mode preset profile
        viewModel.selectPreset(WizardPreset.SAFE_MODE)
        viewModel.setDriverOption(DriverStoreOption.SOFTWARE_FALLBACK)
        val safeProfile = viewModel.buildTargetProfile()
        assertEquals(StackProfile.DriverMode.LLVMPIPE, safeProfile.gpu.driverMode)
        assertEquals(StackProfile.CompositorType.LABWC, safeProfile.compositor.type)
    }

    @Test
    fun testProvisioningStateProgression() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        // Seed valid shell in rootfs so network download is skipped
        val rootfsDir = dirs.getRootfsDir("archlinux-default")
        val binDir = File(rootfsDir, "bin").apply { mkdirs() }
        File(binDir, "sh").writeText("#!/bin/sh\nexit 0\n")

        val testViewModel = WizardViewModel(
            dirs = dirs,
            rootfsManager = rootfsManager,
            driverManager = driverManager,
            releaseFetcher = releaseFetcher,
            profileRepository = profileRepository,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        testViewModel.setDriverOption(DriverStoreOption.SOFTWARE_FALLBACK)
        testViewModel.startProvisioning(testScope)
        assertTrue(testViewModel.uiState.value.isProvisioning)

        testScope.advanceUntilIdle()

        val finalState = testViewModel.uiState.value
        assertFalse(finalState.isProvisioning)
        assertTrue(finalState.isComplete)
        assertEquals(WizardStep.COMPLETED, finalState.step)
        assertEquals(7, finalState.provisioningStep)
        assertNull(finalState.provisioningError)
        assertTrue(finalState.provisioningLogs.isNotEmpty())
    }

    @Test
    fun testOciSelectionFlow() {
        viewModel.selectRootfsSource(RootfsSourceType.OCI_REGISTRY)
        assertEquals(RootfsSourceType.OCI_REGISTRY, viewModel.uiState.value.rootfsSource)

        viewModel.selectOciDistro("ubuntu")
        assertEquals("ubuntu", viewModel.uiState.value.selectedOciDistroId)
        assertEquals("noble", viewModel.uiState.value.selectedOciTag)
        assertEquals("ubuntu-default", viewModel.uiState.value.rootfsId)

        viewModel.selectOciTag("jammy")
        assertEquals("jammy", viewModel.uiState.value.selectedOciTag)

        viewModel.setCustomOciImage("kalilinux/kali-rolling:latest")
        assertEquals("kalilinux/kali-rolling:latest", viewModel.uiState.value.customOciImage)
    }
}
