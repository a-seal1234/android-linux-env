package com.alw.workstation.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.alw.workstation.core.proot.shizuku.DefaultShizukuShellRunner
import com.alw.workstation.core.proot.shizuku.ShizukuCompanionManager
import com.alw.workstation.core.proot.shizuku.ShizukuShellRunner
import com.alw.workstation.core.proot.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps the TASK-027 key-intercept AccessibilityService enabled (TASK-027 §2.2).
 *
 * HyperOS may drop the service from `enabled_accessibility_services` on reboot or
 * app update. [heal] runs on every `MainActivity.onResume`:
 * 1. service already enabled → nothing to do
 * 2. Shizuku companion READY → write the setting through the UID 2000 shell
 * 3. app holds `WRITE_SECURE_SETTINGS` (one-time ADB grant) → write it directly
 * 4. otherwise surface a copyable ADB command block
 */
class AccessibilityHealer(
    private val context: Context,
    private val shizukuManager: ShizukuCompanionManager? = null,
    private val shellRunner: ShizukuShellRunner = DefaultShizukuShellRunner(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    sealed interface HealResult {
        object AlreadyEnabled : HealResult
        object Healed : HealResult
        data class NeedsAdb(val grantCommand: String, val manualCommands: List<String>) : HealResult
        data class Failed(val reason: String) : HealResult
    }

    suspend fun heal(): HealResult = withContext(ioDispatcher) {
        val current = readEnabledServices()
        if (isEnabledStored(current)) return@withContext HealResult.AlreadyEnabled

        if (tryHealViaShizuku(current)) {
            return@withContext HealResult.Healed
        }
        if (tryHealViaWriteSecureSettings(current)) {
            return@withContext HealResult.Healed
        }
        HealResult.NeedsAdb(adbGrantCommand(), adbManualCommands())
    }

    private suspend fun tryHealViaShizuku(current: String?): Boolean {
        val manager = shizukuManager ?: return false
        if (manager.companionState.value.state != ShizukuState.READY) return false

        return try {
            val services = shellRunner.execute(
                listOf(
                    "/system/bin/settings", "put", "secure",
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    mergeServiceEntry(current)
                )
            )
            val enabled = shellRunner.execute(
                listOf(
                    "/system/bin/settings", "put", "secure",
                    Settings.Secure.ACCESSIBILITY_ENABLED, "1"
                )
            )
            val healed = services.exitCode == 0 && enabled.exitCode == 0 &&
                isEnabledStored(readEnabledServicesViaShizuku())
            if (!healed) {
                Log.w(TAG, "Shizuku heal failed (services=${services.exitCode}, " +
                    "flag=${enabled.exitCode}, stderr=${services.stderr})")
            }
            healed
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku heal error: ${e.message}")
            false
        }
    }

    private fun tryHealViaWriteSecureSettings(current: String?): Boolean {
        val granted = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false

        return try {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                mergeServiceEntry(current)
            )
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            val healed = isEnabledStored(readEnabledServices())
            if (!healed) Log.w(TAG, "WRITE_SECURE_SETTINGS heal did not stick")
            healed
        } catch (e: Throwable) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS heal error: ${e.message}")
            false
        }
    }

    private fun readEnabledServices(): String? =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

    private fun readEnabledServicesViaShizuku(): String? = try {
        shellRunner.execute(
            listOf("/system/bin/settings", "get", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ).stdout.ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    private fun isEnabledStored(raw: String?): Boolean {
        val serviceListed = parseEnabledServices(raw).any { isOurServiceEntry(it) }
        if (!serviceListed) return false
        val masterSwitch = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        return masterSwitch == 1
    }

    companion object {
        private const val TAG = "ALW::A11yHealer"

        const val SERVICE_PACKAGE = "com.alw.workstation"
        const val SERVICE_CLASS = "com.alw.workstation.service.AlwKeyInterceptService"

        /** Canonical stored entry (`pkg/pkg.Cls`). */
        const val FLAT_SERVICE_ENTRY = "$SERVICE_PACKAGE/$SERVICE_CLASS"

        /** Alternative stored spelling Android accepts (`pkg/.Cls`). */
        const val SHORT_SERVICE_ENTRY = "$SERVICE_PACKAGE/.service.AlwKeyInterceptService"

        /**
         * Splits a `settings get secure enabled_accessibility_services` value into
         * its stored entries, tolerating null/blank/"null" values. Kept string-based
         * so the merge logic stays unit-testable on the host JVM.
         */
        fun parseEnabledServices(raw: String?): List<String> =
            raw?.split(':')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                ?: emptyList()

        /** True when the entry refers to our key-intercept service (either stored spelling). */
        fun isOurServiceEntry(entry: String): Boolean =
            entry == FLAT_SERVICE_ENTRY || entry == SHORT_SERVICE_ENTRY

        /**
         * Appends our service to the stored entry list. Pre-existing entries are
         * preserved byte-for-byte; when our entry is already present (either spelling)
         * the value is returned unchanged so other services are never rewritten.
         */
        fun mergeServiceEntry(raw: String?): String {
            val entries = parseEnabledServices(raw)
            if (entries.any { isOurServiceEntry(it) }) {
                return raw!!.trim()
            }
            return (entries + FLAT_SERVICE_ENTRY).joinToString(":")
        }

        fun adbGrantCommand(): String =
            "adb shell pm grant $SERVICE_PACKAGE android.permission.WRITE_SECURE_SETTINGS"

        fun adbManualCommands(): List<String> = listOf(
            "adb shell settings get secure enabled_accessibility_services",
            "adb shell 'settings put secure enabled_accessibility_services " +
                "\$(settings get secure enabled_accessibility_services):$FLAT_SERVICE_ENTRY'",
            "adb shell settings put secure accessibility_enabled 1"
        )
    }
}
