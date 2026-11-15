package com.alw.workstation.core.proot.shim

import java.io.File

/**
 * Generates PRoot compatibility shims in /usr/local/bin (preceding /usr/bin in PATH).
 * Ensures third-party Arch Linux / Hyprland rice installers (such as end-4/dots-hyprland,
 * sekaipop, ML4W) run smoothly without failing on systemd, SysV IPC, architecture checks, or PAM.
 */
object RiceShimGenerator {

    const val SYSTEMCTL_SHIM = """#!/bin/bash
# PRoot Rice Compatibility Shim for systemctl
set -e

GUI_DAEMONS="waybar swaync mako swww-daemon pipewire wireplumber ags"

is_gui_daemon() {
    local target="${'$'}1"
    target="${'$'}{target%.service}"
    for d in ${'$'}GUI_DAEMONS; do
        if [ "${'$'}d" = "${'$'}target" ]; then
            return 0
        fi
    done
    return 1
}

register_hyprland_autostart() {
    local daemon="${'$'}1"
    local target_home="${'$'}{HOME:-/home/alw}"
    if [ -z "${'$'}target_home" ] || [[ "${'$'}target_home" == /data/* ]]; then
        if [ -d "/home/alw" ]; then
            target_home="/home/alw"
        else
            target_home="/root"
        fi
    fi
    local config_dir="${'$'}target_home/.config/hypr"
    local autostart_conf="${'$'}config_dir/autostart.conf"
    mkdir -p "${'$'}config_dir"
    touch "${'$'}autostart_conf"
    if ! grep -q "exec-once.*=.*${'$'}daemon" "${'$'}autostart_conf"; then
        echo "exec-once = ${'$'}daemon" >> "${'$'}autostart_conf"
    fi
    if [ -n "${'$'}WAYLAND_DISPLAY" ]; then
        if ! pgrep -x "${'$'}daemon" >/dev/null 2>&1; then
            nohup "${'$'}daemon" >/dev/null 2>&1 &
        fi
    fi
}

is_user=0
action=""
units=()

while [ ${'$'}# -gt 0 ]; do
    case "${'$'}1" in
        --user)
            is_user=1
            shift
            ;;
        start|enable|restart|status|is-active|is-enabled)
            action="${'$'}1"
            shift
            ;;
        --now)
            shift
            ;;
        daemon-reload)
            exit 0
            ;;
        -*)
            shift
            ;;
        *)
            units+=("${'$'}1")
            shift
            ;;
    esac
done

if [ "${'$'}is_user" -eq 1 ]; then
    for unit in "${'$'}{units[@]}"; do
        base_unit="${'$'}{unit%.service}"
        if is_gui_daemon "${'$'}base_unit"; then
            case "${'$'}action" in
                enable|start|restart)
                    register_hyprland_autostart "${'$'}base_unit"
                    ;;
                is-active|is-enabled|status)
                    exit 0
                    ;;
            esac
        fi
    done
fi

exit 0
"""

    const val FAKEROOT_SHIM = """#!/bin/bash
# PRoot Rice Compatibility Shim for fakeroot -> fakeroot-tcp
if command -v fakeroot-tcp >/dev/null 2>&1; then
    exec fakeroot-tcp "${'$'}@"
elif [ -x /usr/bin/fakeroot-tcp ]; then
    exec /usr/bin/fakeroot-tcp "${'$'}@"
elif [ -x /usr/bin/fakeroot ]; then
    exec /usr/bin/fakeroot "${'$'}@"
else
    exec "${'$'}@"
fi
"""

    const val MAKEPKG_SHIM = """#!/bin/bash
# PRoot Rice Compatibility Shim for makepkg injecting -A
REAL_MAKEPKG="/usr/bin/makepkg"
if [ ! -x "${'$'}REAL_MAKEPKG" ]; then
    REAL_MAKEPKG="${'$'}(which -a makepkg 2>/dev/null | grep -v '^/usr/local/bin/makepkg' | head -n 1)"
fi

has_ignorearch=0
for arg in "${'$'}@"; do
    if [ "${'$'}arg" = "-A" ] || [ "${'$'}arg" = "--ignorearch" ]; then
        has_ignorearch=1
        break
    fi
done

if [ "${'$'}has_ignorearch" -eq 1 ]; then
    exec "${'$'}REAL_MAKEPKG" "${'$'}@"
else
    exec "${'$'}REAL_MAKEPKG" -A "${'$'}@"
fi
"""

    const val SUDO_SHIM = """#!/bin/bash
# PRoot Rice Compatibility Shim for sudo
# In unrooted PRoot (-0), executes arguments directly without PAM authentication.
while [ ${'$'}# -gt 0 ]; do
    case "${'$'}1" in
        -u|-g|-C|-D|-h|-p|-r|-t|-T|-U)
            shift 2
            ;;
        -E|-b|-e|-H|-k|-K|-n|-S|-s|-v|--)
            shift 1
            ;;
        -*)
            shift 1
            ;;
        *)
            break
            ;;
    esac
done

if [ ${'$'}# -eq 0 ]; then
    exit 0
fi

exec "${'$'}@"
"""

    /**
     * Materializes all shim scripts into the specified directory (e.g. rootfs /usr/local/bin).
     */
    fun installShims(targetDir: File) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val shims = mapOf(
            "systemctl" to SYSTEMCTL_SHIM,
            "fakeroot" to FAKEROOT_SHIM,
            "makepkg" to MAKEPKG_SHIM,
            "sudo" to SUDO_SHIM
        )

        for ((name, content) in shims) {
            val file = File(targetDir, name)
            file.writeText(content)
            try {
                file.setReadable(true, false)
                file.setExecutable(true, false)
            } catch (_: Throwable) {}
        }
    }
}
