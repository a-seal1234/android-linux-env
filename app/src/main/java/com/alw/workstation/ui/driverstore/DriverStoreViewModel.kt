package com.alw.workstation.ui.driverstore

import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.driver.DriverRelease
import com.alw.workstation.core.proot.driver.GitHubReleaseFetcher
import com.alw.workstation.core.proot.driver.TurnipDriverManifest
import com.alw.workstation.core.proot.driver.TurnipDriverManager
import com.alw.workstation.core.proot.emulator.EmulatorManager
import com.alw.workstation.core.proot.emulator.EmulatorManifest
import com.alw.workstation.core.proot.emulator.EmulatorRelease
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

enum class StoreTab {
    GPU_DRIVERS,
    EMULATOR_OVERLAYS
}

data class DriverStoreUiState(
    val installedDrivers: List<TurnipDriverManifest> = emptyList(),
    val onlineReleases: List<DriverRelease> = emptyList(),
    val activeDriverVersion: String? = null,
    val installedOverlays: List<EmulatorManifest> = emptyList(),
    val activeFexOverlayVersion: String? = null,
    val activeBox64OverlayVersion: String? = null,
    val onlineEmulatorReleases: List<EmulatorRelease> = emptyList(),
    val selectedTab: StoreTab = StoreTab.GPU_DRIVERS,
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val selectedManifest: TurnipDriverManifest? = null
)

class DriverStoreViewModel(
    private val dirs: RuntimeDirectories,
    private val driverManager: TurnipDriverManager = TurnipDriverManager(dirs),
    private val emulatorManager: EmulatorManager = EmulatorManager(dirs),
    private val releaseFetcher: GitHubReleaseFetcher = GitHubReleaseFetcher(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {

    private val _uiState = MutableStateFlow(DriverStoreUiState())
    val uiState: StateFlow<DriverStoreUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val installed = driverManager.listInstalledDrivers()
        val active = driverManager.getActiveDriver()?.version
        val curatedReleases = releaseFetcher.listAvailableReleases()
        val overlays = emulatorManager.listInstalledOverlays()
        val activeFex = emulatorManager.getActiveOverlay("fex")?.version
        val activeBox64 = emulatorManager.getActiveOverlay("box64")?.version
        val curatedEmulators = EmulatorRelease.curatedReleases()

        _uiState.update { current ->
            current.copy(
                installedDrivers = installed,
                onlineReleases = curatedReleases,
                activeDriverVersion = active,
                installedOverlays = overlays,
                activeFexOverlayVersion = activeFex,
                activeBox64OverlayVersion = activeBox64,
                onlineEmulatorReleases = curatedEmulators,
                isLoading = false,
                statusMessage = null
            )
        }
    }

    fun selectTab(tab: StoreTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun activateOverlay(engine: String, version: String) {
        try {
            emulatorManager.activateOverlay(engine, version)
            _uiState.update {
                if (engine.lowercase() == "fex") {
                    it.copy(activeFexOverlayVersion = version, statusMessage = "Activated FEX overlay: $version")
                } else {
                    it.copy(activeBox64OverlayVersion = version, statusMessage = "Activated box64 overlay: $version")
                }
            }
            refresh()
        } catch (e: Exception) {
            _uiState.update { it.copy(statusMessage = "Failed activating overlay: ${e.message}") }
        }
    }

    fun deleteOverlay(engine: String, version: String) {
        try {
            val deleted = emulatorManager.deleteOverlay(engine, version)
            if (deleted) {
                _uiState.update { it.copy(statusMessage = "Deleted overlay: $version") }
                refresh()
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(statusMessage = "Failed deleting overlay: ${e.message}") }
        }
    }

    fun fetchOnlineReleases(scope: CoroutineScope) {
        _uiState.update { it.copy(isLoading = true, statusMessage = "Fetching Mesa 25.0+ Turnip releases...") }
        scope.launch(ioDispatcher) {
            val releases = releaseFetcher.listAvailableReleases()
            withContext(mainDispatcher) {
                _uiState.update {
                    it.copy(
                        onlineReleases = releases,
                        isLoading = false,
                        statusMessage = "Found ${releases.size} available releases"
                    )
                }
            }
        }
    }

    fun activateDriver(version: String) {
        try {
            driverManager.activateDriver(version)
            _uiState.update {
                it.copy(
                    activeDriverVersion = version,
                    statusMessage = "Active driver set to: $version"
                )
            }
            refresh()
        } catch (e: Exception) {
            _uiState.update { it.copy(statusMessage = "Failed activating driver: ${e.message}") }
        }
    }

    fun installOnlineRelease(release: DriverRelease, scope: CoroutineScope) {
        _uiState.update {
            it.copy(
                isLoading = true,
                statusMessage = "Downloading & verifying ${release.version} (SHA256)..."
            )
        }

        scope.launch(ioDispatcher) {
            try {
                driverManager.installDriverRelease(release)
                withContext(mainDispatcher) {
                    refresh()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Successfully installed Turnip driver ${release.version}"
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(mainDispatcher) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Failed installing release: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    fun importLocalDriver(
        archiveFile: File,
        version: String,
        expectedSha256: String,
        isProprietary: Boolean,
        scope: CoroutineScope
    ) {
        if (!archiveFile.exists()) {
            _uiState.update { it.copy(statusMessage = "Local file not found: ${archiveFile.absolutePath}") }
            return
        }

        _uiState.update {
            it.copy(isLoading = true, statusMessage = "Validating and importing local driver archive...")
        }

        scope.launch(ioDispatcher) {
            try {
                driverManager.installDriverArchive(
                    version = version,
                    archiveFile = archiveFile,
                    expectedSha256 = expectedSha256,
                    isProprietary = isProprietary,
                    releaseTag = "local-import"
                )
                withContext(mainDispatcher) {
                    refresh()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Successfully imported $version into shared store"
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(mainDispatcher) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Import failed: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    fun deleteDriver(version: String) {
        try {
            val deleted = driverManager.deleteDriver(version)
            if (deleted) {
                _uiState.update { it.copy(statusMessage = "Deleted Turnip driver $version") }
                refresh()
            } else {
                _uiState.update { it.copy(statusMessage = "Failed deleting driver $version") }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(statusMessage = "Error deleting driver: ${e.message}") }
        }
    }
}
