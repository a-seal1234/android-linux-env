package com.alw.workstation.core.proot

import android.util.Log
import com.alw.workstation.core.proot.model.BindMount
import com.alw.workstation.core.session.EnvironmentGenerator
import com.alw.workstation.core.session.model.StackProfile
import java.io.File

/**
 * Execution harness for PRoot v5.4.0+ in unrooted Android user-space.
 * Incorporates rootfs directory isolation, hardware node bindings, selective storage bridge,
 * build cache redirection, shared Turnip driver store binding, and transparent x86_64 translation hooks.
 */
class ProotRunner(
    private val dirs: RuntimeDirectories,
    private val prootBinaryPath: String = dirs.prootLibPath,
    private val storageBridge: com.alw.workstation.core.proot.storage.StorageBridgeManager =
        com.alw.workstation.core.proot.storage.StorageBridgeManager(dirs),
    private val kgslNodeChecker: (String) -> Boolean = { File(it).exists() }
) {
    companion object {
        private const val TAG = "ALW::ProotRunner"

        val defaultPseudoMounts = listOf(
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

    /**
     * Builds a LaunchCommand targeting the profile's desktop compositor or custom session.
     */
    fun buildLaunchCommand(
        profile: StackProfile,
        sessionRuntimeDir: File,
        customExecCommand: String? = null,
        customArgs: List<String>? = null
    ): LaunchCommand {
        dirs.ensureDirectoriesExist()
        val rootfsDir = dirs.getRootfsDir(profile.distro.rootfsId)
        val args = mutableListOf<String>()

        // 1. Fake root user emulation (UID 0)
        args.add("-0")

        // 2. Enable soft symlink emulation on non-POSIX shared storage
        if (profile.storage.enableSymlink2File) {
            args.add("--link2symlink")
        }

        // 3. Set rootfs root directory
        args.add("-r")
        args.add(rootfsDir.absolutePath)

        // 4. Base system pseudo mounts
        for (mount in defaultPseudoMounts) {
            args.add("-b")
            args.add(mount.toCliArg())
        }

        // Additional pseudo filesystems if present on host
        val devPts = File("/dev/pts")
        if (devPts.exists()) {
            args.add("-b")
            args.add("/dev/pts")
        }
        val devShm = File("/dev/shm")
        if (devShm.exists()) {
            args.add("-b")
            args.add("/dev/shm")
        }

        // 5. Direct Adreno KGSL hardware GPU device node & Fallback check
        val isKgslAccessible = kgslNodeChecker(profile.gpu.kgslNode)
        val requiresKgsl = profile.gpu.driverMode != StackProfile.DriverMode.LLVMPIPE

        if (requiresKgsl && isKgslAccessible) {
            args.add("-b")
            args.add(profile.gpu.kgslNode)
        } else if (requiresKgsl && !isKgslAccessible) {
            Log.w(TAG, "KGSL device node ${profile.gpu.kgslNode} is not accessible on host. Cleanly falling back to LLVMPIPE software rasterizer.")
        }

        // 6. XDG runtime socket directory (wayland-0, PipeWire audio socket)
        if (!sessionRuntimeDir.exists()) {
            sessionRuntimeDir.mkdirs()
        }
        args.add("-b")
        args.add("${sessionRuntimeDir.absolutePath}:/tmp/runtime-alw")

        // 7. External Mesa Turnip / Adreno driver package mount (shared driver store)
        if (profile.gpu.externalDriverId.isNotBlank() && profile.gpu.externalDriverId != "system-fallback") {
            val driverDir = dirs.getTurnipDriverDir(profile.gpu.externalDriverId).takeIf { it.exists() }
                ?: dirs.getDriverDir(profile.gpu.externalDriverId)
            if (driverDir.exists()) {
                args.add("-b")
                args.add("${driverDir.absolutePath}:/opt/drivers/turnip/active:ro")
                // Backwards-compatible /opt/turnip mount
                args.add("-b")
                args.add("${driverDir.absolutePath}:/opt/turnip:ro")
            }
        }

        // 8. Selective SDCard mount (with Scoped Storage Android/data & obb masking)
        val storageMounts = storageBridge.resolveStorageMounts(profile)
        for (mount in storageMounts) {
            args.add("-b")
            args.add(mount.toCliArg())
        }

        // 9. Optional x86_64 emulation hook
        if (profile.emulation.enabled) {
            val emulatorPath = when (profile.emulation.engine) {
                StackProfile.EmulationEngine.FEX -> "/opt/fex/bin/FEXInterpreter"
                StackProfile.EmulationEngine.BOX64 -> "/usr/bin/box64"
            }
            args.add("-q")
            args.add(emulatorPath)
        }

        // 10. Working directory
        val userHome = "/home/${profile.distro.defaultUser}"
        args.add("-w")
        args.add(userHome)

        // 11. Environment variables from EnvironmentGenerator (with safe LLVMPIPE fallback if KGSL missing)
        val env = if (requiresKgsl && !isKgslAccessible) {
            val fallbackProfile = profile.copy(
                gpu = profile.gpu.copy(
                    driverMode = StackProfile.DriverMode.LLVMPIPE,
                    mesaLoaderOverride = "llvmpipe"
                )
            )
            val fallbackEnv = EnvironmentGenerator.generateEnvironment(fallbackProfile, sessionRuntimeDir).toMutableMap()
            fallbackEnv["LIBGL_ALWAYS_SOFTWARE"] = "1"
            fallbackEnv["MESA_LOADER_DRIVER_OVERRIDE"] = "llvmpipe"
            fallbackEnv["GALLIUM_DRIVER"] = "llvmpipe"
            fallbackEnv.remove("VK_ICD_FILENAMES")
            fallbackEnv.remove("VK_DRIVER_FILES")
            fallbackEnv
        } else {
            EnvironmentGenerator.generateEnvironment(profile, sessionRuntimeDir)
        }

        args.add("/usr/bin/env")
        args.add("-i")
        for ((k, v) in env) {
            args.add("$k=$v")
        }

        // 12. Command to execute (wrapped with dbus-run-session for Wayland compositors)
        val execCmd = customExecCommand ?: profile.compositor.execCommand
        val execArgs = customArgs ?: profile.compositor.extraArgs

        // Wrap compositor sessions in dbus-run-session for clipboard & XDG portal services
        if (customExecCommand == null) {
            args.add("dbus-run-session")
            args.add("--")
        }
        args.add(execCmd)
        args.addAll(execArgs)

        // 13. Runner environment (host side for PRoot binary)
        val runnerEnv = mutableMapOf<String, String>()
        runnerEnv["PROOT_TMP_DIR"] = dirs.prootTmpDir.absolutePath
        if (File(dirs.loaderPath).exists()) {
            runnerEnv["PROOT_LOADER"] = dirs.loaderPath
        }
        if (File(dirs.nativeLibDir).exists()) {
            runnerEnv["LD_LIBRARY_PATH"] = dirs.nativeLibDir
        }

        // 14. Resolve Android linker64 execution wrapper for PIE .so files
        val useLinker = prootBinaryPath.endsWith(".so") && File("/system/bin/linker64").exists()
        val binary = if (useLinker) "/system/bin/linker64" else prootBinaryPath
        val finalArgs = if (useLinker) listOf(prootBinaryPath) + args else args

        return LaunchCommand(
            binary = binary,
            arguments = finalArgs,
            environment = runnerEnv,
            workingDir = rootfsDir
        )
    }
}
