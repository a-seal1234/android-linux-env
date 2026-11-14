package com.alw.workstation.core.proot.provisioning

import android.util.Log
import com.alw.workstation.core.proot.ProotProcessManager
import com.alw.workstation.core.proot.ProotRunner
import com.alw.workstation.core.proot.RootfsManager
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.session.model.StackProfile
import java.io.File
import java.util.regex.Pattern

/**
 * Executes the automated Arch Linux ARM provisioning sequence within the PRoot container,
 * parsing real-time step milestones and tracking setup progress to completion.
 */
class ArchProvisioner(
    private val dirs: RuntimeDirectories,
    private val rootfsManager: RootfsManager,
    private val prootRunner: ProotRunner,
    private val processManager: ProotProcessManager = ProotProcessManager()
) {
    companion object {
        private const val TAG = "ALW::ArchProvisioner"
        private val STEP_PATTERN = Pattern.compile("==> Step (\\d+)/(\\d+): (.*)")
    }

    data class ProvisioningProgress(
        val step: Int,
        val totalSteps: Int,
        val stepDescription: String,
        val lastLogLine: String,
        val isComplete: Boolean = false,
        val isFailed: Boolean = false,
        val errorMessage: String? = null
    )

    /**
     * Executes the complete provisioning sequence for a given rootfs environment.
     */
    suspend fun provision(
        profile: StackProfile,
        config: ProvisioningConfig = ProvisioningConfig(
            defaultUser = profile.distro.defaultUser,
            shell = profile.distro.shell
        ),
        timeoutMs: Long = 600_000L, // 10 minutes timeout for package downloads/compilations
        onProgress: ((ProvisioningProgress) -> Unit)? = null
    ): Boolean {
        dirs.ensureDirectoriesExist()
        val rootfsDir = dirs.getRootfsDir(profile.distro.rootfsId)

        if (!rootfsDir.exists() || !rootfsDir.isDirectory) {
            val errorMsg = "Target rootfs does not exist: ${rootfsDir.absolutePath}"
            Log.e(TAG, errorMsg)
            onProgress?.invoke(
                ProvisioningProgress(
                    step = 0,
                    totalSteps = 6,
                    stepDescription = "Initialization",
                    lastLogLine = errorMsg,
                    isFailed = true,
                    errorMessage = errorMsg
                )
            )
            return false
        }

        // 1. Generate and write the provisioning script inside guest /tmp/
        val guestTmpDir = File(rootfsDir, "tmp")
        if (!guestTmpDir.exists()) {
            guestTmpDir.mkdirs()
        }

        val scriptFile = File(guestTmpDir, "alw-provision.sh")
        val scriptContent = ArchProvisioningScriptBuilder.buildScript(config)
        scriptFile.writeText(scriptContent)
        scriptFile.setExecutable(true, false)
        scriptFile.setReadable(true, false)

        val sessionRuntimeDir = dirs.getRuntimeSocketDir("provisioning-${profile.distro.rootfsId}")
        if (!sessionRuntimeDir.exists()) {
            sessionRuntimeDir.mkdirs()
        }

        // 2. Assemble launch command
        val launchCommand = prootRunner.buildLaunchCommand(
            profile = profile,
            sessionRuntimeDir = sessionRuntimeDir,
            customExecCommand = "/bin/bash",
            customArgs = listOf("/tmp/alw-provision.sh")
        )

        var currentStep = 0
        var totalSteps = 6
        var currentDescription = "Starting provisioning"

        onProgress?.invoke(
            ProvisioningProgress(
                step = currentStep,
                totalSteps = totalSteps,
                stepDescription = currentDescription,
                lastLogLine = "Provisioning script generated"
            )
        )

        // 3. Execute the provisioning command and stream line-by-line output
        val result = processManager.executeCommand(
            command = launchCommand,
            timeoutMs = timeoutMs,
            onStdoutLine = { line ->
                val matcher = STEP_PATTERN.matcher(line)
                if (matcher.find()) {
                    currentStep = matcher.group(1)?.toIntOrNull() ?: currentStep
                    totalSteps = matcher.group(2)?.toIntOrNull() ?: totalSteps
                    currentDescription = matcher.group(3)?.trim() ?: currentDescription
                }
                onProgress?.invoke(
                    ProvisioningProgress(
                        step = currentStep,
                        totalSteps = totalSteps,
                        stepDescription = currentDescription,
                        lastLogLine = line
                    )
                )
            },
            onStderrLine = { line ->
                Log.w(TAG, "[PRoot Provision Stderr] $line")
                onProgress?.invoke(
                    ProvisioningProgress(
                        step = currentStep,
                        totalSteps = totalSteps,
                        stepDescription = currentDescription,
                        lastLogLine = line
                    )
                )
            }
        )

        // 4. Cleanup script
        try {
            if (scriptFile.exists()) {
                scriptFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed cleaning up provisioning script: ${e.message}")
        }

        // 5. Evaluate result
        if (result.exitCode == 0) {
            rootfsManager.markProvisioned(profile.distro.rootfsId, true)
            onProgress?.invoke(
                ProvisioningProgress(
                    step = totalSteps,
                    totalSteps = totalSteps,
                    stepDescription = "Complete",
                    lastLogLine = "Arch Linux ARM provisioning completed successfully",
                    isComplete = true
                )
            )
            return true
        } else {
            val errorMsg = "Provisioning failed with exit code ${result.exitCode}"
            Log.e(TAG, errorMsg)
            onProgress?.invoke(
                ProvisioningProgress(
                    step = currentStep,
                    totalSteps = totalSteps,
                    stepDescription = currentDescription,
                    lastLogLine = errorMsg,
                    isFailed = true,
                    errorMessage = errorMsg
                )
            )
            return false
        }
    }
}
