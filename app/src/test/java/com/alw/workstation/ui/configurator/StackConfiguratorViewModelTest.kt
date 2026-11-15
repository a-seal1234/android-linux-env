package com.alw.workstation.ui.configurator

import com.alw.workstation.core.proot.RootfsManager
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.driver.TurnipDriverManager
import com.alw.workstation.core.session.ProfileRepository
import com.alw.workstation.core.session.model.StackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StackConfiguratorViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dirs: RuntimeDirectories
    private lateinit var profileRepository: ProfileRepository
    private lateinit var rootfsManager: RootfsManager
    private lateinit var driverManager: TurnipDriverManager
    private lateinit var viewModel: StackConfiguratorViewModel
    private val defaultProfile = StackProfile.defaultArchXiaomiPad8()

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("files")
        val nativeLibDir = tempFolder.newFolder("lib").absolutePath
        dirs = RuntimeDirectories(filesDir, nativeLibDir)
        dirs.ensureDirectoriesExist()

        profileRepository = ProfileRepository(filesDir)
        profileRepository.saveProfile(defaultProfile)

        rootfsManager = RootfsManager(dirs)
        driverManager = TurnipDriverManager(dirs)

        viewModel = StackConfiguratorViewModel(
            dirs = dirs,
            profileRepository = profileRepository,
            rootfsManager = rootfsManager,
            driverManager = driverManager
        )
    }

    @Test
    fun testInitialStateAndLoadProfile() {
        viewModel.loadProfile(defaultProfile.id)
        val state = viewModel.uiState.value

        assertEquals(defaultProfile.id, state.profile.id)
        assertFalse(state.isNewProfile)
        assertTrue(state.validationErrors.isEmpty())

        // Test creating new profile
        viewModel.loadProfile(null)
        val newState = viewModel.uiState.value
        assertTrue(newState.isNewProfile)
        assertTrue(newState.profile.id.startsWith("profile-"))
    }

    @Test
    fun testUpdateFields() {
        viewModel.loadProfile(defaultProfile.id)

        viewModel.updateName("Creative Hyprland Pad 8")
        assertEquals("Creative Hyprland Pad 8", viewModel.uiState.value.profile.name)

        viewModel.updateRootfsId("arch-custom")
        assertEquals("arch-custom", viewModel.uiState.value.profile.distro.rootfsId)

        viewModel.updateCompositor(StackProfile.CompositorType.LABWC)
        assertEquals(StackProfile.CompositorType.LABWC, viewModel.uiState.value.profile.compositor.type)

        viewModel.updateDriverMode(StackProfile.DriverMode.LLVMPIPE)
        assertEquals(StackProfile.DriverMode.LLVMPIPE, viewModel.uiState.value.profile.gpu.driverMode)

        viewModel.updateExternalDriverId("v25.0.0-turnip")
        assertEquals("v25.0.0-turnip", viewModel.uiState.value.profile.gpu.externalDriverId)

        viewModel.updateTuDebugNoconform(true)
        assertTrue(viewModel.uiState.value.profile.gpu.tuDebugNoConform)

        viewModel.updateScaleFactor(2.0f)
        assertEquals(2.0f, viewModel.uiState.value.profile.display.scaleFactor, 0.001f)

        viewModel.updateRefreshRate(120)
        assertEquals(120, viewModel.uiState.value.profile.display.refreshRateHz)

        viewModel.updateInputRebind(true)
        assertTrue(viewModel.uiState.value.profile.input.rebindCapsToSuper)
        assertEquals("caps:super", viewModel.uiState.value.profile.input.xkbOptions)

        viewModel.updateNaturalScroll(true)
        assertTrue(viewModel.uiState.value.profile.input.touchpadNaturalScroll)

        viewModel.updateBuildCacheRedirection(false)
        assertFalse(viewModel.uiState.value.profile.storage.cacheRedirection.enabled)

        viewModel.updateMountSdcard(false)
        assertFalse(viewModel.uiState.value.profile.storage.bindSdcard)

        viewModel.setEnvVar("MESA_DEBUG", "silent")
        assertEquals("silent", viewModel.uiState.value.profile.gpu.environmentOverrides["MESA_DEBUG"])

        viewModel.removeEnvVar("MESA_DEBUG")
        assertFalse(viewModel.uiState.value.profile.gpu.environmentOverrides.containsKey("MESA_DEBUG"))
    }

    @Test
    fun testSaveProfileValidationFailureAndSuccess() {
        viewModel.loadProfile(defaultProfile.id)

        // Blank name should fail validation
        viewModel.updateName("")
        val failed = viewModel.saveProfile()
        assertFalse(failed)
        assertFalse(viewModel.uiState.value.isSaved)
        assertTrue(viewModel.uiState.value.validationErrors.isNotEmpty())

        // Valid name should succeed
        viewModel.updateName("Valid Workstation")
        val saved = viewModel.saveProfile()
        assertTrue(saved)
        assertTrue(viewModel.uiState.value.isSaved)
        assertTrue(viewModel.uiState.value.validationErrors.isEmpty())

        val reloaded = profileRepository.getProfile(defaultProfile.id)
        assertNotNull(reloaded)
        assertEquals("Valid Workstation", reloaded?.name)
    }

    @Test
    fun testDuplicateProfile() {
        viewModel.loadProfile(defaultProfile.id)
        val duplicated = viewModel.duplicateProfile()

        assertNotNull(duplicated)
        assertEquals("${defaultProfile.id}-copy", duplicated?.id)
        assertEquals("${defaultProfile.name} (Copy)", duplicated?.name)

        val stored = profileRepository.getProfile(duplicated!!.id)
        assertNotNull(stored)
        assertEquals(duplicated.id, viewModel.uiState.value.profile.id)
    }

    @Test
    fun testDeleteProfile() {
        // Create a second profile so deletion is allowed
        val secondProfile = defaultProfile.copy(id = "profile-secondary", name = "Secondary")
        profileRepository.saveProfile(secondProfile)

        viewModel.loadProfile(secondProfile.id)
        assertEquals("profile-secondary", viewModel.uiState.value.profile.id)

        val deleted = viewModel.deleteProfile()
        assertTrue(deleted)
        assertEquals(null, profileRepository.getProfile("profile-secondary"))

        // Try deleting the only remaining profile
        assertEquals(defaultProfile.id, viewModel.uiState.value.profile.id)
        val cannotDeleteLast = viewModel.deleteProfile()
        assertFalse(cannotDeleteLast)
        assertNotNull(profileRepository.getProfile(defaultProfile.id))
    }

    @Test
    fun testUpdateEmulationConfig() {
        viewModel.loadProfile(defaultProfile.id)
        assertFalse(viewModel.uiState.value.profile.emulation.enabled)

        viewModel.updateEmulationEnabled(true)
        assertTrue(viewModel.uiState.value.profile.emulation.enabled)

        viewModel.updateEmulationEngine(StackProfile.EmulationEngine.BOX64)
        assertEquals(StackProfile.EmulationEngine.BOX64, viewModel.uiState.value.profile.emulation.engine)

        viewModel.updateEmulationThunkLibs(false)
        assertFalse(viewModel.uiState.value.profile.emulation.thunkLibsEnabled)

        viewModel.updateEmulationOverlayVersion("box64-custom")
        assertEquals("box64-custom", viewModel.uiState.value.profile.emulation.overlayVersion)

        viewModel.updateCustomInterpreterPath("/usr/bin/custom")
        assertEquals("/usr/bin/custom", viewModel.uiState.value.profile.emulation.customInterpreterPath)

        val saved = viewModel.saveProfile()
        assertTrue(saved)

        val stored = profileRepository.getProfile(defaultProfile.id)
        assertNotNull(stored)
        assertTrue(stored!!.emulation.enabled)
        assertEquals(StackProfile.EmulationEngine.BOX64, stored.emulation.engine)
        assertFalse(stored.emulation.thunkLibsEnabled)
        assertEquals("box64-custom", stored.emulation.overlayVersion)
        assertEquals("/usr/bin/custom", stored.emulation.customInterpreterPath)
    }
}
