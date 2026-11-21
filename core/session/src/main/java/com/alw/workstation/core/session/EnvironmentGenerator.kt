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
            StackProfile.DriverMode.FREEDRENO_KGSL -> "gles2"
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
                env["TU_DEBUG"] = "noconform,noubwc"
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
            StackProfile.DriverMode.FREEDRENO_KGSL -> {
                // Direct GL/GLES on the Adreno via Mesa's freedreno Gallium driver: no
                // Vulkan, hence no Zink WSI/explicit-sync fence path. Turnip stays
                // exported so Vulkan applications keep working.
                val icdPath = "/opt/drivers/turnip/active/freedreno_icd.aarch64.json"
                env["VK_ICD_FILENAMES"] = icdPath
                env["VK_DRIVER_FILES"] = icdPath
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "kgsl"
                env["GALLIUM_DRIVER"] = "kgsl"
                env["LIBGL_ALWAYS_SOFTWARE"] = "0"
                // The host bridge presents guest dma-bufs by mmap + linear upload
                // (Android EGL has no EGL_EXT_image_dma_buf_import, and gralloc
                // rejects a raw dma_heap handle), so the buffer contents must be
                // linear. freedreno otherwise renders tiled/UBWC and the frame
                // arrives as macro-tile shear; sysmem+noubwc keeps it linear.
                // Verified on-device 2026-11-21 (TASK-035).
                env["FD_MESA_DEBUG"] = "sysmem,noubwc"
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

        // Custom mesa loader override if explicitly specified. The freedreno driver mode
        // owns the loader name, so a leftover "zink" in the profile cannot pull guest
        // GL back onto the Vulkan path.
        if (profile.gpu.driverMode != StackProfile.DriverMode.FREEDRENO_KGSL) {
            profile.gpu.mesaLoaderOverride?.let {
                env["MESA_LOADER_DRIVER_OVERRIDE"] = it
            }
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

        // 9. GPU split (TASK-025).
        //
        // Turnip is exported for the whole session; on-device `vulkaninfo` reports
        // "Adreno (TM) 825 / turnip Mesa driver" and `eglinfo` shows
        // "zink Vulkan 1.4(Adreno (TM) 825 (MESA_TURNIP))" on the surfaceless platform.
        //
        // The compositor itself must keep Mesa's kms_swrast path: forcing zink on the
        // PRoot virtual DRM node fails GBM EGL init ("gbm platform: eglInitialize
        // failed"), which leaves Hyprland with no renderer at all (verified on-device:
        // zero frames reach the bridge). Guest applications are launched through
        // alw-gui-run, which exports Turnip+Zink so they render on the Adreno 825.
        when (profile.gpu.driverMode) {
            StackProfile.DriverMode.TURNIP_KGSL, StackProfile.DriverMode.TURNIP_ZINK -> {
                // Installed by mesa-vulkan-freedreno inside the guest rootfs.
                val packagedIcd = "/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"
                env["VK_ICD_FILENAMES"] = packagedIcd
                env["VK_DRIVER_FILES"] = packagedIcd
                env["TU_DEBUG"] = env["TU_DEBUG"] ?: "noconform,noubwc"
                env["MESA_VK_WSI_PRESENT_MODE"] = "immediate"
                env["LIBGL_ALWAYS_SOFTWARE"] = "0"
            }
            StackProfile.DriverMode.ADRENO_PROPRIETARY_ZINK -> {
                env["TU_DEBUG"] = env["TU_DEBUG"] ?: "noconform,noubwc"
                env["MESA_VK_WSI_PRESENT_MODE"] = "immediate"
            }
            else -> Unit
        }

        // freedreno initializes GBM EGL on the PRoot virtual DRM node (verified
        // on-device), so it is the one accelerated mode where the compositor is not
        // demoted to kms_swrast.
        if (profile.compositor.type == StackProfile.CompositorType.HYPRLAND &&
            profile.gpu.driverMode != StackProfile.DriverMode.LLVMPIPE &&
            profile.gpu.driverMode != StackProfile.DriverMode.FREEDRENO_KGSL
        ) {
            if (profile.gpu.forceCompositorZink) {
                // TASK-026 Phase 3b research path: put the compositor itself on
                // Turnip+Zink. Off by default; see the task's bail-out rule.
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
                env["GALLIUM_DRIVER"] = "zink"
                env["LIBGL_ALWAYS_SOFTWARE"] = "0"
                env["ALW_COMPOSITOR_ZINK"] = "1"
            } else {
                // The verified default: the compositor keeps Mesa's kms_swrast path on
                // the PRoot virtual DRM node (zink fails GBM EGL init there), plus the
                // immediate llvmpipe relief applied to the synthesized Hyprland config.
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "kms_swrast"
                env.remove("GALLIUM_DRIVER")
                env["ALW_COMPOSITOR_ZINK"] = "0"
            }
        }

        return env
    }
}
