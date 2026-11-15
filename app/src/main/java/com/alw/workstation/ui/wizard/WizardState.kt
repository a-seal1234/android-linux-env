package com.alw.workstation.ui.wizard

import com.alw.workstation.core.proot.driver.DriverRelease

enum class WizardStep {
    DISTRO_PROFILE,
    DRIVER_STORE,
    PROVISIONING,
    COMPLETED
}

enum class WizardPreset {
    CREATIVE_WORKSTATION,
    MINIMAL_DEV,
    SAFE_MODE
}

enum class RootfsSourceType {
    DEFAULT_ARCH_ARM,
    OCI_REGISTRY,
    LOCAL_ARCHIVE
}

enum class DriverStoreOption {
    ONLINE_RELEASE,
    LOCAL_ARCHIVE,
    SOFTWARE_FALLBACK
}

enum class DriverVerificationState {
    UNCHECKED,
    VERIFYING,
    VERIFIED,
    FAILED
}

data class WizardUiState(
    val step: WizardStep = WizardStep.DISTRO_PROFILE,
    val rootfsId: String = "archlinux-default",
    val rootfsSource: RootfsSourceType = RootfsSourceType.DEFAULT_ARCH_ARM,
    val localRootfsPath: String? = null,
    val selectedOciDistroId: String = "archlinux",
    val selectedOciTag: String = "base",
    val customOciImage: String = "",
    val availableOciTags: List<String> = listOf("base", "latest"),
    val isFetchingOciTags: Boolean = false,
    val selectedPreset: WizardPreset = WizardPreset.CREATIVE_WORKSTATION,
    val username: String = "alw",
    val scaleFactor: Float = 1.75f,
    val driverOption: DriverStoreOption = DriverStoreOption.ONLINE_RELEASE,
    val availableReleases: List<DriverRelease> = emptyList(),
    val isFetchingReleases: Boolean = false,
    val selectedReleaseTag: String? = null,
    val localDriverPath: String? = null,
    val driverVerificationState: DriverVerificationState = DriverVerificationState.UNCHECKED,
    val driverSha256: String? = null,
    val driverIsGlibc: Boolean? = null,
    val driverErrorMessage: String? = null,
    val isDownloadingRootfs: Boolean = false,
    val downloadProgressFraction: Float = 0f,
    val downloadSpeedBytesPerSec: Long = 0L,
    val isExtractingRootfs: Boolean = false,
    val extractionProgressFraction: Float = 0f,
    val isProvisioning: Boolean = false,
    val provisioningStep: Int = 0,
    val provisioningTotalSteps: Int = 7,
    val provisioningStepDescription: String = "Initializing",
    val provisioningLogs: List<String> = emptyList(),
    val provisioningError: String? = null,
    val isComplete: Boolean = false
)
