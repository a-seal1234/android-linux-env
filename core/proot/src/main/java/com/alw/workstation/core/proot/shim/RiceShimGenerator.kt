package com.alw.workstation.core.proot.shim

import java.io.File

/**
 * Generates PRoot compatibility shims in /usr/local/bin (preceding /usr/bin in PATH).
 * Ensures third-party Arch Linux / Hyprland rice installers (such as end-4/dots-hyprland,
 * sekaipop, ML4W) run smoothly without failing on systemd, SysV IPC, architecture checks, or PAM.
 */
object RiceShimGenerator {

    const val PLASMA_SESSION_START_SHIM = """#!/bin/bash
# KDE Plasma Session Starter for Nested KWin Wayland
echo "Starting plasma session at ${'$'}(date)"
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:${'$'}PATH
export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp/runtime-alw}"
export WAYLAND_DISPLAY=wayland-1
export XDG_CURRENT_DESKTOP=KDE
export DESKTOP_SESSION=plasma
export QT_QPA_PLATFORM=wayland
export GDK_BACKEND=wayland
export MESA_LOADER_DRIVER_OVERRIDE="${'$'}{MESA_LOADER_DRIVER_OVERRIDE:-zink}"
export GALLIUM_DRIVER="${'$'}{GALLIUM_DRIVER:-zink}"

for i in ${'$'}(seq 1 50); do
    if [ -S "${'$'}XDG_RUNTIME_DIR/wayland-1" ]; then
        break
    fi
    sleep 0.1
done

plasmashell &
echo "plasmashell started pid=${'$'}!"
konsole --nofork --hold -e /bin/bash -c "fastfetch; glxinfo -B 2>&1 | head -n 20; exec bash" &
echo "konsole started pid=${'$'}!"
wait
"""

    const val STARTPLASMA_WAYLAND_SHIM = """#!/bin/bash
# PRoot Compatibility Shim for startplasma-wayland
echo "=== startplasma-wayland started at ${'$'}(date) ==="
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:${'$'}PATH
export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp/runtime-alw}"
mkdir -p "${'$'}XDG_RUNTIME_DIR"
chmod 0700 "${'$'}XDG_RUNTIME_DIR" 2>/dev/null || true
HOST_WAYLAND="${'$'}{WAYLAND_DISPLAY:-wayland-0}"
export WAYLAND_DISPLAY="${'$'}HOST_WAYLAND"
echo "XDG_RUNTIME_DIR=${'$'}XDG_RUNTIME_DIR, WAYLAND_DISPLAY=${'$'}WAYLAND_DISPLAY"
ls -la "${'$'}XDG_RUNTIME_DIR" || true

WRAPPER=""
if [ -z "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
    WRAPPER="dbus-run-session"
fi

# Nested KWin on the Android Wayland bridge: force the QPainter scene to avoid
# GL renderer crashes (SIGSEGV) in the nested Turnip/Zink path.
export KWIN_COMPOSE=Q
export QT_QUICK_BACKEND=software
export LIBGL_ALWAYS_SOFTWARE=1

exec ${'$'}WRAPPER kwin_wayland --wayland-display "${'$'}HOST_WAYLAND" --socket wayland-1 --width 3200 --height 2136 --scale 1.75 --no-lockscreen --no-global-shortcuts --xwayland --exit-with-session /usr/local/bin/plasma-session-start
"""

    const val SYSTEMCTL_SHIM = """#!/bin/bash
# PRoot Rice Compatibility Shim for systemctl
set -e

GUI_DAEMONS="waybar swaync mako swww-daemon pipewire wireplumber ags plasma-plasmashell plasma-xdg-desktop-portal-kde"
HARDWARE_DAEMONS="NetworkManager bluetooth ufw powerdevil upower udisks2"

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

is_hardware_daemon() {
    local target="${'$'}1"
    target="${'$'}{target%.service}"
    for d in ${'$'}HARDWARE_DAEMONS; do
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

for unit in "${'$'}{units[@]}"; do
    base_unit="${'$'}{unit%.service}"
    if is_hardware_daemon "${'$'}base_unit"; then
        exit 0
    fi
    if [ "${'$'}is_user" -eq 1 ] && is_gui_daemon "${'$'}base_unit"; then
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

exit 0
"""

    const val SERVICE_SHIM = """#!/bin/bash
# PRoot Compatibility Shim for service
exec /usr/local/bin/systemctl "${'$'}@"
"""

    const val LOGINCTL_SHIM = """#!/bin/bash
# PRoot Compatibility Shim for loginctl
echo "Active=yes"
echo "State=active"
echo "Type=wayland"
exit 0
"""

    const val EXIT_ZERO_STUB = """#!/bin/bash
# Fast exit-0 stub for missing hardware daemon
exit 0
"""

    const val PKEXEC_SHIM = """#!/bin/bash
# PRoot Compatibility Shim for pkexec
while [ ${'$'}# -gt 0 ]; do
    case "${'$'}1" in
        --user|--disable-internal-agent)
            shift 2
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

    const val XDG_OPEN_SHIM = """#!/bin/bash
# PRoot Compatibility Shim for xdg-open forwarding to Android
TARGET="${'$'}1"
if [ -z "${'$'}TARGET" ]; then
    exit 0
fi

if command -v am >/dev/null 2>&1; then
    am start -a android.intent.action.VIEW -d "${'$'}TARGET" >/dev/null 2>&1 || true
elif [ -x /system/bin/am ]; then
    /system/bin/am start -a android.intent.action.VIEW -d "${'$'}TARGET" >/dev/null 2>&1 || true
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
            "startplasma-wayland" to STARTPLASMA_WAYLAND_SHIM,
            "plasma-session-start" to PLASMA_SESSION_START_SHIM,
            "systemctl" to SYSTEMCTL_SHIM,
            "service" to SERVICE_SHIM,
            "loginctl" to LOGINCTL_SHIM,
            "org_kde_powerdevil" to EXIT_ZERO_STUB,
            "upowerd" to EXIT_ZERO_STUB,
            "udisksd" to EXIT_ZERO_STUB,
            "pkexec" to PKEXEC_SHIM,
            "fakeroot" to FAKEROOT_SHIM,
            "makepkg" to MAKEPKG_SHIM,
            "sudo" to SUDO_SHIM,
            "xdg-open" to XDG_OPEN_SHIM
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
