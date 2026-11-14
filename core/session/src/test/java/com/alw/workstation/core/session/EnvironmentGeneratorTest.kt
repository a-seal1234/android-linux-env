package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnvironmentGeneratorTest {

    @Test
    fun testTurnipKgslEnvironment() {
        val profile = StackProfile.defaultArchXiaomiPad8()
        val runtimeDir = File("/tmp/runtime-0")
        val env = EnvironmentGenerator.generateEnvironment(profile, runtimeDir)

        assertEquals("wayland-0", env["WAYLAND_DISPLAY"])
        assertEquals("/tmp/runtime-alw", env["XDG_RUNTIME_DIR"])
        assertEquals("Hyprland", env["XDG_CURRENT_DESKTOP"])
        assertEquals("wayland", env["ELECTRON_OZONE_PLATFORM_HINT"])
        assertEquals("1", env["MOZ_ENABLE_WAYLAND"])
        assertEquals("wayland", env["QT_QPA_PLATFORM"])

        // Build cache redirection
        assertEquals("/home/alw/.cache/cargo-target", env["CARGO_TARGET_DIR"])
        assertEquals("/home/alw/.cache/uv-env", env["UV_PROJECT_ENVIRONMENT"])
        assertEquals("/home/alw/.cache/pnpm-store", env["PNPM_STORE_DIR"])
        assertEquals("/home/alw/.cache/go-build", env["GOCACHE"])

        // Turnip KGSL variables
        assertEquals("/opt/turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json", env["VK_ICD_FILENAMES"])
        assertEquals("/opt/turnip/lib/dri", env["LIBGL_DRIVERS_PATH"])
        assertEquals("/opt/turnip/lib", env["LD_LIBRARY_PATH"])
        assertEquals("kgsl", env["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("noconform", env["TU_DEBUG"])
    }

    @Test
    fun testAdrenoProprietaryZinkEnvironment() {
        val profile = StackProfile.defaultArchXiaomiPad8().copy(
            gpu = StackProfile.defaultArchXiaomiPad8().gpu.copy(
                driverMode = StackProfile.DriverMode.ADRENO_PROPRIETARY_ZINK,
                mesaLoaderOverride = "zink"
            )
        )
        val runtimeDir = File("/tmp/runtime-0")
        val env = EnvironmentGenerator.generateEnvironment(profile, runtimeDir)

        assertEquals("/opt/turnip/share/vulkan/icd.d/adreno_icd.aarch64.json", env["VK_ICD_FILENAMES"])
        assertEquals("zink", env["MESA_LOADER_DRIVER_OVERRIDE"])
    }

    @Test
    fun testSafeModeLlvmpipeEnvironment() {
        val profile = StackProfile.safeModeProfile()
        val runtimeDir = File("/tmp/runtime-0")
        val env = EnvironmentGenerator.generateEnvironment(profile, runtimeDir)

        assertEquals("LabWC", env["XDG_CURRENT_DESKTOP"])
        assertEquals("llvmpipe", env["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("1", env["LIBGL_ALWAYS_SOFTWARE"])
        assertNull(env["VK_ICD_FILENAMES"])
    }
}
