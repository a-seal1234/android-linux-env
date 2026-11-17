package com.alw.workstation.ui.configurator

import com.alw.workstation.core.proot.RootfsManager
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.driver.TurnipDriverManager
import com.alw.workstation.core.proot.emulator.EmulatorManager
import com.alw.workstation.core.proot.emulator.EmulatorManifest
import com.alw.workstation.core.session.ProfileRepository
import com.alw.workstation.core.session.ProfileValidator
import com.alw.workstation.core.session.model.StackProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StackConfiguratorUiState(
    val profile: StackProfile = StackProfile.defaultArchXiaomiPad8(),
    val isNewProfile: Boolean = false,
    val availableRootfs: List<RootfsManager.RootfsInfo> = emptyList(),
    val availableDrivers: List<String> = emptyList(),
    val availableOverlays: List<EmulatorManifest> = emptyList(),
    val validationErrors: List<String> = emptyList(),
    val isSaved: Boolean = false,
    val message: String? = null
)

class StackConfiguratorViewModel(
    private val dirs: RuntimeDirectories,
    private val profileRepository: ProfileRepository = ProfileRepository(dirs.filesDir),
    private val rootfsManager: RootfsManager = RootfsManager(dirs),
    private val driverManager: TurnipDriverManager = TurnipDriverManager(dirs),
    private val emulatorManager: EmulatorManager = EmulatorManager(dirs)
) {

    private val _uiState = MutableStateFlow(StackConfiguratorUiState())
    val uiState: StateFlow<StackConfiguratorUiState> = _uiState.asStateFlow()

    init {
        loadEnvironmentData()
    }

    private fun loadEnvironmentData() {
        val rootfsList = rootfsManager.listRootfs()
        val installedDrivers = driverManager.listInstalledDrivers()
        val driverNames = installedDrivers.map { it.version } + listOf("active", "system-fallback")
        val installedOverlays = emulatorManager.listInstalledOverlays()

        _uiState.update { current ->
            current.copy(
                availableRootfs = rootfsList,
                availableDrivers = driverNames.distinct(),
                availableOverlays = installedOverlays
            )
        }
    }

    fun loadProfile(profileId: String?) {
        loadEnvironmentData()
        if (profileId == null) {
            // New profile based on Xiaomi Pad 8 default
            val newId = "profile-${System.currentTimeMillis() % 10000}"
            val newProfile = StackProfile.defaultArchXiaomiPad8().copy(
                id = newId,
                name = "Custom Workstation ($newId)"
            )
            _uiState.update {
                it.copy(
                    profile = newProfile,
                    isNewProfile = true,
                    validationErrors = emptyList(),
                    isSaved = false,
                    message = null
                )
            }
        } else {
            val existing = profileRepository.getProfile(profileId) ?: StackProfile.defaultArchXiaomiPad8()
            _uiState.update {
                it.copy(
                    profile = existing,
                    isNewProfile = false,
                    validationErrors = emptyList(),
                    isSaved = false,
                    message = null
                )
            }
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(profile = it.profile.copy(name = name)) }
    }

    fun updateRootfsId(rootfsId: String) {
        _uiState.update {
            it.copy(profile = it.profile.copy(distro = it.profile.distro.copy(rootfsId = rootfsId)))
        }
    }

    fun updateCompositor(type: StackProfile.CompositorType) {
        _uiState.update { current ->
            val execCmd = when (type) {
                StackProfile.CompositorType.HYPRLAND -> "hyprland"
                StackProfile.CompositorType.LABWC -> "labwc"
                StackProfile.CompositorType.PLASMA -> "startplasma-wayland"
                StackProfile.CompositorType.CUSTOM -> current.profile.compositor.execCommand
            }
            val updatedCompositor = current.profile.compositor.copy(
                type = type,
                execCommand = execCmd
            )
            current.copy(profile = current.profile.copy(compositor = updatedCompositor))
        }
    }

    fun updateDriverMode(mode: StackProfile.DriverMode) {
        _uiState.update { current ->
            val updatedGpu = current.profile.gpu.copy(driverMode = mode)
            current.copy(profile = current.profile.copy(gpu = updatedGpu))
        }
    }

    fun updateExternalDriverId(driverId: String) {
        _uiState.update { current ->
            val updatedGpu = current.profile.gpu.copy(externalDriverId = driverId)
            current.copy(profile = current.profile.copy(gpu = updatedGpu))
        }
    }

    fun updateTuDebugNoconform(enabled: Boolean) {
        _uiState.update { current ->
            val updatedGpu = current.profile.gpu.copy(tuDebugNoConform = enabled)
            current.copy(profile = current.profile.copy(gpu = updatedGpu))
        }
    }

    fun updateScaleFactor(scale: Float) {
        val clamped = scale.coerceIn(1.0f, 2.5f)
        _uiState.update { current ->
            val updatedDisplay = current.profile.display.copy(scaleFactor = clamped)
            current.copy(profile = current.profile.copy(display = updatedDisplay))
        }
    }

    fun updateRefreshRate(rateHz: Int) {
        _uiState.update { current ->
            val updatedDisplay = current.profile.display.copy(refreshRateHz = rateHz)
            current.copy(profile = current.profile.copy(display = updatedDisplay))
        }
    }

    fun updateInputRebind(rebindCapsToSuper: Boolean) {
        _uiState.update { current ->
            val updatedInput = current.profile.input.copy(
                rebindCapsToSuper = rebindCapsToSuper,
                xkbOptions = if (rebindCapsToSuper) "caps:super" else ""
            )
            current.copy(profile = current.profile.copy(input = updatedInput))
        }
    }

    fun updateNaturalScroll(naturalScroll: Boolean) {
        _uiState.update { current ->
            val updatedInput = current.profile.input.copy(touchpadNaturalScroll = naturalScroll)
            current.copy(profile = current.profile.copy(input = updatedInput))
        }
    }

    fun updateBuildCacheRedirection(enabled: Boolean) {
        _uiState.update { current ->
            val updatedStorage = current.profile.storage.copy(
                cacheRedirection = current.profile.storage.cacheRedirection.copy(enabled = enabled)
            )
            current.copy(profile = current.profile.copy(storage = updatedStorage))
        }
    }

    fun updateMountSdcard(enabled: Boolean) {
        _uiState.update { current ->
            val updatedStorage = current.profile.storage.copy(bindSdcard = enabled)
            current.copy(profile = current.profile.copy(storage = updatedStorage))
        }
    }

    fun setEnvVar(key: String, value: String) {
        if (key.isBlank()) return
        _uiState.update { current ->
            val env = current.profile.gpu.environmentOverrides.toMutableMap()
            env[key.trim()] = value.trim()
            val updatedGpu = current.profile.gpu.copy(environmentOverrides = env)
            current.copy(profile = current.profile.copy(gpu = updatedGpu))
        }
    }

    fun removeEnvVar(key: String) {
        _uiState.update { current ->
            val env = current.profile.gpu.environmentOverrides.toMutableMap()
            env.remove(key)
            val updatedGpu = current.profile.gpu.copy(environmentOverrides = env)
            current.copy(profile = current.profile.copy(gpu = updatedGpu))
        }
    }

    fun updateEmulationEnabled(enabled: Boolean) {
        _uiState.update { current ->
            val updated = current.profile.emulation.copy(enabled = enabled)
            current.copy(profile = current.profile.copy(emulation = updated))
        }
    }

    fun updateEmulationEngine(engine: StackProfile.EmulationEngine) {
        _uiState.update { current ->
            val defaultOverlay = if (engine == StackProfile.EmulationEngine.FEX) "fex-2412" else "box64-v0.3.2"
            val updated = current.profile.emulation.copy(
                engine = engine,
                overlayVersion = defaultOverlay
            )
            current.copy(profile = current.profile.copy(emulation = updated))
        }
    }

    fun updateEmulationThunkLibs(enabled: Boolean) {
        _uiState.update { current ->
            val updated = current.profile.emulation.copy(thunkLibsEnabled = enabled)
            current.copy(profile = current.profile.copy(emulation = updated))
        }
    }

    fun updateEmulationOverlayVersion(version: String) {
        _uiState.update { current ->
            val updated = current.profile.emulation.copy(overlayVersion = version)
            current.copy(profile = current.profile.copy(emulation = updated))
        }
    }

    fun updateCustomInterpreterPath(path: String?) {
        _uiState.update { current ->
            val updated = current.profile.emulation.copy(
                customInterpreterPath = path?.takeIf { it.isNotBlank() }
            )
            current.copy(profile = current.profile.copy(emulation = updated))
        }
    }

    fun saveProfile(): Boolean {
        val currentProfile = _uiState.value.profile
        when (val validation = ProfileValidator.validate(currentProfile)) {
            is ProfileValidator.ValidationResult.Invalid -> {
                _uiState.update {
                    it.copy(
                        validationErrors = validation.errors,
                        message = "Validation failed: ${validation.errors.firstOrNull()}"
                    )
                }
                return false
            }
            is ProfileValidator.ValidationResult.Valid -> {
                // Passed validation
            }
        }

        return try {
            profileRepository.saveProfile(currentProfile)
            _uiState.update {
                it.copy(
                    isSaved = true,
                    validationErrors = emptyList(),
                    message = "Profile '${currentProfile.name}' saved successfully!"
                )
            }
            true
        } catch (e: Exception) {
            _uiState.update {
                it.copy(message = "Failed saving profile: ${e.message}")
            }
            false
        }
    }

    fun duplicateProfile(): StackProfile? {
        val current = _uiState.value.profile
        val duplicateId = "${current.id}-copy"
        val duplicate = current.copy(
            id = duplicateId,
            name = "${current.name} (Copy)"
        )
        return try {
            profileRepository.saveProfile(duplicate)
            loadProfile(duplicateId)
            duplicate
        } catch (e: Exception) {
            _uiState.update { it.copy(message = "Failed duplicating profile: ${e.message}") }
            null
        }
    }

    fun deleteProfile(): Boolean {
        val currentId = _uiState.value.profile.id
        val allProfiles = profileRepository.listProfiles()
        if (allProfiles.size <= 1) {
            _uiState.update { it.copy(message = "Cannot delete the last remaining profile") }
            return false
        }

        return try {
            val deleted = profileRepository.deleteProfile(currentId)
            if (deleted) {
                val nextProfile = profileRepository.listProfiles().firstOrNull()
                loadProfile(nextProfile?.id)
            }
            deleted
        } catch (e: Exception) {
            _uiState.update { it.copy(message = "Failed deleting profile: ${e.message}") }
            false
        }
    }
}
