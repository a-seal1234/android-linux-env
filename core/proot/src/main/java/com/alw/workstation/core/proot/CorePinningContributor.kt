package com.alw.workstation.core.proot

import com.alw.workstation.core.proot.model.BindMount
import com.alw.workstation.core.session.model.StackProfile
import java.io.File

/**
 * Launch contributor for runtime performance tuning and CPU core pinning (TASK-036).
 *
 * Restricts Malloc arenas to 2 to minimize memory fragmentation under PRoot
 * and pins PRoot guest process trees to performance cores (CPUs 3-7, mask 0xf8).
 * Note: PROOT_NO_SECCOMP=1 must remain set on Android 15+ because Zygote's
 * app seccomp filter (SECCOMP_RET_TRAP) takes precedence over PRoot's
 * SECCOMP_RET_TRACE and kills guest processes with SIGSYS (signal 31).
 */
class CorePinningContributor(
    private val dirs: RuntimeDirectories,
    val coreMask: String = "0xf8"
) : ProotLaunchContributor {

    override val priority: Int = 10

    override fun extraEnvVars(
        profile: StackProfile,
        dirs: RuntimeDirectories
    ): Map<String, String> {
        return mapOf("MALLOC_ARENA_MAX" to "2")
    }

    override fun onProcessSpawned(pid: Long, profile: StackProfile) {
        if (pid <= 0) return
        pinPid(pid)
    }

    fun pinPid(pid: Long) {
        try {
            val process = ProcessBuilder("taskset", "-p", coreMask, pid.toString()).start()
            process.waitFor()
        } catch (_: Throwable) {
            // taskset may not be present on host or permission may be restricted
        }
    }
}
