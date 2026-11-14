package com.alw.workstation.core.proot.provisioning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchProvisioningScriptBuilderTest {

    @Test
    fun testDefaultScriptContainsAllSteps() {
        val config = ProvisioningConfig(
            defaultUser = "developer",
            shell = "/bin/zsh"
        )
        val script = ArchProvisioningScriptBuilder.buildScript(config)

        // Boilerplate
        assertTrue(script.startsWith("#!/bin/bash\nset -eo pipefail"))

        // Step 1: Pacman keyrings
        assertTrue(script.contains("pacman-key --init"))
        assertTrue(script.contains("pacman-key --populate archlinuxarm"))

        // Step 2: System sync
        assertTrue(script.contains("pacman -Syu --noconfirm"))

        // Step 3: Core packages
        assertTrue(script.contains("pacman -S --noconfirm --needed"))
        assertTrue(script.contains("xorg-xwayland"))
        assertTrue(script.contains("pipewire"))
        assertTrue(script.contains("wireplumber"))
        assertTrue(script.contains("hyprland"))
        assertTrue(script.contains("labwc"))
        assertTrue(script.contains("kitty"))
        assertTrue(script.contains("base-devel"))

        // Step 4: User creation & sudoers
        assertTrue(script.contains("useradd -m -s \"/bin/zsh\" -G wheel \"developer\""))
        assertTrue(script.contains("%wheel ALL=(ALL:ALL) NOPASSWD: ALL"))
        assertTrue(script.contains("00-wheel-nopasswd"))

        // Step 5: AUR makepkg wrapper
        assertTrue(script.contains("/usr/local/bin/makepkg-wrapper"))
        assertTrue(script.contains("exec /usr/bin/makepkg --mflags \"-A\" \"\$@\""))

        // Step 6: Global Wayland and Electron sandbox flags
        assertTrue(script.contains("ELECTRON_OZONE_PLATFORM_HINT=wayland"))
        assertTrue(script.contains("MOZ_ENABLE_WAYLAND=1"))
        assertTrue(script.contains("QT_QPA_PLATFORM=wayland"))
        assertTrue(script.contains("/etc/chromium-flags.conf"))
        assertTrue(script.contains("--no-sandbox"))
        assertTrue(script.contains("--disable-gpu-sandbox"))
    }

    @Test
    fun testAdditionalPackagesIncluded() {
        val config = ProvisioningConfig(
            additionalPackages = listOf("tmux", "htop", "rust")
        )
        val script = ArchProvisioningScriptBuilder.buildScript(config)

        assertTrue(script.contains("tmux"))
        assertTrue(script.contains("htop"))
        assertTrue(script.contains("rust"))
    }

    @Test
    fun testSkippedOptions() {
        val config = ProvisioningConfig(
            configurePacmanKeys = false,
            updateSystem = false,
            installCorePackages = false,
            installAurWrapper = false,
            configureWaylandFlags = false,
            configureChromiumSandboxFlags = false
        )
        val script = ArchProvisioningScriptBuilder.buildScript(config)

        assertFalse(script.contains("pacman-key --init"))
        assertFalse(script.contains("pacman -Syu"))
        assertFalse(script.contains("pacman -S --noconfirm --needed"))
        assertFalse(script.contains("makepkg --mflags"))
        assertFalse(script.contains("ELECTRON_OZONE_PLATFORM_HINT=wayland"))
        assertFalse(script.contains("/etc/chromium-flags.conf"))
    }
}
