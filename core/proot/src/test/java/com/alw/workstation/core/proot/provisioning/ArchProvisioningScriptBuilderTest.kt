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

        // DNS & Pacman 7 Sandbox configuration
        assertTrue(script.contains("nameserver 1.1.1.1"))
        assertTrue(script.contains("DisableSandbox"))

        // Step 1: Pacman keyrings
        assertTrue(script.contains("pacman-key --init"))
        assertTrue(script.contains("pacman-key --populate archlinuxarm"))

        // Step 2: System sync and package ignores
        assertTrue(script.contains("IgnorePkg = linux-aarch64 linux-firmware mkinitcpio"))
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
        assertTrue(script.contains("fastfetch"))
        assertTrue(script.contains("fakeroot-tcp"))

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

        // Step 7: Low-latency PulseAudio / PipeWire audio bridge
        assertTrue(script.contains("/etc/pulse/client.conf"))
        assertTrue(script.contains("default-server = unix:/tmp/rt/pulse/native"))
        assertTrue(script.contains("enable-shm = false"))
        assertTrue(script.contains("ln -sfn /tmp/rt/pulse /run/user/1000/pulse"))
        assertTrue(script.contains("PULSE_SERVER=unix:/run/user/1000/pulse/native unix:/tmp/rt/pulse/native"))
        assertTrue(script.contains("PIPEWIRE_RUNTIME_DIR=/tmp/rt"))

        // Step 8: Android PTY & Pacman root access compatibility shim
        assertTrue(script.contains("==> Step 8/8: Materializing Android PTY & Pacman access shim..."))
        assertTrue(script.contains("/usr/lib/libalw_shim.so"))
        assertTrue(script.contains("/etc/ld.so.preload"))
        assertTrue(script.contains("ttyname_r"))
        assertTrue(script.contains("libalw_shim.so"))
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
            ignoreKernelPackages = false,
            updateSystem = false,
            installCorePackages = false,
            installAurWrapper = false,
            configureWaylandFlags = false,
            configureChromiumSandboxFlags = false,
            configureAudioBridge = false
        )
        val script = ArchProvisioningScriptBuilder.buildScript(config)

        assertFalse(script.contains("pacman-key --init"))
        assertFalse(script.contains("IgnorePkg = linux-aarch64"))
        assertFalse(script.contains("pacman -Syu"))
        assertFalse(script.contains("pacman -S --noconfirm --needed"))
        assertFalse(script.contains("makepkg --mflags"))
        assertFalse(script.contains("ELECTRON_OZONE_PLATFORM_HINT=wayland"))
        assertFalse(script.contains("/etc/chromium-flags.conf"))
        assertFalse(script.contains("/etc/pulse/client.conf"))
        assertFalse(script.contains("enable-shm = false"))
    }
}
