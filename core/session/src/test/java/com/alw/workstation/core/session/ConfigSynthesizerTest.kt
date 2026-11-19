package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigSynthesizerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSynthesizeHyprlandSessionConfigHardwareAcceleration() {
        val profile = StackProfile.defaultArchXiaomiPad8()
        val config = ConfigSynthesizer.synthesizeHyprlandSessionConfig(profile)

        // Hyprland >= 0.57 Lua dialect (the INI dialect is silently ignored)
        assertTrue(config.contains("hl.config({"))
        assertTrue(config.contains("xwayland = { enabled = false }"))
        assertTrue(config.contains("debug = { enable_stdout_logs = true }"))
        assertTrue(config.contains("disable_hyprland_logo = true"))

        // Display scale and nested backend environment
        assertTrue(config.contains("hl.monitor({ output = \"\", mode = \"preferred\", position = \"auto\", scale = \"1.75\" })"))
        assertTrue(config.contains("hl.env(\"AQ_BACKEND\", \"wayland\")"))
        assertTrue(config.contains("hl.env(\"WLR_BACKENDS\", \"wayland\")"))
        assertTrue(config.contains("hl.env(\"LIBSEAT_BACKEND\", \"noop\")"))
        // The nested Aquamarine backend never exports a cursor sprite, so the session
        // keeps the compositor-composited software cursor (hardware cursors = invisible).
        assertTrue(config.contains("cursor = { no_hardware_cursors = 1 }"))
        assertFalse(config.contains("WLR_NO_HARDWARE_CURSORS"))

        // Autostart and keybindings use the Lua dispatcher API
        assertTrue(config.contains("hl.on(\"hyprland.start\", function()"))
        assertTrue(config.contains("hl.exec_cmd(\"alw-gui-run kitty --hold -e /bin/bash -c 'fastfetch; exec bash'\")"))
        assertTrue(config.contains("hl.bind(\"SUPER + Return\", hl.dsp.exec_cmd(\"kitty\"))"))
        assertTrue(config.contains("hl.bind(\"SUPER + M\", hl.dsp.exit())"))

        // Hardware accelerated visuals (Turnip Adreno 825). Rounding is owned by the
        // imported Sekai Pop theme layer, so the synthesizer does not pin it.
        assertTrue(config.contains("blur = { enabled = true, size = 5, passes = 2 }"))

        // No legacy INI directives may leak into the Lua session config
        assertFalse(config.contains("exec-once ="))
        assertFalse(config.contains("monitor = "))
        assertFalse(config.contains("env = AQ_BACKEND"))
        // The compositor must stay on the kms_swrast loader override
        assertFalse(config.contains("GALLIUM_DRIVER"))
    }

    @Test
    fun testSynthesizeHyprlandSessionConfigSoftwareFallback() {
        val base = StackProfile.defaultArchXiaomiPad8()
        val profile = base.copy(
            gpu = base.gpu.copy(
                driverMode = StackProfile.DriverMode.LLVMPIPE,
                mesaLoaderOverride = "llvmpipe"
            )
        )
        val config = ConfigSynthesizer.synthesizeHyprlandSessionConfig(profile)

        // Software fallback disables expensive decorations
        assertTrue(config.contains("blur = { enabled = false }"))
        assertFalse(config.contains("size = 5"))
    }

    @Test
    fun testSynthesizeImportsHostLuaConfigBeforeOverrides() {
        val profile = StackProfile.defaultArchXiaomiPad8()
        val config = ConfigSynthesizer.synthesizeHyprlandSessionConfig(profile)

        // Modular require(...) resolution for the imported CachyOS tree
        assertTrue(
            config.contains(
                "package.path = \"/home/alw/.config/hypr/?.lua;" +
                    "/home/alw/.config/hypr/?/init.lua;/root/.config/hypr/?.lua;\" .. package.path"
            )
        )

        // User entry + Sekai Pop theme are pcall-guarded
        assertTrue(config.contains("pcall(dofile, entry)"))
        assertTrue(config.contains("/home/alw/.config/hypr/hyprland.lua"))
        assertTrue(config.contains("pcall(dofile, \"/home/alw/.config/hypr/sekaipop-theme.lua\")"))

        // Android/PRoot overrides must land AFTER the user config loads
        val userLoad = config.indexOf("/home/alw/.config/hypr/hyprland.lua")
        val themeLoad = config.indexOf("sekaipop-theme.lua")
        val overrides = config.indexOf("xwayland = { enabled = false }")
        assertTrue(userLoad in 0 until themeLoad)
        assertTrue(themeLoad < overrides)

        // Sekai Pop rice autostart (wallpaper + PRoot-safe Waybar)
        assertTrue(config.contains("swaybg -i"))
        assertTrue(config.contains("waybar -c \$B/config.jsonc"))
        assertTrue(config.contains("qs -c sekaipop"))
        assertTrue(config.contains("hl.window_rule({ match = { class = \"^(kitty)$\" }"))
        assertFalse(config.contains("hyprland/workspaces"))
    }

    @Test
    fun testGenerateDefaultHyprlandUserConfig() {
        val userConfig = ConfigSynthesizer.generateDefaultHyprlandUserConfig()

        assertTrue(userConfig.contains("Xiaomi Pad 8 Default Hyprland Configuration"))
        assertTrue(userConfig.contains("monitor = , preferred, auto, 1.75"))
        assertTrue(userConfig.contains("bind = \$mainMod, Return, exec, \$terminal"))
        assertTrue(userConfig.contains("tablet {"))
        assertTrue(userConfig.contains("touchdevice {"))
    }

    @Test
    fun testGenerateDefaultLabwcRcXml() {
        val profile = StackProfile.safeModeProfile()
        val rcXml = ConfigSynthesizer.generateDefaultLabwcRcXml(profile)

        assertTrue(rcXml.contains("<labwc_config>"))
        assertTrue(rcXml.contains("<name>Openbox</name>"))
        assertTrue(rcXml.contains("<layout>NL:IMC</layout>"))
        assertTrue(rcXml.contains("<decoration>server</decoration>"))
        assertTrue(rcXml.contains("<windowRule identifier=\"*\" serverDecoration=\"yes\" />"))
        assertTrue(rcXml.contains("<keybind key=\"W-Return\"><action name=\"Execute\" command=\"weston-terminal --shell=/usr/bin/bash\" /></keybind>"))
        assertTrue(rcXml.contains("<keybind key=\"A-Return\"><action name=\"Execute\" command=\"kitty\" /></keybind>"))
        assertTrue(rcXml.contains("<keybind key=\"W-q\"><action name=\"Close\" /></keybind>"))
        assertTrue(rcXml.contains("<keybind key=\"W-m\"><action name=\"Exit\" /></keybind>"))
        assertTrue(rcXml.contains("<naturalScroll>true</naturalScroll>"))
    }

    @Test
    fun testGenerateDefaultLabwcAutostart() {
        val autostart = ConfigSynthesizer.generateDefaultLabwcAutostart()
        assertTrue(autostart.contains("kitty &"))
        assertTrue(autostart.contains("weston-terminal --shell=/usr/bin/bash &"))
    }

    @Test
    fun testEnsureDefaultConfigsExistPreservesExistingUserDotfiles() {
        val rootfsDir = tempFolder.newFolder("rootfs")
        val user = "alw"
        val profile = StackProfile.defaultArchXiaomiPad8()

        // 1. Initial creation
        ConfigSynthesizer.ensureDefaultConfigsExist(rootfsDir, user, profile)

        val hyprFile = File(rootfsDir, "home/$user/.config/hypr/hyprland.conf")
        val labwcFile = File(rootfsDir, "home/$user/.config/labwc/rc.xml")
        val autostartFile = File(rootfsDir, "home/$user/.config/labwc/autostart")

        assertTrue(hyprFile.exists())
        assertTrue(labwcFile.exists())
        assertTrue(autostartFile.exists())

        // 2. Modify user config to verify Non-Destructive Invariant
        val customizedConfig = "# Custom user line\nexec-once = waybar\n"
        hyprFile.writeText(customizedConfig)

        // 3. Second run: ensureDefaultConfigsExist must NOT overwrite customized config
        ConfigSynthesizer.ensureDefaultConfigsExist(rootfsDir, user, profile)
        assertEquals(customizedConfig, hyprFile.readText())
    }
}
