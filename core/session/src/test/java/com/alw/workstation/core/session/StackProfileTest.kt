package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StackProfileTest {

    @Test
    fun testDefaultArchXiaomiPad8Profile() {
        val profile = StackProfile.defaultArchXiaomiPad8()
        assertEquals("arch-xiaomi-pad-8", profile.id)
        assertEquals("aarch64", profile.distro.architecture)
        assertEquals("archlinux-default", profile.distro.rootfsId)
        assertEquals(StackProfile.CompositorType.HYPRLAND, profile.compositor.type)
        assertEquals(StackProfile.DriverMode.FREEDRENO_KGSL, profile.gpu.driverMode)
        assertEquals("/dev/kgsl-3d0", profile.gpu.kgslNode)
        assertEquals(1.6f, profile.display.scaleFactor, 0.001f)
        assertEquals(144, profile.display.refreshRateHz)
        assertTrue(profile.input.requestPointerCapture)
        assertTrue(profile.input.rebindCapsToSuper)
        assertTrue(profile.storage.cacheRedirection.enabled)
        assertTrue(profile.storage.excludePaths.contains("/storage/emulated/0/Android/data"))
    }

    @Test
    fun testSafeModeProfile() {
        val profile = StackProfile.safeModeProfile()
        assertEquals("safe-mode", profile.id)
        assertEquals(StackProfile.CompositorType.LABWC, profile.compositor.type)
        assertEquals(StackProfile.DriverMode.LLVMPIPE, profile.gpu.driverMode)
        assertEquals("llvmpipe", profile.gpu.mesaLoaderOverride)
        assertFalse(profile.storage.bindSdcard)
        assertFalse(profile.input.rebindCapsToSuper)
    }

    @Test
    fun testConfigSynthesizerNonDestructiveWrapper() {
        val profile = StackProfile.defaultArchXiaomiPad8()
        val config = ConfigSynthesizer.synthesizeHyprlandSessionConfig(profile)

        // Verifies the Lua session override is additive: the user config path is
        // referenced (never rewritten) and overrides are separate directives.
        assertTrue(config.contains("stays untouched at /home/alw/.config/hypr/hyprland.conf"))
        assertTrue(config.contains("hl.config({"))
        assertTrue(config.contains("hl.monitor({"))
        assertTrue(config.contains("hl.env(\"WAYLAND_DISPLAY\", \"wayland-0\")"))
        assertTrue(config.contains("hl.env(\"ELECTRON_OZONE_PLATFORM_HINT\", \"wayland\")"))
    }

    @Test
    fun testJsonSerializationRoundTrip() {
        val profile = StackProfile.defaultArchXiaomiPad8()
        val json = profile.toJson()
        assertEquals("1.0.0", json.getString("schemaVersion"))
        assertEquals("arch-xiaomi-pad-8", json.getString("id"))
        assertEquals("freedreno_kgsl", json.getJSONObject("gpu").getString("driverMode"))

        val parsed = StackProfile.fromJson(json)
        assertEquals(profile.id, parsed.id)
        assertEquals(profile.name, parsed.name)
        assertEquals(profile.distro.rootfsId, parsed.distro.rootfsId)
        assertEquals(profile.compositor.type, parsed.compositor.type)
        assertEquals(profile.gpu.driverMode, parsed.gpu.driverMode)
        assertEquals(profile.display.scaleFactor, parsed.display.scaleFactor)
        assertEquals(profile.display.refreshRateHz, parsed.display.refreshRateHz)
        assertEquals(profile.storage.bindSdcard, parsed.storage.bindSdcard)
        assertEquals(profile.input.requestPointerCapture, parsed.input.requestPointerCapture)
    }

    @Test
    fun testFromJsonString() {
        val original = StackProfile.safeModeProfile()
        val jsonString = original.toJson().toString()
        val reconstructed = StackProfile.fromJsonString(jsonString)

        assertEquals("safe-mode", reconstructed.id)
        assertEquals(StackProfile.CompositorType.LABWC, reconstructed.compositor.type)
        assertEquals(StackProfile.DriverMode.LLVMPIPE, reconstructed.gpu.driverMode)
    }

    @Test
    fun testEmulationConfigSerializationRoundTrip() {
        val original = StackProfile.defaultArchXiaomiPad8().copy(
            emulation = StackProfile.EmulationConfig(
                enabled = true,
                engine = StackProfile.EmulationEngine.FEX,
                thunkLibsEnabled = true,
                overlayVersion = "fex-2412",
                customInterpreterPath = "/opt/fex/bin/custom"
            )
        )
        val json = original.toJson()
        val parsed = StackProfile.fromJson(json)

        assertTrue(parsed.emulation.enabled)
        assertEquals(StackProfile.EmulationEngine.FEX, parsed.emulation.engine)
        assertTrue(parsed.emulation.thunkLibsEnabled)
        assertEquals("fex-2412", parsed.emulation.overlayVersion)
        assertEquals("/opt/fex/bin/custom", parsed.emulation.customInterpreterPath)
    }

    @Test
    fun testKdePlasmaProfile() {
        val profile = StackProfile.kdePlasmaProfile()
        assertEquals("arch-kde-plasma", profile.id)
        assertEquals(StackProfile.CompositorType.PLASMA, profile.compositor.type)
        assertEquals("startplasma-wayland", profile.compositor.execCommand)
        assertEquals(StackProfile.DriverMode.TURNIP_KGSL, profile.gpu.driverMode)
        assertEquals("zink", profile.gpu.mesaLoaderOverride)
    }
}
