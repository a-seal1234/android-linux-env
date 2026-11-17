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
    private val kgslNodeChecker: (String) -> Boolean = { File(it).exists() },
    private val fakeFsManager: com.alw.workstation.core.proot.fakefs.FakeFsManager =
        com.alw.workstation.core.proot.fakefs.FakeFsManager(dirs)
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

        // Ensure guest rootfs /dev/pts exists for internal PTY allocation
        val guestDevPts = File(rootfsDir, "dev/pts")
        if (!guestDevPts.exists()) {
            guestDevPts.mkdirs()
        }

        // Host /dev/pts is intentionally kept unmounted on unrooted Android so that
        // PRoot's internal openpty/forkpty interception handles pseudo-terminals cleanly.

        // Fake filesystem (host-backed writable runtime dirs, resolv.conf/hosts, synthetic proc nodes)
        fakeFsManager.materialize()
        for (mount in fakeFsManager.getBindMounts()) {
            args.add("-b")
            args.add(mount.toCliArg())
        }

        // Materialize PRoot rice compatibility shims (systemctl, fakeroot, makepkg, sudo) in /usr/local/bin
        val guestLocalBin = File(rootfsDir, "usr/local/bin")
        com.alw.workstation.core.proot.shim.RiceShimGenerator.installShims(guestLocalBin)

        // 5. Direct Adreno KGSL hardware GPU device node & Fallback check
        val isKgslAccessible = kgslNodeChecker(profile.gpu.kgslNode)
        val requiresKgsl = profile.gpu.driverMode != StackProfile.DriverMode.LLVMPIPE

        if (requiresKgsl && isKgslAccessible) {
            args.add("-b")
            args.add(profile.gpu.kgslNode)
        } else if (requiresKgsl && !isKgslAccessible) {
            Log.w(TAG, "KGSL device node ${profile.gpu.kgslNode} is not accessible on host. Cleanly falling back to LLVMPIPE software rasterizer.")
        }

        // 6. XDG runtime socket directory (wayland-0, PipeWire/PulseAudio socket)
        if (!sessionRuntimeDir.exists()) {
            sessionRuntimeDir.mkdirs()
        }
        val sessionPulseDir = File(sessionRuntimeDir, "pulse")
        if (!sessionPulseDir.exists()) {
            sessionPulseDir.mkdirs()
        }
        val isInsideFakefsTmp = try {
            sessionRuntimeDir.canonicalPath.startsWith(fakeFsManager.tmpDir.canonicalPath)
        } catch (_: Throwable) {
            sessionRuntimeDir.absolutePath.startsWith(fakeFsManager.tmpDir.absolutePath)
        }
        if (!isInsideFakefsTmp) {
            args.add("-b")
            args.add("${sessionRuntimeDir.absolutePath}:/tmp/runtime-alw")

            // Also bind-mount to /run/user/1000 for standard Linux client compatibility
            val guestRunUser1000 = File(rootfsDir, "run/user/1000")
            if (!guestRunUser1000.exists()) {
                guestRunUser1000.mkdirs()
            }
            args.add("-b")
            args.add("${sessionRuntimeDir.absolutePath}:/run/user/1000")
        }

        // 7. External Mesa Turnip / Adreno driver package mount (shared driver store)
        if (profile.gpu.externalDriverId.isNotBlank() && profile.gpu.externalDriverId != "system-fallback") {
            val driverDir = dirs.getTurnipDriverDir(profile.gpu.externalDriverId).takeIf { it.exists() }
                ?: dirs.getActiveTurnipDriverDir().takeIf { it.exists() }
                ?: dirs.getDriverDir(profile.gpu.externalDriverId)
            if (driverDir.exists()) {
                val resolvedDriverDir = driverDir.canonicalFile
                val targetDir = File(rootfsDir, "opt/drivers/turnip/active")
                if (!targetDir.exists()) targetDir.mkdirs()
                val compatDir = File(rootfsDir, "opt/turnip")
                if (!compatDir.exists()) compatDir.mkdirs()

                args.add("-b")
                args.add("${resolvedDriverDir.absolutePath}:/opt/drivers/turnip/active")
                // Backwards-compatible /opt/turnip mount
                args.add("-b")
                args.add("${resolvedDriverDir.absolutePath}:/opt/turnip")
            }
        }

        // 8. Selective SDCard mount (with Scoped Storage Android/data & obb masking)
        val storageMounts = storageBridge.resolveStorageMounts(profile)
        for (mount in storageMounts) {
            val gPath = mount.guestPath ?: mount.hostPath
            val targetDir = File(rootfsDir, gPath.removePrefix("/"))
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            args.add("-b")
            args.add(mount.toCliArg())
        }

        // 9. Optional x86_64 emulation hook & shared overlay bind-mount
        if (profile.emulation.enabled) {
            val engineName = profile.emulation.engine.name.lowercase()
            val overlayDir = dirs.getOverlayDir(engineName, profile.emulation.overlayVersion).takeIf { it.exists() }
                ?: dirs.getActiveOverlayDir(engineName).takeIf { it.exists() }
            if (overlayDir != null && overlayDir.exists()) {
                args.add("-b")
                args.add("${overlayDir.absolutePath}:/opt/overlays/$engineName/active:ro")
                // Legacy / fallback mount for /opt/fex or /opt/box64
                args.add("-b")
                args.add("${overlayDir.absolutePath}:/opt/$engineName:ro")
            }

            val emulatorPath = profile.emulation.customInterpreterPath
                ?: when (profile.emulation.engine) {
                    StackProfile.EmulationEngine.FEX -> {
                        if (overlayDir != null && overlayDir.exists()) {
                            "/opt/overlays/fex/active/bin/FEXInterpreter"
                        } else {
                            "/opt/fex/bin/FEXInterpreter"
                        }
                    }
                    StackProfile.EmulationEngine.BOX64 -> {
                        if (overlayDir != null && overlayDir.exists()) {
                            "/opt/overlays/box64/active/bin/box64"
                        } else {
                            "/usr/bin/box64"
                        }
                    }
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
        val execArgs = (customArgs ?: profile.compositor.extraArgs).toMutableList()

        if ((execCmd == "Hyprland" || profile.compositor.type == StackProfile.CompositorType.HYPRLAND) &&
            !execArgs.contains("--i-am-really-stupid")
        ) {
            execArgs.add("--i-am-really-stupid")
        }

        // Wrap compositor sessions in dbus-run-session for clipboard & XDG portal services
        if (customExecCommand == null && execCmd != "startplasma-wayland" && !execCmd.contains("dbus-run-session")) {
            args.add("dbus-run-session")
            args.add("--")
        }
        args.add(execCmd)
        args.addAll(execArgs)

        // 13. Runner environment (host side for PRoot binary)
        val runnerEnv = mutableMapOf<String, String>()
        runnerEnv["PROOT_TMP_DIR"] = dirs.prootTmpDir.absolutePath
        runnerEnv["PROOT_LOADER"] = dirs.loaderPath
        runnerEnv["PROOT_NO_SECCOMP"] = "1"
        // Must be present in the host-side env as well: /usr/bin/env itself is a guest glibc
        // binary whose ld.so would register rseq (SIGSYS under Android 15+ seccomp) before
        // it ever exports GLIBC_TUNABLES to its children.
        runnerEnv["GLIBC_TUNABLES"] = "glibc.pthread.rseq=0"

        // 14. Resolve execution binary (direct execution if binary is executable, otherwise fallback to linker64 if needed)
        val useLinker = prootBinaryPath.endsWith(".so") && !File(prootBinaryPath).canExecute() && File("/system/bin/linker64").canExecute()
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
