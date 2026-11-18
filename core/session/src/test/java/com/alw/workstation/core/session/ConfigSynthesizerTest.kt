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
    fun testSynthesizeHyprlandConfigHardwareAcceleration() {
        val profile = StackProfile.defaultArchXiaomiPad8()
        val config = ConfigSynthesizer.synthesizeHyprlandConfig(profile)

        // 1. Non-destructive sourcing
        assertTrue(config.contains("source = /home/alw/.config/hypr/hyprland.conf"))

        // 2. Display & HiDPI scaling
        assertTrue(config.contains("monitor = WL-1, 3200x2136@144, 0x0, 1.75"))
        assertTrue(config.contains("force_zero_scaling = true"))

        // 3. Tablet and touch inputs for creative pen workflows
        assertTrue(config.contains("tablet {"))
        assertTrue(config.contains("touchdevice {"))
        assertTrue(config.contains("output = WL-1"))

        // 4. Workstation standard keybindings
        assertTrue(config.contains("\$mainMod = SUPER"))
        assertTrue(config.contains("bind = \$mainMod, Return, exec, kitty"))
        assertTrue(config.contains("bind = \$mainMod, Q, killactive,"))
        assertTrue(config.contains("bind = \$mainMod, M, exit,"))

        // 5. Hardware accelerated visuals (Turnip Adreno 825)
        assertTrue(config.contains("enabled = true"))
        assertTrue(config.contains("drop_shadow = true"))

        // 6. Nested Wayland backend environment
        assertTrue(config.contains("env = AQ_BACKEND,wayland"))
        assertTrue(config.contains("env = WLR_BACKENDS,wayland"))

        // 7. PRoot / Android Wayland bridge mitigations
        assertTrue(config.contains("enabled = false")) // xwayland disabled
        assertTrue(config.contains("no_hardware_cursors = true"))
        assertTrue(config.contains("monitor = , 3200x2136@60, 0x0, 1.75"))
        assertTrue(config.contains("windowrulev2 = float, class:.*"))
        assertTrue(config.contains("env = LIBSEAT_BACKEND,noop"))
        assertTrue(config.contains("env = WLR_NO_HARDWARE_CURSORS,1"))
        assertTrue(config.contains("env = MESA_GL_VERSION_OVERRIDE,4.6"))
        assertTrue(config.contains("exec-once = konsole --hold -e /bin/bash -c \"fastfetch; glxinfo -B; exec bash\" &"))
    }

    @Test
    fun testSynthesizeHyprlandConfigSoftwareFallbackVisualDegradation() {
        val profile = StackProfile.defaultArchXiaomiPad8().copy(
            gpu = StackProfile.defaultArchXiaomiPad8().gpu.copy(
                driverMode = StackProfile.DriverMode.LLVMPIPE,
                mesaLoaderOverride = "llvmpipe"
            )
        )
        val config = ConfigSynthesizer.synthesizeHyprlandConfig(profile)

        // Verifies blur and drop shadows are disabled under software fallback (llvmpipe)
        assertTrue(config.contains("Software fallback visual degradation (llvmpipe)"))
        assertTrue(config.contains("rounding = 0"))
        assertTrue(config.contains("blur {\n        enabled = false"))
        assertTrue(config.contains("drop_shadow = false"))
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
