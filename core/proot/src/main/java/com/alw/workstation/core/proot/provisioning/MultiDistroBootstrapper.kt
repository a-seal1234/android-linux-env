package com.alw.workstation.core.proot.provisioning

import android.util.Log
import com.alw.workstation.core.proot.oci.DistroFamily
import java.io.File

/**
 * Detects Linux distributions from /etc/os-release and generates tailored, zero-terminal
 * bootstrap scripts across Arch, Debian, Ubuntu, Kali, Fedora, Alpine, and openSUSE.
 */
class MultiDistroBootstrapper {

    companion object {
        private const val TAG = "ALW::MultiDistroBootstrapper"
    }

    data class OsReleaseInfo(
        val id: String,
        val idLike: List<String>,
        val name: String,
        val versionId: String,
        val family: DistroFamily
    )

    /**
     * Inspects /etc/os-release (or /usr/lib/os-release) inside [rootfsDir].
     */
    fun detectDistribution(rootfsDir: File): OsReleaseInfo {
        val osReleaseFile = listOf(
            File(rootfsDir, "etc/os-release"),
            File(rootfsDir, "usr/lib/os-release")
        ).firstOrNull { it.exists() }

        if (osReleaseFile == null) {
            Log.w(TAG, "No os-release found in ${rootfsDir.absolutePath}, falling back to UNKNOWN")
            return OsReleaseInfo(
                id = "unknown",
                idLike = emptyList(),
                name = "Linux Generic",
                versionId = "",
                family = DistroFamily.UNKNOWN
            )
        }

        val props = mutableMapOf<String, String>()
        osReleaseFile.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains('=')) {
                val key = trimmed.substringBefore('=').trim()
                val value = trimmed.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
                props[key] = value
            }
        }

        val id = props["ID"]?.lowercase() ?: "unknown"
        val idLike = props["ID_LIKE"]?.lowercase()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        val name = props["NAME"] ?: props["PRETTY_NAME"] ?: "Linux"
        val versionId = props["VERSION_ID"] ?: ""

        val family = when {
            id == "arch" || id == "archarm" || idLike.contains("arch") -> DistroFamily.ARCH
            id in listOf("debian", "ubuntu", "kali", "raspbian", "pop", "mint") ||
                idLike.any { it in listOf("debian", "ubuntu") } -> DistroFamily.DEBIAN
            id in listOf("fedora", "rhel", "centos", "rocky", "alma") ||
                idLike.contains("fedora") -> DistroFamily.FEDORA
            id == "alpine" -> DistroFamily.ALPINE
            id in listOf("opensuse", "opensuse-tumbleweed", "opensuse-leap", "sles") ||
                idLike.contains("suse") -> DistroFamily.OPENSUSE
            else -> DistroFamily.UNKNOWN
        }

        return OsReleaseInfo(
            id = id,
            idLike = idLike,
            name = name,
            versionId = versionId,
            family = family
        )
    }

    /**
     * Generates a complete bash bootstrap script tailored to the detected distribution.
     */
    fun buildBootstrapScript(
        rootfsDir: File,
        username: String = "workstation",
        installDesktopPackages: Boolean = true
    ): String {
        val distro = detectDistribution(rootfsDir)
        return buildString {
            appendLine("#!/bin/sh")
            appendLine("set -e")
            appendLine("echo '==> ALW Multi-Distro Bootstrap: detected ${distro.name} (${distro.family})'")

            // 1. Core Network & Environment configuration
            appendLine("# 1. Environment and DNS resolution")
            appendLine("mkdir -p /etc")
            appendLine("cat << 'EOF' > /etc/resolv.conf")
            appendLine("nameserver 1.1.1.1")
            appendLine("nameserver 8.8.8.8")
            appendLine("EOF")

            appendLine("cat << 'EOF' > /etc/hosts")
            appendLine("127.0.0.1 localhost")
            appendLine("127.0.1.1 workstation")
            appendLine("::1       localhost ip6-localhost ip6-loopback")
            appendLine("EOF")

            appendLine("cat << 'EOF' > /etc/environment")
            appendLine("PULSE_SERVER=unix:/run/user/1000/pulse/native")
            appendLine("WAYLAND_DISPLAY=wayland-0")
            appendLine("XDG_RUNTIME_DIR=/run/user/1000")
            appendLine("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            appendLine("EOF")

            // 2. Distro-specific package manager and user setup
            appendLine()
            appendLine("# 2. Package installation and user setup")
            when (distro.family) {
                DistroFamily.ARCH -> {
                    appendLine("pacman-key --init 2>/dev/null || true")
                    appendLine("pacman-key --populate archlinuxarm 2>/dev/null || true")
                    appendLine("pacman -Sy --noconfirm --needed sudo bash ca-certificates curl")
                    if (installDesktopPackages) {
                        appendLine("pacman -S --noconfirm --needed wayland xorg-xwayland kitty 2>/dev/null || true")
                    }
                    appendLine("id -u $username >/dev/null 2>&1 || useradd -m -s /bin/bash -G wheel $username")
                }
                DistroFamily.DEBIAN -> {
                    appendLine("export DEBIAN_FRONTEND=noninteractive")
                    appendLine("apt-get update -y")
                    appendLine("apt-get install -y --no-install-recommends sudo bash ca-certificates curl")
                    if (installDesktopPackages) {
                        appendLine("apt-get install -y --no-install-recommends libwayland-client0 xwayland kitty 2>/dev/null || true")
                    }
                    appendLine("id -u $username >/dev/null 2>&1 || useradd -m -s /bin/bash -G sudo $username")
                }
                DistroFamily.FEDORA -> {
                    appendLine("dnf makecache -y")
                    appendLine("dnf install -y sudo bash ca-certificates curl")
                    if (installDesktopPackages) {
                        appendLine("dnf install -y wayland-utils xorg-x11-server-Xwayland kitty 2>/dev/null || true")
                    }
                    appendLine("id -u $username >/dev/null 2>&1 || useradd -m -s /bin/bash -G wheel $username")
                }
                DistroFamily.ALPINE -> {
                    appendLine("apk update")
                    appendLine("apk add sudo bash ca-certificates curl shadow 2>/dev/null || apk add sudo bash ca-certificates curl")
                    if (installDesktopPackages) {
                        appendLine("apk add wayland xwayland kitty 2>/dev/null || true")
                    }
                    appendLine("id -u $username >/dev/null 2>&1 || useradd -m -s /bin/bash -G wheel $username 2>/dev/null || adduser -s /bin/bash -D -G wheel $username")
                }
                DistroFamily.OPENSUSE -> {
                    appendLine("zypper --non-interactive refresh")
                    appendLine("zypper --non-interactive install -y sudo bash ca-certificates curl")
                    if (installDesktopPackages) {
                        appendLine("zypper --non-interactive install -y wayland-utils xwayland kitty 2>/dev/null || true")
                    }
                    appendLine("id -u $username >/dev/null 2>&1 || useradd -m -s /bin/bash -G wheel $username")
                }
                DistroFamily.UNKNOWN -> {
                    appendLine("echo 'Unknown distro family, attempting generic useradd'")
                    appendLine("id -u $username >/dev/null 2>&1 || useradd -m -s /bin/sh $username 2>/dev/null || adduser -D $username 2>/dev/null || true")
                }
            }

            // 3. Passwordless sudoers config
            appendLine()
            appendLine("# 3. Sudoers passwordless configuration")
            appendLine("mkdir -p /etc/sudoers.d")
            appendLine("echo '$username ALL=(ALL:ALL) NOPASSWD: ALL' > /etc/sudoers.d/010_alw")
            appendLine("chmod 0440 /etc/sudoers.d/010_alw 2>/dev/null || true")

            // 4. Runtime user directory
            appendLine("mkdir -p /run/user/1000/pulse")
            appendLine("chown -R $username /run/user/1000 2>/dev/null || true")
            appendLine("chmod 700 /run/user/1000 2>/dev/null || true")

            appendLine("echo '==> ALW Multi-Distro Bootstrap Complete!'")
        }
    }
}
