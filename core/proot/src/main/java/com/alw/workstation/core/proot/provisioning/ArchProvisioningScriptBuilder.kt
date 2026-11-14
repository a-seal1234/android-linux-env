package com.alw.workstation.core.proot.provisioning

/**
 * Configuration parameters for the automated Arch Linux ARM rootfs provisioning sequence.
 */
data class ProvisioningConfig(
    val defaultUser: String = "alw",
    val shell: String = "/bin/bash",
    val configurePacmanKeys: Boolean = true,
    val updateSystem: Boolean = true,
    val installCorePackages: Boolean = true,
    val additionalPackages: List<String> = emptyList(),
    val installAurWrapper: Boolean = true,
    val configureWaylandFlags: Boolean = true,
    val configureChromiumSandboxFlags: Boolean = true
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
        val totalSteps = 6

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

            // Step 2: Sync and update
            appendLine("echo '==> Step 2/$totalSteps: Synchronizing package repositories...'")
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

            appendLine("echo '==> Provisioning complete!'")
        }
    }
}
