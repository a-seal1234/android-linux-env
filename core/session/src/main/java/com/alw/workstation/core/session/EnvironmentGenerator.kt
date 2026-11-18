package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile
import java.io.File

/**
 * Generates runtime environment variable maps for guest Linux execution.
 */
object EnvironmentGenerator {

    fun generateEnvironment(profile: StackProfile, runtimeDir: File): Map<String, String> {
        val env = mutableMapOf<String, String>()

        val userHome = "/home/${profile.distro.defaultUser}"

        // 1. Base UNIX environment
        env["TERM"] = "xterm-256color"
        env["HOME"] = userHome
        env["USER"] = profile.distro.defaultUser
        env["LOGNAME"] = profile.distro.defaultUser
        env["SHELL"] = profile.distro.shell
        env["PATH"] = "/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin"
        env["UV_USE_IO_URING"] = "0"
        env["APPIMAGE_EXTRACT_AND_RUN"] = "1"
        // Android 15+ app seccomp policies trap rseq (SECCOMP_RET_TRAP -> SIGSYS);
        // a blocked/ignored SIGSYS degrades to ENOSYS, so disable glibc rseq registration
        env["GLIBC_TUNABLES"] = "glibc.pthread.rseq=0"

        // 2. Wayland runtime environment & nested compositor backend
        env["XDG_RUNTIME_DIR"] = "/tmp/rt"
        env["WAYLAND_DISPLAY"] = "wayland-0"
        env["XDG_SESSION_TYPE"] = "wayland"
        env["AQ_BACKEND"] = "wayland"
        env["WLR_BACKENDS"] = "wayland"
        env["WLR_RENDERER"] = when (profile.gpu.driverMode) {
            StackProfile.DriverMode.TURNIP_KGSL,
            StackProfile.DriverMode.TURNIP_ZINK,
            StackProfile.DriverMode.ADRENO_PROPRIETARY_ZINK -> "gles2"
            StackProfile.DriverMode.LLVMPIPE -> "pixman"
            StackProfile.DriverMode.VIRGL -> "gles2"
        }
        env["XDG_CURRENT_DESKTOP"] = when (profile.compositor.type) {
            StackProfile.CompositorType.HYPRLAND -> "Hyprland"
            StackProfile.CompositorType.LABWC -> "LabWC"
            StackProfile.CompositorType.PLASMA -> "KDE"
            StackProfile.CompositorType.CUSTOM -> "Wayland"
        }

        // 2b. Hyprland / Aquamarine nested-backend requirements
        if (profile.compositor.type == StackProfile.CompositorType.HYPRLAND) {
            // No systemd-logind / seatd inside PRoot: force the noop libseat backend.
            env["LIBSEAT_BACKEND"] = "noop"
            // The Android Wayland bridge exposes no dedicated DRM cursor plane.
            env["WLR_NO_HARDWARE_CURSORS"] = "1"
        }

        // 3. Application toolkit Wayland flags
        env["ELECTRON_OZONE_PLATFORM_HINT"] = "wayland"
        env["MOZ_ENABLE_WAYLAND"] = "1"
        env["QT_QPA_PLATFORM"] = "wayland"
        env["GDK_BACKEND"] = "wayland"

        // 4. High-Speed UFS Build Cache Redirection
        if (profile.storage.cacheRedirection.enabled) {
            val baseCache = profile.storage.cacheRedirection.internalBaseDir
            env["CARGO_TARGET_DIR"] = "$baseCache/cargo-target"
            env["UV_PROJECT_ENVIRONMENT"] = "$baseCache/uv-env"
            env["PNPM_STORE_DIR"] = "$baseCache/pnpm-store"
            env["GOCACHE"] = "$baseCache/go-build"
            env["CCACHE_DIR"] = "$baseCache/ccache"
            env["GRADLE_USER_HOME"] = "$baseCache/gradle-user-home"
        }

        // 5. GPU driver acceleration environment
        when (profile.gpu.driverMode) {
            StackProfile.DriverMode.TURNIP_KGSL, StackProfile.DriverMode.TURNIP_ZINK -> {
                val icdPath = "/opt/drivers/turnip/active/freedreno_icd.aarch64.json"
                env["VK_ICD_FILENAMES"] = icdPath
                env["VK_DRIVER_FILES"] = icdPath
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
                env["GALLIUM_DRIVER"] = "zink"
                env["TU_DEBUG"] = "noconform"
                // Aquamarine/EGL advertises GLES via Zink; expose the GL 4.6 surface level
                env["MESA_GL_VERSION_OVERRIDE"] = "4.6"
            }
            StackProfile.DriverMode.ADRENO_PROPRIETARY_ZINK -> {
                val icdPath = "/opt/drivers/turnip/active/adreno_icd.aarch64.json"
                env["VK_ICD_FILENAMES"] = icdPath
                env["VK_DRIVER_FILES"] = icdPath
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
                env["GALLIUM_DRIVER"] = "zink"
            }
            StackProfile.DriverMode.LLVMPIPE -> {
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "llvmpipe"
                env["GALLIUM_DRIVER"] = "llvmpipe"
                env["LIBGL_ALWAYS_SOFTWARE"] = "1"
            }
            StackProfile.DriverMode.VIRGL -> {
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "virgl"
            }
        }

        // Custom mesa loader override if explicitly specified
        profile.gpu.mesaLoaderOverride?.let {
            env["MESA_LOADER_DRIVER_OVERRIDE"] = it
        }

        // 6. Low-Latency Audio Subsystem (PulseAudio & PipeWire)
        env["PULSE_SERVER"] = "unix:/run/user/1000/pulse/native"
        env["PIPEWIRE_RUNTIME_DIR"] = "/tmp/rt"

        // 7. x86_64 Emulation & GPU Thunking environment (FEX-Emu / box64)
        if (profile.emulation.enabled) {
            when (profile.emulation.engine) {
                StackProfile.EmulationEngine.FEX -> {
                    if (profile.emulation.thunkLibsEnabled) {
                        env["FEX_THUNKHOSTPATH"] = "/opt/overlays/fex/active/thunks/host"
                        env["FEX_THUNKGUESTPATH"] = "/opt/overlays/fex/active/thunks/guest"
                        env["FEX_ROOTFS"] = "/opt/overlays/fex/active/rootfs"
                    }
                }
                StackProfile.EmulationEngine.BOX64 -> {
                    env["BOX64_LD_LIBRARY_PATH"] = "/opt/overlays/box64/active/lib:/opt/drivers/turnip/active/lib"
                    env["BOX64_PATH"] = "/opt/overlays/box64/active/bin"
                    env["BOX64_LOG"] = "1"
                    env["BOX64_NOBANNER"] = "1"
                }
            }
        }

        // 8. User and profile environment overrides
        env.putAll(profile.gpu.environmentOverrides)

        // 9. Compositor GBM driver split (TASK-024).
        // The compositor initializes EGL/GBM on the virtual "vgem" DRM node created by
        // PRoot. Zink requires a real MSM/kgsl DRM backend there, so the compositor
        // process must use Mesa's software kms_swrast path; child graphical applications
        // still receive Turnip/Zink through the compositor's own config (hyprland.conf).
        if (profile.compositor.type == StackProfile.CompositorType.HYPRLAND) {
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "kms_swrast"
            // Empirically verified on-device: explicitly selecting a gallium driver
            // (GALLIUM_DRIVER=*) or LIBGL_ALWAYS_SOFTWARE=1 alongside the kms_swrast
            // loader override makes eglInitialize() fail with EGL_NOT_INITIALIZED on the
            // virtual vgem node. Leaving both unset makes EGL-on-GBM initialize cleanly.
            env.remove("GALLIUM_DRIVER")
            env["LIBGL_ALWAYS_SOFTWARE"] = "0"
        }

        return env
    }
}
