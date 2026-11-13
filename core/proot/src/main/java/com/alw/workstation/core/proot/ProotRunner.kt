package com.alw.workstation.core.proot

import android.util.Log
import com.alw.workstation.core.proot.model.BindMount
import com.alw.workstation.core.session.model.StackProfile
import java.io.File

/**
 * Execution harness for PRoot v5.4.0+ in unrooted Android user-space.
 * Incorporates selective storage bridge, cache redirection, and hardware node bindings.
 */
class ProotRunner(
    private val dirs: RuntimeDirectories,
    private val prootBinaryPath: String
) {
    companion object {
        private const val TAG = "ALW::ProotRunner"

        val systemPseudoMounts = listOf(
            BindMount("/proc"),
            BindMount("/dev"),
            BindMount("/sys")
        )
    }

    data class LaunchCommand(
        val binary: String,
        val arguments: List<String>,
        val environment: Map<String, String>,
        val workingDir: File
    )

    fun buildLaunchCommand(profile: StackProfile, sessionRuntimeDir: File): LaunchCommand {
        val rootfsDir = dirs.getRootfsDir(profile.distro.rootfsId)
        val args = mutableListOf<String>()

        // 1. Fake root
        args.add("-0")

        // 2. Enable soft symlink emulation on non-POSIX storage if enabled
        if (profile.storage.enableSymlink2File) {
            args.add("--link2symlink")
        }

        // 3. Set rootfs root directory
        args.add("-r")
        args.add(rootfsDir.absolutePath)

        // 4. Base system pseudo mounts
        for (mount in systemPseudoMounts) {
            args.add("-b")
            args.add(mount.toCliArg())
        }

        // 5. Direct Adreno GPU KGSL device node
        val kgslNode = File(profile.gpu.kgslNode)
        if (kgslNode.exists()) {
            args.add("-b")
            args.add(profile.gpu.kgslNode)
        } else {
            Log.w(TAG, "KGSL device node does not exist on host: ${profile.gpu.kgslNode}")
        }

        // 6. XDG runtime socket directory (wayland-0, audio socket)
        args.add("-b")
        args.add("${sessionRuntimeDir.absolutePath}:/tmp/runtime-alw")

        // 7. External Mesa Turnip / Adreno driver package mount
        if (profile.gpu.externalDriverId.isNotBlank() && profile.gpu.externalDriverId != "system-fallback") {
            val driverDir = dirs.getDriverDir(profile.gpu.externalDriverId)
            if (driverDir.exists()) {
                args.add("-b")
                args.add("${driverDir.absolutePath}:/opt/turnip:ro")
            }
        }

        // 8. Selective SDCard mount (excluding Android/data and Android/obb)
        if (profile.storage.bindSdcard) {
            val sdcard = File("/storage/emulated/0")
            if (sdcard.exists()) {
                args.add("-b")
                args.add("${sdcard.absolutePath}:${profile.storage.sdcardMountPoint}")
            }
        }

        // 9. Optional x86_64 emulation hook
        if (profile.emulation.enabled) {
            val fexPath = File("/opt/fex/bin/FEXInterpreter")
            args.add("-q")
            args.add(fexPath.absolutePath)
        }

        // 10. Working directory
        val userHome = "/home/${profile.distro.defaultUser}"
        args.add("-w")
        args.add(userHome)

        // 11. Environment variables
        val env = mutableMapOf<String, String>()
        env["TERM"] = "xterm-256color"
        env["HOME"] = userHome
        env["USER"] = profile.distro.defaultUser
        env["PATH"] = "/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin"
        env["XDG_RUNTIME_DIR"] = "/tmp/runtime-alw"
        env["WAYLAND_DISPLAY"] = "wayland-0"

        // Build cache redirection to fast internal UFS storage
        if (profile.storage.cacheRedirection.enabled) {
            val baseCache = profile.storage.cacheRedirection.internalBaseDir
            env["CARGO_TARGET_DIR"] = "$baseCache/cargo-target"
            env["UV_PROJECT_ENVIRONMENT"] = "$baseCache/uv-env"
            env["PNPM_STORE_DIR"] = "$baseCache/pnpm-store"
            env["GOCACHE"] = "$baseCache/go-build"
        }

        // Driver-specific environment exports
        when (profile.gpu.driverMode) {
            StackProfile.DriverMode.TURNIP_KGSL -> {
                env["VK_ICD_FILENAMES"] = "/opt/turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json"
                env["LIBGL_DRIVERS_PATH"] = "/opt/turnip/lib/dri"
                env["LD_LIBRARY_PATH"] = "/opt/turnip/lib"
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "kgsl"
            }
            StackProfile.DriverMode.ADRENO_PROPRIETARY_ZINK -> {
                env["VK_ICD_FILENAMES"] = "/opt/turnip/share/vulkan/icd.d/adreno_icd.aarch64.json"
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
            }
            StackProfile.DriverMode.LLVMPIPE -> {
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "llvmpipe"
                env["LIBGL_ALWAYS_SOFTWARE"] = "1"
            }
            else -> {}
        }

        env.putAll(profile.gpu.environmentOverrides)

        // Command to execute
        args.add("/usr/bin/env")
        args.add("-i")
        for ((k, v) in env) {
            args.add("$k=$v")
        }
        args.add(profile.compositor.execCommand)
        args.addAll(profile.compositor.extraArgs)

        return LaunchCommand(
            binary = prootBinaryPath,
            arguments = args,
            environment = mapOf("PROOT_TMP_DIR" to dirs.prootTmpDir.absolutePath),
            workingDir = rootfsDir
        )
    }
}
