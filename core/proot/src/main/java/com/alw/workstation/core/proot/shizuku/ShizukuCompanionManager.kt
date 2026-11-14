package com.alw.workstation.core.proot.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Manages the lifecycle, permissions, and privileged command execution for the
 * optional Shizuku companion (UID 2000).
 *
 * Adheres strictly to the Non-Root & Optional Companion Invariant:
 * The workstation operates with 100% functionality without Shizuku. When Shizuku
 * is active, it allows lifting Android 12+ Phantom Process Killer restrictions
 * (max_phantom_processes 2147483647) and whitelisting Xiaomi HyperOS PowerKeeper.
 */
class ShizukuCompanionManager(
    private val context: Context? = null,
    private val shellRunner: ShizukuShellRunner = DefaultShizukuShellRunner(),
    private val isInstalledProvider: () -> Boolean = {
        context?.packageManager?.let { isPackageInstalled(it, SHIZUKU_PACKAGE_NAME) } ?: false
    },
    private val isRunningProvider: () -> Boolean = {
        try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    },
    private val hasPermissionProvider: () -> Boolean = {
        try {
            Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    },
    private val requestPermissionAction: (Int) -> Unit = { code ->
        try {
            Shizuku.requestPermission(code)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed requesting Shizuku permission: ${e.message}")
        }
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val TAG = "ALW::ShizukuCompanion"
        const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 4421
        const val UNLIMITED_PHANTOM_PROCESS_LIMIT = "2147483647"

        fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                }
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private val _companionState = MutableStateFlow(ShizukuCompanionState())
    val companionState: StateFlow<ShizukuCompanionState> = _companionState.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        refreshState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.i(TAG, "Shizuku binder died")
        refreshState()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            Log.i(TAG, "Shizuku permission result: $grantResult")
            onPermissionResult(requestCode, grantResult)
        }
    }

    init {
        refreshState()
    }

    /**
     * Registers Shizuku IPC binder and permission result listeners.
     */
    fun registerListeners() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed registering Shizuku listeners: ${e.message}")
        }
    }

    /**
     * Unregisters Shizuku IPC listeners.
     */
    fun unregisterListeners() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed unregistering Shizuku listeners: ${e.message}")
        }
    }

    /**
     * Refreshes the companion state by querying package installation,
     * binder availability, and permission status.
     */
    fun refreshState() {
        val installed = isInstalledProvider()
        val running = isRunningProvider()
        val hasPermission = hasPermissionProvider()

        val state = when {
            !installed -> ShizukuState.NOT_INSTALLED
            !running -> ShizukuState.STOPPED
            !hasPermission -> ShizukuState.PERMISSION_REQUIRED
            else -> ShizukuState.READY
        }

        val version = if (running) {
            try { Shizuku.getVersion() } catch (e: Throwable) { 0 }
        } else 0

        val uid = if (running && hasPermission) {
            try { Shizuku.getUid() } catch (e: Throwable) { 0 }
        } else 0

        _companionState.update { current ->
            current.copy(
                state = state,
                shizukuVersion = version,
                shizukuUid = uid,
                lastError = null
            )
        }

        if (state == ShizukuState.READY) {
            CoroutineScope(ioDispatcher).launch {
                readPhantomProcessLimit()
            }
        }
    }

    /**
     * Requests Shizuku ADB authorization prompt.
     */
    fun requestPermission(requestCode: Int = SHIZUKU_PERMISSION_REQUEST_CODE) {
        val current = _companionState.value.state
        if (current == ShizukuState.NOT_INSTALLED) {
            _companionState.update { it.copy(lastError = "Shizuku is not installed on this device") }
            return
        }
        if (current == ShizukuState.STOPPED) {
            _companionState.update { it.copy(lastError = "Shizuku service is not running. Please start Shizuku.") }
            return
        }
        requestPermissionAction(requestCode)
    }

    /**
     * Handles permission request results received from Shizuku.
     */
    fun onPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                _companionState.update {
                    it.copy(
                        state = ShizukuState.READY,
                        lastError = null,
                        message = "Shizuku authorization granted!"
                    )
                }
                CoroutineScope(ioDispatcher).launch {
                    readPhantomProcessLimit()
                }
            } else {
                _companionState.update {
                    it.copy(
                        state = ShizukuState.PERMISSION_REQUIRED,
                        lastError = "Shizuku permission was denied"
                    )
                }
            }
        }
    }

    /**
     * Reads back the current active `max_phantom_processes` device configuration.
     */
    suspend fun readPhantomProcessLimit(): String = withContext(ioDispatcher) {
        if (_companionState.value.state != ShizukuState.READY) {
            return@withContext "32 (Default - Shizuku Inactive)"
        }

        val result = shellRunner.execute(
            listOf("/system/bin/device_config", "get", "activity_manager", "max_phantom_processes")
        )
        val rawLimit = result.stdout.trim()
        val isBypassed = rawLimit == UNLIMITED_PHANTOM_PROCESS_LIMIT
        val displayLimit = when {
            isBypassed -> "Unlimited ($UNLIMITED_PHANTOM_PROCESS_LIMIT)"
            rawLimit.isBlank() || rawLimit == "null" -> "32 (Default - 32 Process Cap)"
            else -> "$rawLimit max procs"
        }

        _companionState.update { current ->
            current.copy(
                phantomProcessStatus = current.phantomProcessStatus.copy(
                    currentLimit = displayLimit,
                    isBypassed = isBypassed
                )
            )
        }
        displayLimit
    }

    /**
     * Executes the one-tap Phantom Process Killer bypass and HyperOS PowerKeeper whitelisting.
     *
     * 1. /system/bin/device_config set_sync_disabled_for_tests persistent
     * 2. /system/bin/device_config put activity_manager max_phantom_processes 2147483647
     * 3. dumpsys deviceidle whitelist +<package>
     * 4. cmd appops set <package> RUN_IN_BACKGROUND allow
     * 5. cmd appops set <package> RUN_ANY_IN_BACKGROUND allow
     */
    suspend fun applyPhantomProcessBypass(
        packageName: String = context?.packageName ?: "com.alw.workstation"
    ): Boolean = withContext(ioDispatcher) {
        if (_companionState.value.state != ShizukuState.READY) {
            _companionState.update {
                it.copy(lastError = "Cannot apply bypass: Shizuku companion is not READY")
            }
            return@withContext false
        }

        _companionState.update { it.copy(isApplyingBypass = true, lastError = null) }

        try {
            // Step 1: Disable sync for tests
            val syncResult = shellRunner.execute(
                listOf("/system/bin/device_config", "set_sync_disabled_for_tests", "persistent")
            )
            Log.d(TAG, "device_config set_sync_disabled_for_tests: exit=${syncResult.exitCode}")

            // Step 2: Set phantom limit to 2.14B
            val limitResult = shellRunner.execute(
                listOf(
                    "/system/bin/device_config",
                    "put",
                    "activity_manager",
                    "max_phantom_processes",
                    UNLIMITED_PHANTOM_PROCESS_LIMIT
                )
            )
            Log.d(TAG, "device_config put max_phantom_processes: exit=${limitResult.exitCode}")

            // Step 3: Xiaomi HyperOS PowerKeeper Whitelisting
            val whitelistResult = shellRunner.execute(
                listOf("/system/bin/dumpsys", "deviceidle", "whitelist", "+$packageName")
            )
            val appOpsResult1 = shellRunner.execute(
                listOf("/system/bin/cmd", "appops", "set", packageName, "RUN_IN_BACKGROUND", "allow")
            )
            val appOpsResult2 = shellRunner.execute(
                listOf("/system/bin/cmd", "appops", "set", packageName, "RUN_ANY_IN_BACKGROUND", "allow")
            )
            val powerWhitelisted = whitelistResult.isSuccess || appOpsResult1.isSuccess

            // Step 4: Verify limit
            val verifyResult = shellRunner.execute(
                listOf("/system/bin/device_config", "get", "activity_manager", "max_phantom_processes")
            )
            val newLimit = verifyResult.stdout.trim()
            val isBypassed = newLimit == UNLIMITED_PHANTOM_PROCESS_LIMIT

            _companionState.update { current ->
                current.copy(
                    isApplyingBypass = false,
                    phantomProcessStatus = PhantomProcessStatus(
                        currentLimit = if (isBypassed) "Unlimited ($UNLIMITED_PHANTOM_PROCESS_LIMIT)" else newLimit.ifBlank { "32 (Default)" },
                        isBypassed = isBypassed,
                        powerKeeperWhitelisted = powerWhitelisted
                    ),
                    message = if (isBypassed) {
                        "Phantom Process limit lifted to Unlimited (2.14B)! HyperOS PowerKeeper whitelisted."
                    } else {
                        "Warning: Active limit reported as '$newLimit'"
                    }
                )
            }
            isBypassed
        } catch (e: Exception) {
            Log.e(TAG, "Failed applying phantom process bypass", e)
            _companionState.update {
                it.copy(
                    isApplyingBypass = false,
                    lastError = "Failed applying bypass: ${e.message}"
                )
            }
            false
        }
    }
}
