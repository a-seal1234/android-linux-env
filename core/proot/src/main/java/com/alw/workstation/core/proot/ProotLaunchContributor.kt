package com.alw.workstation.core.proot

import com.alw.workstation.core.proot.model.BindMount
import com.alw.workstation.core.session.model.StackProfile
import java.io.File

/**
 * Modular extension point for PRoot launch execution pipeline.
 *
 * Contributors can register additional bind mounts, inject guest environment
 * variables, and handle post-spawn process hooks (e.g., core pinning or telemetry).
 */
interface ProotLaunchContributor {

    /**
     * Priority determines execution order (lower value executes earlier).
     */
    val priority: Int get() = 100

    /**
     * Returns additional bind mounts to attach to the PRoot invocation.
     */
    fun extraBindMounts(
        profile: StackProfile,
        rootfsDir: File,
        dirs: RuntimeDirectories
    ): List<BindMount> = emptyList()

    /**
     * Returns additional environment variables to inject into the guest execution environment.
     */
    fun extraEnvVars(
        profile: StackProfile,
        dirs: RuntimeDirectories
    ): Map<String, String> = emptyMap()

    /**
     * Returns overrides for host runner environment variables.
     * A null value indicates the variable should be unset.
     */
    fun runnerEnvOverrides(): Map<String, String?> = emptyMap()

    /**
     * Hook invoked after the PRoot process has been spawned.
     */
    fun onProcessSpawned(
        pid: Long,
        profile: StackProfile
    ) {}
}
