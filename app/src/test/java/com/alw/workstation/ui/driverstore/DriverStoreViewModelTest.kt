package com.alw.workstation.ui.driverstore

import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.driver.GitHubReleaseFetcher
import com.alw.workstation.core.proot.driver.TurnipDriverManifest
import com.alw.workstation.core.proot.driver.TurnipDriverManager
import com.alw.workstation.core.proot.emulator.EmulatorManifest
import com.alw.workstation.core.proot.emulator.EmulatorManager
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DriverStoreViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dirs: RuntimeDirectories
    private lateinit var driverManager: TurnipDriverManager
    private lateinit var releaseFetcher: GitHubReleaseFetcher
    private lateinit var viewModel: DriverStoreViewModel

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("files")
        val nativeLibDir = tempFolder.newFolder("lib").absolutePath
        dirs = RuntimeDirectories(filesDir, nativeLibDir)
        dirs.ensureDirectoriesExist()

        releaseFetcher = GitHubReleaseFetcher()
        driverManager = TurnipDriverManager(dirs, releaseFetcher)

        viewModel = DriverStoreViewModel(
            dirs = dirs,
            driverManager = driverManager,
            releaseFetcher = releaseFetcher,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testInitialStateAndOnlineReleases() {
        val state = viewModel.uiState.value
        assertTrue(state.onlineReleases.isNotEmpty())
        assertTrue(state.installedDrivers.isEmpty())
        assertNull(state.activeDriverVersion)

        val mesa25 = state.onlineReleases.find { it.version.contains("25.0.0") }
        assertNotNull(mesa25)
        assertTrue(mesa25!!.supportedGpus.contains("Adreno 825 (A8xx)"))
    }

    @Test
    fun testActivateDriverUpdatesState() {
        // Setup a simulated driver directory with manifest
        val version = "turnip-25.0.0-test"
        val driverDir = dirs.getTurnipDriverDir(version)
        driverDir.mkdirs()

        val manifest = TurnipDriverManifest(
            version = version,
            releaseTag = "v25.0.0",
            driverType = TurnipDriverManifest.DriverType.TURNIP_KGSL,
            sha256Checksum = "dummy-sha",
            apiVersion = "1.4.0"
        )
        File(driverDir, "manifest.json").writeText(manifest.toJson())

        viewModel.refresh()
        assertEquals(1, viewModel.uiState.value.installedDrivers.size)

        viewModel.activateDriver(version)
        assertEquals(version, viewModel.uiState.value.activeDriverVersion)
        assertEquals(version, driverManager.getActiveDriver()?.version)
    }

    @Test
    fun testDeleteDriver() {
        val version = "turnip-delete-test"
        val driverDir = dirs.getTurnipDriverDir(version)
        driverDir.mkdirs()

        val manifest = TurnipDriverManifest(
            version = version,
            releaseTag = "v25.0.0",
            driverType = TurnipDriverManifest.DriverType.TURNIP_KGSL,
            sha256Checksum = "dummy-sha",
            apiVersion = "1.4.0"
        )
        File(driverDir, "manifest.json").writeText(manifest.toJson())

        viewModel.refresh()
        assertEquals(1, viewModel.uiState.value.installedDrivers.size)

        viewModel.deleteDriver(version)
        assertTrue(viewModel.uiState.value.installedDrivers.isEmpty())
        assertEquals(null, driverManager.getInstalledDriver(version))
    }

    @Test
    fun testEmulatorOverlaysTabAndActivation() {
        val emuManager = EmulatorManager(dirs)

        // 1. Initial curated releases
        val state = viewModel.uiState.value
        assertTrue(state.onlineEmulatorReleases.isNotEmpty())
        val fexRelease = state.onlineEmulatorReleases.find { it.engine == "fex" }
        assertNotNull(fexRelease)
        assertEquals("fex-2412", fexRelease?.version)

        // 2. Tab switching
        assertEquals(StoreTab.GPU_DRIVERS, viewModel.uiState.value.selectedTab)
        viewModel.selectTab(StoreTab.EMULATOR_OVERLAYS)
        assertEquals(StoreTab.EMULATOR_OVERLAYS, viewModel.uiState.value.selectedTab)

        // 3. Setup mock installed overlay
        val fexDir = dirs.getOverlayDir("fex", "fex-2412")
        fexDir.mkdirs()
        File(fexDir, "bin").mkdirs()
        val manifest = EmulatorManifest(
            engine = "fex",
            version = "fex-2412",
            displayName = "FEX v2412 Test",
            interpreterRelativePath = "bin/FEXInterpreter",
            sha256Checksum = "mock-sha",
            hasThunkLibs = true
        )
        File(fexDir, "manifest.json").writeText(manifest.toJson())

        viewModel.refresh()
        assertEquals(1, viewModel.uiState.value.installedOverlays.size)

        // 4. Activate overlay
        viewModel.activateOverlay("fex", "fex-2412")
        assertEquals("fex-2412", viewModel.uiState.value.activeFexOverlayVersion)
        assertEquals("fex-2412", emuManager.getActiveOverlay("fex")?.version)

        // 5. Delete overlay
        viewModel.deleteOverlay("fex", "fex-2412")
        assertTrue(viewModel.uiState.value.installedOverlays.isEmpty())
        assertNull(emuManager.getActiveOverlay("fex"))
    }
}
