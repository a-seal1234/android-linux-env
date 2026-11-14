package com.alw.workstation.core.proot.shizuku

/**
 * High-level connection state of the optional Shizuku companion.
 * Follows the Non-Root & Optional Companion Invariant: the app functions
 * 100% in all states, with Shizuku providing privileged optimizations.
 */
enum class ShizukuState {
    /** Shizuku app is not installed on the Android host device */
    NOT_INSTALLED,
    /** Shizuku app is installed but the Shizuku server service is stopped */
    STOPPED,
    /** Shizuku server is active, but ALW has not been granted ADB permission */
    PERMISSION_REQUIRED,
    /** Shizuku server is running and permission is granted (privileged UID 2000 active) */
    READY
}

/**
 * Active status of the Android 12+ Phantom Process Killer and HyperOS PowerKeeper.
 */
data class PhantomProcessStatus(
    /** Formatted limit description, e.g. "2147483647", "32 (Default)", or "null" */
    val currentLimit: String = "32 (Default)",
    /** Whether the 32-process cap has been lifted (limit set to 2147483647) */
    val isBypassed: Boolean = false,
    /** Whether HyperOS PowerKeeper background execution is whitelisted */
    val powerKeeperWhitelisted: Boolean = false
)

/**
 * Reactive state snapshot emitted by [ShizukuCompanionManager].
 */
data class ShizukuCompanionState(
    val state: ShizukuState = ShizukuState.NOT_INSTALLED,
    val shizukuVersion: Int = 0,
    val shizukuUid: Int = 0,
    val phantomProcessStatus: PhantomProcessStatus = PhantomProcessStatus(),
    val isApplyingBypass: Boolean = false,
    val lastError: String? = null,
    val message: String? = null
) {
    val isReady: Boolean get() = state == ShizukuState.READY
    val isBypassed: Boolean get() = phantomProcessStatus.isBypassed
}

/**
 * Result of a privileged command executed via Shizuku shell.
 */
data class ShizukuCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}
