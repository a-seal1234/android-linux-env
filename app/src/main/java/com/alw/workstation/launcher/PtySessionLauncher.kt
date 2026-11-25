package com.alw.workstation.launcher

import android.os.ParcelFileDescriptor
import android.util.Log
import com.alw.workstation.bridge.wayland.PtyBridge
import com.alw.workstation.core.proot.ProotRunner
import com.alw.workstation.core.session.SessionProcessLauncher
import com.alw.workstation.core.session.model.StackProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Executes a workstation session inside the PRoot runtime environment attached
 * directly to a native PTY master/slave pair (via PtyBridge).
 *
 * This completely isolates the spawned PRoot process from Android Zygote / ART VM
 * signal handlers and LD_PRELOAD namespaces, preventing premature exit code 255 crashes.
 */
class PtySessionLauncher(
    private val runner: ProotRunner
) : SessionProcessLauncher {

    companion object {
        private const val TAG = "ALW::PtySessionLauncher"
    }

    override suspend fun launchProcess(
        profile: StackProfile,
        sessionRuntimeDir: File,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val cmd = runner.buildLaunchCommand(profile, sessionRuntimeDir)
        Log.i(TAG, "Launching session compositor via PtyBridge: ${cmd.binary} (args: ${cmd.arguments.size})")

        val result = PtyBridge.forkSubprocess(
            command = cmd.binary,
            args = cmd.arguments,
            env = cmd.environment,
            cwd = cmd.workingDir.absolutePath,
            cols = 80,
            rows = 24
        )
        if (result == null) {
            Log.e(TAG, "Failed to fork subprocess via PtyBridge")
            return@withContext -1
        }

        Log.i(TAG, "Spawned session compositor pid=${result.pid} masterFd=${result.masterFd}")

        val pfd = try {
            if (result.masterFd >= 0) ParcelFileDescriptor.adoptFd(result.masterFd) else null
        } catch (e: Throwable) {
            Log.w(TAG, "Failed adopting masterFd: ${e.message}")
            null
        }

        val readerThread = Thread({
            try {
                pfd?.let { p ->
                    val stream = FileInputStream(p.fileDescriptor)
                    val buffer = ByteArray(4096)
                    val lineBuilder = StringBuilder()
                    while (true) {
                        val count = try {
                            stream.read(buffer)
                        } catch (e: Exception) {
                            Log.d(TAG, "PTY stream.read terminated: ${e.message}")
                            -1
                        }
                        if (count <= 0) break
                        val chunk = String(buffer, 0, count)
                        lineBuilder.append(chunk)
                        while (true) {
                            val newlineIdx = lineBuilder.indexOf('\n')
                            if (newlineIdx < 0) break
                            val line = lineBuilder.substring(0, newlineIdx).trimEnd('\r')
                            lineBuilder.delete(0, newlineIdx + 1)
                            Log.i("ALW_Compositor", line)
                            onStdoutLine(line)
                        }
                    }
                    if (lineBuilder.isNotEmpty()) {
                        val remaining = lineBuilder.toString().trimEnd('\r')
                        Log.i("ALW_Compositor", remaining)
                        onStdoutLine(remaining)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "PTY reader finished: ${e.message}")
            }
        }, "PtyCompositorReader-${result.pid}")
        readerThread.start()

        var exitCode = -3
        try {
            while (isActive && exitCode == -3) {
                exitCode = PtyBridge.waitPid(result.pid, noHang = true)
                if (exitCode == -3) {
                    delay(150)
                }
            }
        } finally {
            if (exitCode == -3) {
                Log.i(TAG, "Session coroutine cancelled; terminating pid ${result.pid}")
                try {
                    android.os.Process.sendSignal(result.pid, 15) // SIGTERM
                } catch (_: Throwable) {}
                delay(300)
                exitCode = PtyBridge.waitPid(result.pid, noHang = true)
                if (exitCode == -3) {
                    try {
                        android.os.Process.sendSignal(result.pid, 9) // SIGKILL
                    } catch (_: Throwable) {}
                    PtyBridge.waitPid(result.pid, noHang = true)
                }
            }
            try {
                readerThread.join(1500)
            } catch (_: Throwable) {}
            if (pfd != null) {
                try {
                    pfd.close()
                } catch (_: Throwable) {}
            } else {
                PtyBridge.closeFd(result.masterFd)
            }
        }

        Log.i(TAG, "Session compositor pid=${result.pid} completed with exitCode=$exitCode")
        if (exitCode < 0) 0 else exitCode
    }
}
