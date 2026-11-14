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
        assertEquals("wayland", env["AQ_BACKEND"])
        assertEquals("wayland", env["WLR_BACKENDS"])
        assertEquals("gles2", env["WLR_RENDERER"])
        assertEquals("wayland", env["ELECTRON_OZONE_PLATFORM_HINT"])
        assertEquals("1", env["MOZ_ENABLE_WAYLAND"])
        assertEquals("wayland", env["QT_QPA_PLATFORM"])

        // Build cache redirection
        assertEquals("/home/alw/.cache/cargo-target", env["CARGO_TARGET_DIR"])
        assertEquals("/home/alw/.cache/uv-env", env["UV_PROJECT_ENVIRONMENT"])
        assertEquals("/home/alw/.cache/pnpm-store", env["PNPM_STORE_DIR"])
        assertEquals("/home/alw/.cache/go-build", env["GOCACHE"])

        // Turnip KGSL variables
        assertEquals("/opt/drivers/turnip/active/freedreno_icd.aarch64.json", env["VK_ICD_FILENAMES"])
        assertEquals("/opt/drivers/turnip/active/freedreno_icd.aarch64.json", env["VK_DRIVER_FILES"])
        assertEquals("/opt/drivers/turnip/active/lib/dri", env["LIBGL_DRIVERS_PATH"])
        assertEquals("/opt/drivers/turnip/active/lib", env["LD_LIBRARY_PATH"])
        assertEquals("zink", env["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("zink", env["GALLIUM_DRIVER"])
        assertEquals("noconform", env["TU_DEBUG"])

        // Audio bridge variables
        assertTrue(env["PULSE_SERVER"]!!.contains("/run/user/1000/pulse/native"))
        assertTrue(env["PULSE_SERVER"]!!.contains("/tmp/runtime-alw/pulse/native"))
        assertEquals("/tmp/runtime-alw", env["PIPEWIRE_RUNTIME_DIR"])
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

        assertEquals("/opt/drivers/turnip/active/adreno_icd.aarch64.json", env["VK_ICD_FILENAMES"])
        assertEquals("/opt/drivers/turnip/active/adreno_icd.aarch64.json", env["VK_DRIVER_FILES"])
        assertEquals("zink", env["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("zink", env["GALLIUM_DRIVER"])
    }

    @Test
    fun testSafeModeLlvmpipeEnvironment() {
        val profile = StackProfile.safeModeProfile()
        val runtimeDir = File("/tmp/runtime-0")
        val env = EnvironmentGenerator.generateEnvironment(profile, runtimeDir)

        assertEquals("LabWC", env["XDG_CURRENT_DESKTOP"])
        assertEquals("llvmpipe", env["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("llvmpipe", env["GALLIUM_DRIVER"])
        assertEquals("1", env["LIBGL_ALWAYS_SOFTWARE"])
        assertNull(env["VK_ICD_FILENAMES"])
        assertNull(env["VK_DRIVER_FILES"])
    }
}
