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

        // 2. Wayland runtime environment
        env["XDG_RUNTIME_DIR"] = "/tmp/runtime-alw"
        env["WAYLAND_DISPLAY"] = "wayland-0"
        env["XDG_SESSION_TYPE"] = "wayland"
        env["XDG_CURRENT_DESKTOP"] = when (profile.compositor.type) {
            StackProfile.CompositorType.HYPRLAND -> "Hyprland"
            StackProfile.CompositorType.LABWC -> "LabWC"
            StackProfile.CompositorType.PLASMA -> "KDE"
            StackProfile.CompositorType.CUSTOM -> "Wayland"
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
                env["LIBGL_DRIVERS_PATH"] = "/opt/drivers/turnip/active/lib/dri"
                env["LD_LIBRARY_PATH"] = "/opt/drivers/turnip/active/lib"
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
                env["GALLIUM_DRIVER"] = "zink"
                if (profile.gpu.tuDebugNoConform) {
                    env["TU_DEBUG"] = "noconform"
                }
            }
            StackProfile.DriverMode.ADRENO_PROPRIETARY_ZINK -> {
                val icdPath = "/opt/drivers/turnip/active/adreno_icd.aarch64.json"
                env["VK_ICD_FILENAMES"] = icdPath
                env["VK_DRIVER_FILES"] = icdPath
                env["LIBGL_DRIVERS_PATH"] = "/opt/drivers/turnip/active/lib/dri"
                env["LD_LIBRARY_PATH"] = "/opt/drivers/turnip/active/lib"
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

        // 6. User and profile environment overrides
        env.putAll(profile.gpu.environmentOverrides)

        return env
    }
}
