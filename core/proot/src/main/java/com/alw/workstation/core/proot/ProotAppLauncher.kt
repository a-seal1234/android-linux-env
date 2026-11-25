package com.alw.workstation.core.proot

import android.util.Log
import com.alw.workstation.core.session.model.StackProfile
import com.alw.workstation.core.session.rice.RiceHostBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Launches isolated application processes in dedicated PRoot containers (TASK-040).
 * Each application gets an independent host-side ptrace domain so that any application
 * crash, unhandled signal, or seccomp violation only affects that single app instance
 * and cannot cascade into the desktop compositor session.
 */
class ProotAppLauncher(
    private val runner: ProotRunner,
    private val processManager: ProotProcessManager = ProotProcessManager(),
    private val profileProvider: () -> StackProfile?,
    private val runtimeDirProvider: () -> File?
) : RiceHostBridge.AppSpawnHandler {

    companion object {
        private const val TAG = "ALW::ProotAppLauncher"
    }

    private val activeProcesses = ConcurrentHashMap<Long, ProotProcessManager.ManagedProcess>()

    override fun spawnApp(command: String, args: List<String>): Result<Long> {
        val profile = profileProvider()
            ?: return Result.failure(IllegalStateException("No active profile"))
        val runtimeDir = runtimeDirProvider()
            ?: return Result.failure(IllegalStateException("No active runtime dir"))

        return try {
            val launchCmd = runner.buildLaunchCommand(
                profile = profile,
                sessionRuntimeDir = runtimeDir,
                customExecCommand = command,
                customArgs = args
            )

            // Inject ALW_ISOLATED=1 inside `/usr/bin/env -i` arguments so guest shims do not
            // recursively delegate to alw-app-exec, and target Hyprland's nested Wayland socket
            // (wayland-1) if active so isolated apps render inside the desktop compositor.
            val targetWaylandDisplay = if (File(runtimeDir, "wayland-1").exists()) "wayland-1" else null
            val adjustedArgs = mutableListOf<String>()
            var insertedIsolated = false
            for (arg in launchCmd.arguments) {
                if (targetWaylandDisplay != null && arg.startsWith("WAYLAND_DISPLAY=")) {
                    adjustedArgs.add("WAYLAND_DISPLAY=$targetWaylandDisplay")
                } else {
                    adjustedArgs.add(arg)
                }
                if (!insertedIsolated && arg == "-i") {
                    adjustedArgs.add("ALW_ISOLATED=1")
                    insertedIsolated = true
                }
            }
            val isolatedEnv = launchCmd.environment.toMutableMap().apply {
                put("ALW_ISOLATED", "1")
            }
            val adjustedCmd = launchCmd.copy(
                arguments = adjustedArgs,
                environment = isolatedEnv
            )

            var currentPid = 0L
            val managed = processManager.startProcess(
                command = adjustedCmd,
                listener = object : ProotProcessManager.ProcessListener {
                    override fun onProcessSpawned(pid: Long) {
                        currentPid = pid
                        Log.i(TAG, "Spawned isolated app '$command' (PID: $pid)")
                        runner.onProcessSpawned(pid, profile)
                    }

                    override fun onProcessExited(exitCode: Int) {
                        Log.i(TAG, "Isolated app '$command' exited with code $exitCode")
                        if (currentPid > 0L) {
                            activeProcesses.remove(currentPid)
                        }
                    }
                }
            )

            val pid = managed.pid() ?: currentPid
            if (pid > 0L) {
                activeProcesses[pid] = managed
            }
            Result.success(pid)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed spawning isolated app '$command'", e)
            Result.failure(e)
        }
    }

    fun activeProcessCount(): Int = activeProcesses.count { it.value.isAlive }

    fun getActiveProcesses(): Map<Long, ProotProcessManager.ManagedProcess> = activeProcesses.toMap()

    fun terminateAll(gracePeriodMs: Long = 1000L) {
        for ((_, proc) in activeProcesses) {
            try {
                proc.terminate(gracePeriodMs)
            } catch (_: Throwable) {}
        }
        activeProcesses.clear()
    }
}
