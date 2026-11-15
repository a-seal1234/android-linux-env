package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile
import java.io.File

/**
 * Functional interface for launching a workstation session process.
 */
fun interface SessionProcessLauncher {
    suspend fun launchProcess(
        profile: StackProfile,
        sessionRuntimeDir: File,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int
}

/**
 * Orchestrates Wayland compositor startup, runtime monitoring, and early-crash
 * automatic fallback to LabWC Safe Mode.
 */
class CompositorOrchestrator(
    private val sessionController: SessionController,
    private val processLauncher: SessionProcessLauncher,
    private val crashWindowMillis: Long = 5000L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    sealed interface LaunchOutcome {
        data class Success(val exitCode: Int, val uptimeMillis: Long) : LaunchOutcome
        data class FallbackTriggered(
            val originalProfile: StackProfile,
            val fallbackProfile: StackProfile,
            val reason: String,
            val fallbackExitCode: Int
        ) : LaunchOutcome
        data class Error(val message: String, val cause: Throwable? = null) : LaunchOutcome
    }

    suspend fun launchSession(
        profile: StackProfile,
        sessionRuntimeDir: File,
        rootfsDir: File? = null,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {}
    ): LaunchOutcome {
        // 1. Ensure default configs exist on first boot if rootfsDir is provided
        if (rootfsDir != null) {
            ConfigSynthesizer.ensureDefaultConfigsExist(rootfsDir, profile.distro.defaultUser, profile)
        }

        // 2. Prepare session config
        val prepResult = sessionController.prepareSession(profile)
        if (prepResult.isFailure) {
            val err = prepResult.exceptionOrNull()?.message ?: "Session preparation failed"
            return LaunchOutcome.Error(err, prepResult.exceptionOrNull())
        }
        val configContent = prepResult.getOrThrow()
        if (rootfsDir != null && profile.compositor.configMode == StackProfile.ConfigMode.WRAP_AND_OVERRIDE) {
            val sessionConfigFile = File(rootfsDir, profile.compositor.sessionConfigPath.removePrefix("/"))
            sessionConfigFile.parentFile?.mkdirs()
            sessionConfigFile.writeText(configContent)
        }

        sessionController.updateState(SessionState.Running(profile, 0L))
        val startTime = timeProvider()

        // 3. Launch the primary compositor
        val exitCode = try {
            processLauncher.launchProcess(profile, sessionRuntimeDir, onStdoutLine, onStderrLine)
        } catch (e: Exception) {
            val err = "Failed launching primary compositor ${profile.compositor.type}: ${e.message}"
            sessionController.updateState(SessionState.Error(err, e))
            return LaunchOutcome.Error(err, e)
        }

        val uptime = timeProvider() - startTime

        // 4. Early-crash / non-zero exit detection for Hyprland
        val isHyprland = profile.compositor.type == StackProfile.CompositorType.HYPRLAND
        val isCrashOrEarlyExit = exitCode != 0 || uptime < crashWindowMillis

        if (isHyprland && isCrashOrEarlyExit) {
            val reason = "Hyprland terminated prematurely (exitCode=$exitCode, uptime=${uptime}ms). Automatically activating LabWC Safe Mode."
            val safeProfile = StackProfile.safeModeProfile()

            sessionController.updateState(
                SessionState.SafeModeFallback(
                    originalProfile = profile,
                    fallbackProfile = safeProfile,
                    reason = reason
                )
            )

            // Prepare Safe Mode session artifacts & configs
            if (rootfsDir != null) {
                ConfigSynthesizer.ensureDefaultConfigsExist(rootfsDir, safeProfile.distro.defaultUser, safeProfile)
            }
            sessionController.prepareSession(safeProfile)
            sessionController.updateState(SessionState.Running(safeProfile, 0L))

            // Launch LabWC Safe Mode
            val fallbackExitCode = try {
                processLauncher.launchProcess(safeProfile, sessionRuntimeDir, onStdoutLine, onStderrLine)
            } catch (e: Exception) {
                val err = "Failed launching Safe Mode LabWC fallback: ${e.message}"
                sessionController.updateState(SessionState.Error(err, e))
                return LaunchOutcome.Error(err, e)
            }

            if (fallbackExitCode == 0) {
                sessionController.updateState(SessionState.Stopped)
            } else {
                sessionController.updateState(SessionState.Error("Safe Mode LabWC exited with code $fallbackExitCode"))
            }

            return LaunchOutcome.FallbackTriggered(
                originalProfile = profile,
                fallbackProfile = safeProfile,
                reason = reason,
                fallbackExitCode = fallbackExitCode
            )
        }

        // Normal exit
        if (exitCode == 0) {
            sessionController.updateState(SessionState.Stopped)
            return LaunchOutcome.Success(exitCode, uptime)
        } else {
            sessionController.updateState(SessionState.Error("Session exited with code $exitCode"))
            return LaunchOutcome.Error("Session exited with code $exitCode")
        }
    }
}
