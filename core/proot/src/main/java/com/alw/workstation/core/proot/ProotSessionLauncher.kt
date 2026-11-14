package com.alw.workstation.core.proot

import com.alw.workstation.core.session.SessionProcessLauncher
import com.alw.workstation.core.session.model.StackProfile
import java.io.File

/**
 * Executes a workstation session inside the PRoot runtime environment.
 */
class ProotSessionLauncher(
    private val runner: ProotRunner,
    private val processManager: ProotProcessManager = ProotProcessManager(),
    private val timeoutMs: Long = 86_400_000L // 24-hour session timeout
) : SessionProcessLauncher {

    override suspend fun launchProcess(
        profile: StackProfile,
        sessionRuntimeDir: File,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        val cmd = runner.buildLaunchCommand(profile, sessionRuntimeDir)
        val result = processManager.executeCommand(
            command = cmd,
            timeoutMs = timeoutMs,
            onStdoutLine = onStdoutLine,
            onStderrLine = onStderrLine
        )
        return result.exitCode
    }
}
