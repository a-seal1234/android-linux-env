package com.alw.workstation.core.proot

import com.alw.workstation.core.proot.driver.AngleBundleManager
import com.alw.workstation.core.proot.model.BindMount
import com.alw.workstation.core.session.model.StackProfile
import java.io.File

/**
 * Launch contributor for Google ANGLE GLES guest backend (TASK-038).
 *
 * When an authenticated ANGLE bundle is installed, bind-mounts /opt/angle/lib
 * into the guest rootfs and exports ALW_GL_BACKEND=angle (or user override).
 */
class AngleBundleContributor(
    private val dirs: RuntimeDirectories,
    private val angleBundleManager: AngleBundleManager = AngleBundleManager(dirs)
) : ProotLaunchContributor {

    override val priority: Int = 100

    override fun extraBindMounts(
        profile: StackProfile,
        rootfsDir: File,
        dirs: RuntimeDirectories
    ): List<BindMount> {
        if (!angleBundleManager.isBundleInstalled()) {
            return emptyList()
        }

        val angleLibDir = angleBundleManager.activeLibraryDir()
        val guestAngleDir = File(rootfsDir, "opt/angle/lib")
        if (!guestAngleDir.exists()) {
            guestAngleDir.mkdirs()
        }

        return listOf(BindMount(hostPath = angleLibDir.absolutePath, guestPath = "/opt/angle/lib"))
    }

    override fun extraEnvVars(
        profile: StackProfile,
        dirs: RuntimeDirectories
    ): Map<String, String> {
        val angleInstalled = angleBundleManager.isBundleInstalled()
        val backend = profile.gpu.environmentOverrides["ALW_GL_BACKEND"]
            ?: when {
                profile.gpu.driverMode == StackProfile.DriverMode.FREEDRENO_KGSL -> "freedreno"
                angleInstalled -> "angle"
                else -> "zink"
            }
        return mapOf("ALW_GL_BACKEND" to backend)
    }

    override fun onProcessSpawned(pid: Long, profile: StackProfile) {
        // No-op for ANGLE bundle
    }
}
