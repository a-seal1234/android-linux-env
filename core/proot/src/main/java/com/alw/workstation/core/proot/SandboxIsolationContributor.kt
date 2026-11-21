package com.alw.workstation.core.proot

import com.alw.workstation.core.proot.model.BindMount
import com.alw.workstation.core.session.model.StackProfile
import java.io.File

/**
 * Launch contributor providing no-sandbox isolation and runtime tuning for guest
 * browsers and Electron applications (TASK-036).
 *
 * Disables content sandboxes for Firefox and QtWebEngine, forces Wayland Ozone
 * platform for Electron, ensures a writable /dev/shm tmpfs mount, and injects
 * libnosandbox.so to intercept prctl(PR_SET_SECCOMP) and namespace creation in unshare().
 */
class SandboxIsolationContributor(
    private val dirs: RuntimeDirectories
) : ProotLaunchContributor {

    override val priority: Int = 20

    override fun extraBindMounts(
        profile: StackProfile,
        rootfsDir: File,
        dirs: RuntimeDirectories
    ): List<BindMount> {
        val shmDir = File(dirs.fakefsBaseDir, "dev/shm")
        if (!shmDir.exists()) {
            shmDir.mkdirs()
        }
        try {
            shmDir.setReadable(true, false)
            shmDir.setWritable(true, false)
            shmDir.setExecutable(true, false)
        } catch (_: Throwable) {}

        val guestShmDir = File(rootfsDir, "dev/shm")
        if (!guestShmDir.exists()) {
            guestShmDir.mkdirs()
        }

        return listOf(BindMount(hostPath = shmDir.absolutePath, guestPath = "/dev/shm"))
    }

    override fun extraEnvVars(
        profile: StackProfile,
        dirs: RuntimeDirectories
    ): Map<String, String> {
        return mapOf(
            "MOZ_DISABLE_CONTENT_SANDBOX" to "1",
            "MOZ_DISABLE_RDD_SANDBOX" to "1",
            "MOZ_DISABLE_GMP_SANDBOX" to "1",
            "MOZ_DISABLE_SOCKET_PROCESS_SANDBOX" to "1",
            "QTWEBENGINE_DISABLE_SANDBOX" to "1",
            "ELECTRON_OZONE_PLATFORM_HINT" to "wayland"
        )
    }

    override fun onProcessSpawned(pid: Long, profile: StackProfile) {
        // No-op
    }
}
