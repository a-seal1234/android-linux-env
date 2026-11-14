package com.alw.workstation.core.proot.provisioning

/**
 * Configuration parameters for the automated Arch Linux ARM rootfs provisioning sequence.
 */
data class ProvisioningConfig(
    val defaultUser: String = "alw",
    val shell: String = "/bin/bash",
    val configurePacmanKeys: Boolean = true,
    val ignoreKernelPackages: Boolean = true,
    val updateSystem: Boolean = true,
    val installCorePackages: Boolean = true,
    val additionalPackages: List<String> = emptyList(),
    val installAurWrapper: Boolean = true,
    val configureWaylandFlags: Boolean = true,
    val configureChromiumSandboxFlags: Boolean = true,
    val configureAudioBridge: Boolean = true
) {
    companion object {
        val defaultCorePackages = listOf(
            "xorg-xwayland",
            "mesa-utils",
            "vulkan-tools",
            "pipewire",
            "pipewire-pulse",
            "wireplumber",
            "hyprland",
            "labwc",
            "kitty",
            "neovim",
            "git",
            "sudo",
            "base-devel"
        )
    }
}

/**
 * Builds the automated rootfs provisioning shell script.
 * Encapsulates pacman keyring initialization, package installations, user creation,
 * AUR makepkg -A wrapper, and global Wayland/Electron sandbox flags.
 */
object ArchProvisioningScriptBuilder {

    fun buildScript(config: ProvisioningConfig): String {
        val allPackages = (ProvisioningConfig.defaultCorePackages + config.additionalPackages).distinct()
        val totalSteps = 7

        return buildString {
            appendLine("#!/bin/bash")
            appendLine("set -eo pipefail")
            appendLine()

            // Step 1: Pacman keyrings
            appendLine("echo '==> Step 1/$totalSteps: Initializing pacman keyrings...'")
            if (config.configurePacmanKeys) {
                appendLine("pacman-key --init")
                appendLine("pacman-key --populate archlinuxarm")
            } else {
                appendLine("echo 'Pacman keyrings skipped by config'")
            }
            appendLine()

            // Step 2: Configure pacman ignores, sync and update
            appendLine("echo '==> Step 2/$totalSteps: Configuring package ignores and synchronizing repositories...'")
            if (config.ignoreKernelPackages) {
                appendLine("# Prevent kernel and initramfs hooks from executing inside PRoot")
                appendLine("if [ -f /etc/pacman.conf ]; then")
                appendLine("    if grep -q '^#IgnorePkg' /etc/pacman.conf; then")
                appendLine("        sed -i 's/^#IgnorePkg.*$/IgnorePkg = linux-aarch64 linux-firmware mkinitcpio/' /etc/pacman.conf")
                appendLine("    elif grep -q '^IgnorePkg' /etc/pacman.conf; then")
                appendLine("        sed -i 's/^IgnorePkg.*$/IgnorePkg = linux-aarch64 linux-firmware mkinitcpio/' /etc/pacman.conf")
                appendLine("    else")
                appendLine("        sed -i '/\\[options\\]/a IgnorePkg = linux-aarch64 linux-firmware mkinitcpio' /etc/pacman.conf")
                appendLine("    fi")
                appendLine("fi")
            }
            if (config.updateSystem) {
                appendLine("pacman -Syu --noconfirm")
            } else {
                appendLine("echo 'System update skipped by config'")
            }
            appendLine()

            // Step 3: Install core workstation packages
            appendLine("echo '==> Step 3/$totalSteps: Installing core workstation packages...'")
            if (config.installCorePackages) {
                val packageListStr = allPackages.joinToString(" ")
                appendLine("pacman -S --noconfirm --needed $packageListStr")
            } else {
                appendLine("echo 'Core package installation skipped by config'")
            }
            appendLine()

            // Step 4: Create default user & wheel sudoers
            appendLine("echo '==> Step 4/$totalSteps: Configuring user account and sudo permissions...'")
            appendLine("if ! id \"${config.defaultUser}\" >/dev/null 2>&1; then")
            appendLine("    useradd -m -s \"${config.shell}\" -G wheel \"${config.defaultUser}\"")
            appendLine("fi")
            appendLine("mkdir -p /etc/sudoers.d")
            appendLine("cat <<'EOF' > /etc/sudoers.d/00-wheel-nopasswd")
            appendLine("%wheel ALL=(ALL:ALL) NOPASSWD: ALL")
            appendLine("EOF")
            appendLine("chmod 0440 /etc/sudoers.d/00-wheel-nopasswd")
            appendLine()

            // Step 5: AUR makepkg -A wrapper
            appendLine("echo '==> Step 5/$totalSteps: Installing native aarch64 makepkg AUR wrapper...'")
            if (config.installAurWrapper) {
                appendLine("mkdir -p /usr/local/bin")
                appendLine("cat <<'EOF' > /usr/local/bin/makepkg-wrapper")
                appendLine("#!/bin/bash")
                appendLine("exec /usr/bin/makepkg --mflags \"-A\" \"$@\"")
                appendLine("EOF")
                appendLine("chmod 0755 /usr/local/bin/makepkg-wrapper")
            } else {
                appendLine("echo 'AUR wrapper skipped by config'")
            }
            appendLine()

            // Step 6: Global Wayland and Electron sandbox flags
            appendLine("echo '==> Step 6/$totalSteps: Configuring global Wayland & Electron sandbox flags...'")
            if (config.configureWaylandFlags) {
                appendLine("cat <<'EOF' >> /etc/environment")
                appendLine("ELECTRON_OZONE_PLATFORM_HINT=wayland")
                appendLine("MOZ_ENABLE_WAYLAND=1")
                appendLine("QT_QPA_PLATFORM=wayland")
                appendLine("XDG_SESSION_TYPE=wayland")
                appendLine("EOF")
            }
            if (config.configureChromiumSandboxFlags) {
                appendLine("mkdir -p /etc")
                appendLine("cat <<'EOF' > /etc/chromium-flags.conf")
                appendLine("--ozone-platform-hint=wayland")
                appendLine("--no-sandbox")
                appendLine("--disable-gpu-sandbox")
                appendLine("EOF")
            }
            appendLine()

            // Step 7: Low-latency PulseAudio and PipeWire client configuration
            appendLine("echo '==> Step 7/$totalSteps: Configuring low-latency PulseAudio & PipeWire bridge...'")
            if (config.configureAudioBridge) {
                appendLine("mkdir -p /etc/pulse")
                appendLine("cat <<'EOF' > /etc/pulse/client.conf")
                appendLine("default-server = unix:/tmp/runtime-alw/pulse/native")
                appendLine("autospawn = no")
                appendLine("daemon-binary = /bin/true")
                appendLine("enable-shm = false")
                appendLine("EOF")
                appendLine("mkdir -p /run/user/1000")
                appendLine("ln -sfn /tmp/runtime-alw/pulse /run/user/1000/pulse")
                appendLine("cat <<'EOF' >> /etc/environment")
                appendLine("PULSE_SERVER=unix:/run/user/1000/pulse/native unix:/tmp/runtime-alw/pulse/native")
                appendLine("PIPEWIRE_RUNTIME_DIR=/tmp/runtime-alw")
                appendLine("EOF")
            } else {
                appendLine("echo 'Audio bridge configuration skipped by config'")
            }
            appendLine()

            appendLine("echo '==> Provisioning complete!'")
        }
    }
}
