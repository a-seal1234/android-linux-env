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
export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp/rt}"
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
export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp/rt}"
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

# Nested KWin on the Android Wayland bridge: the KWin Wayland platform requires
# OpenGL compositing (QPainter is unsupported -> "quitting KWin as it is broken").
# The bridge exposes wl_shm only (no zwp_linux_dmabuf_v1), so force Mesa's software
# wl_shm EGL path (llvmpipe). Zink would require dmabuf WSI and crash.
export LIBGL_ALWAYS_SOFTWARE=1
export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
export GALLIUM_DRIVER=llvmpipe
export WLR_RENDERER=pixman
export QT_QUICK_BACKEND=software
# Capture KWin's exact crash point in guest stderr
export QT_LOGGING_RULES="kwin_*.debug=true;qt.qpa.wayland*.debug=true"

exec ${'$'}WRAPPER kwin_wayland --wayland-display "${'$'}HOST_WAYLAND" --socket wayland-1 --width 3200 --height 2136 --scale 1.75 --no-lockscreen --no-global-shortcuts --exit-with-session /usr/local/bin/plasma-session-start
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

case "${'$'}{1:-}" in
    poweroff|reboot|suspend|hibernate|hybrid-sleep|suspend-then-hibernate|kexec|soft-reboot)
        echo "ALW: '${'$'}1' is handled by the Android host (no systemd in PRoot)."
        exit 0
        ;;
    is-system-running)
        echo "running"
        exit 0
        ;;
esac

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
# PRoot Compatibility Shim for loginctl (TASK-025)
case "${'$'}{1:-}" in
    list-sessions)
        echo "1 1000 alw seat0 tty1"
        ;;
    show-session|session-status)
        echo "Id=1"
        echo "User=1000"
        echo "Name=alw"
        echo "Type=wayland"
        echo "Class=user"
        echo "Active=yes"
        echo "State=active"
        ;;
    terminate-session|lock-session|unlock-session|activate|poweroff|reboot|suspend|hibernate|kill-session)
        echo "ALW: '${'$'}1' is managed by the Android host (no systemd-logind in PRoot)."
        ;;
    *)
        echo "Active=yes"
        echo "State=active"
        echo "Type=wayland"
        ;;
esac
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
# PRoot Compatibility Shim for xdg-open (TASK-025: host bridge first)
TARGET="${'$'}1"
if [ -z "${'$'}TARGET" ]; then
    exit 0
fi

if [ -S "${'$'}{XDG_RUNTIME_DIR:-/tmp/rt}/alw-host.sock" ]; then
    RESP="$(alw-host-query "open ${'$'}TARGET" 2>/dev/null)"
    case "${'$'}RESP" in
        ok=1*) exit 0 ;;
    esac
fi

if command -v am >/dev/null 2>&1; then
    am start -a android.intent.action.VIEW -d "${'$'}TARGET" >/dev/null 2>&1 || true
elif [ -x /system/bin/am ]; then
    /system/bin/am start -a android.intent.action.VIEW -d "${'$'}TARGET" >/dev/null 2>&1 || true
fi
exit 0
"""

    const val ALW_GUI_RUN_SHIM = """#!/bin/sh
# ALW: launch a GUI client against the nested compositor's Wayland socket.
# Hyprland does not always propagate WAYLAND_DISPLAY to exec_cmd children, and the
# bridge owns wayland-0 while the compositor takes the next free name; resolve the
# newest compositor socket explicitly before exec.
RUNTIME="${'$'}{XDG_RUNTIME_DIR:-/tmp/rt}"
if [ -z "${'$'}WAYLAND_DISPLAY" ] || [ ! -S "${'$'}RUNTIME/${'$'}WAYLAND_DISPLAY" ]; then
    SOCK="$(ls -t "${'$'}RUNTIME"/wayland-* 2>/dev/null | grep -v '\\.lock${'$'}' | head -1)"
    if [ -n "${'$'}SOCK" ]; then
        WAYLAND_DISPLAY="$(basename "${'$'}SOCK")"
        export WAYLAND_DISPLAY
    fi
fi
exec "${'$'}@"
"""

    const val ALW_HOST_QUERY_SHIM = """#!/bin/sh
# ALW host bridge query helper (TASK-025).
# Usage: alw-host-query "battery" | alw-host-query "volume get" ...
SOCK="${'$'}{XDG_RUNTIME_DIR:-/tmp/rt}/alw-host.sock"
if [ ! -S "${'$'}SOCK" ]; then
    echo "alw-host-query: host bridge unavailable" >&2
    exit 3
fi
exec python3 - "${'$'}SOCK" "${'$'}*" <<'ALWPY'
import socket, sys
path, cmd = sys.argv[1], sys.argv[2]
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(3)
try:
    s.connect(path)
    s.sendall((cmd + "\n").encode())
    out = b""
    while True:
        chunk = s.recv(4096)
        if not chunk:
            break
        out += chunk
    sys.stdout.write(out.decode(errors="replace"))
except OSError as exc:
    sys.stderr.write(f"alw-host-query: {exc}\n")
    sys.exit(3)
ALWPY
"""

    const val BRIGHTNESSCTL_SHIM = """#!/bin/sh
# ALW PRoot shim: brightnessctl -> Android host bridge (TASK-025)
SYS="/sys/class/backlight/alw_backlight"

read_attr() {
    if [ -r "${'$'}SYS/${'$'}1" ]; then
        tr -d '\n' < "${'$'}SYS/${'$'}1"
    else
        alw-host-query "brightness get" 2>/dev/null | sed -n 's/^brightness=//p'
    fi
}

MAX="$(read_attr max_brightness)"
[ -n "${'$'}MAX" ] || MAX=100

MACHINE=0
SKIP_NEXT=0
ARGS=""
for a in "${'$'}@"; do
    if [ "${'$'}SKIP_NEXT" = "1" ]; then SKIP_NEXT=0; continue; fi
    case "${'$'}a" in
        -m|--machine-readable) MACHINE=1 ;;
        -d|--device) SKIP_NEXT=1 ;;
        -q|--quiet|-s|--save|-r|--restore) ;;
        *) ARGS="${'$'}ARGS ${'$'}a" ;;
    esac
done
set -- ${'$'}ARGS

CMD="${'$'}{1:-get}"

print_value() {
    VAL="$(read_attr brightness)"
    [ -n "${'$'}VAL" ] || VAL=0
    PCT=$(( VAL * 100 / MAX ))
    if [ "${'$'}MACHINE" = "1" ]; then
        echo "alw_backlight,backlight,${'$'}VAL,${'$'}PCT%"
    else
        echo "${'$'}VAL"
    fi
}

case "${'$'}CMD" in
    get) print_value ;;
    max) echo "${'$'}MAX" ;;
    info)
        VAL="$(read_attr brightness)"
        echo "Device 'alw_backlight' of class 'backlight':"
        echo "    Current brightness: ${'$'}VAL ($(( VAL * 100 / MAX ))%)"
        echo "    Max brightness: ${'$'}MAX"
        ;;
    set)
        SHIFT="${'$'}2"
        if [ -z "${'$'}SHIFT" ]; then echo "brightnessctl: missing value" >&2; exit 1; fi
        CUR="$(read_attr brightness)"; [ -n "${'$'}CUR" ] || CUR=0
        PCT=$(( CUR * 100 / MAX ))
        NUM=""
        case "${'$'}SHIFT" in
            +*) NUM="${'$'}{SHIFT#+}"; NUM="${'$'}{NUM%%%*}"; TARGET=$(( PCT + NUM )) ;;
            -*%) NUM="${'$'}{SHIFT%\%-}"; NUM="${'$'}{NUM#-}"; TARGET=$(( PCT - NUM )) ;;
            *%) NUM="${'$'}{SHIFT%%%}"; TARGET="${'$'}NUM" ;;
            *) TARGET="${'$'}SHIFT" ;;
        esac
        case "${'$'}TARGET" in
            ''|*[!0-9]*) echo "brightnessctl: invalid value '${'$'}SHIFT'" >&2; exit 1 ;;
        esac
        [ "${'$'}TARGET" -lt 0 ] && TARGET=0
        [ "${'$'}TARGET" -gt 100 ] && TARGET=100
        alw-host-query "brightness set ${'$'}TARGET" > /dev/null 2>&1 || exit 3
        ;;
    *) echo "brightnessctl: unsupported invocation: ${'$'}*" >&2; exit 1 ;;
esac
exit 0
"""

    const val WPCTL_SHIM = """#!/bin/sh
# ALW PRoot shim: wpctl -> Android host bridge (TASK-025)
CMD="${'$'}1"
shift 2>/dev/null

volume_state() {
    RESP="$(alw-host-query "volume get" 2>/dev/null)" || exit 3
    VOL="$(echo "${'$'}RESP" | sed -n 's/^volume=//p')"
    MUTED="$(echo "${'$'}RESP" | sed -n 's/^muted=//p')"
    [ -n "${'$'}VOL" ] || VOL=0
}

case "${'$'}CMD" in
    get-volume)
        volume_state
        CUR=$(awk -v v="${'$'}VOL" 'BEGIN { printf "%.2f", v / 100 }')
        if [ "${'$'}MUTED" = "1" ]; then
            echo "Volume: ${'$'}CUR [MUTED]"
        else
            echo "Volume: ${'$'}CUR"
        fi
        ;;
    set-volume)
        shift
        VALUE="${'$'}1"
        if [ -z "${'$'}VALUE" ]; then echo "wpctl: missing volume" >&2; exit 1; fi
        volume_state
        CUR="${'$'}VOL"
        case "${'$'}VALUE" in
            *%+) STEP="${'$'}{VALUE%%%+}"; NEW=$(( CUR + STEP )) ;;
            *%-) STEP="${'$'}{VALUE%%%-}"; NEW=$(( CUR - STEP )) ;;
            *%) NEW="${'$'}{VALUE%%%}" ;;
            *) NEW=$(awk -v v="${'$'}VALUE" 'BEGIN { printf "%d", v * 100 }') ;;
        esac
        [ "${'$'}NEW" -lt 0 ] && NEW=0
        [ "${'$'}NEW" -gt 100 ] && NEW=100
        alw-host-query "volume set ${'$'}NEW" > /dev/null 2>&1 || exit 3
        ;;
    set-mute)
        shift
        STATE="${'$'}1"
        case "${'$'}STATE" in
            1|on|true) alw-host-query "volume mute 1" > /dev/null 2>&1 || exit 3 ;;
            0|off|false) alw-host-query "volume mute 0" > /dev/null 2>&1 || exit 3 ;;
            toggle) alw-host-query "volume mute toggle" > /dev/null 2>&1 || exit 3 ;;
            *) echo "wpctl: invalid mute state '${'$'}STATE'" >&2; exit 1 ;;
        esac
        ;;
    status|inspect|list|help|--help|-h)
        echo "PipeWire 'wpctl' ALW shim (Android STREAM_MUSIC)"
        ;;
    *)
        echo "wpctl: unsupported command '${'$'}CMD'" >&2
        exit 1
        ;;
esac
exit 0
"""

    const val PAMIXER_SHIM = """#!/bin/sh
# ALW PRoot shim: pamixer -> Android host bridge (TASK-025)
ACTION=""
VALUE=""

read_volume() {
    RESP="$(alw-host-query "volume get" 2>/dev/null)" || exit 3
    VOL="$(echo "${'$'}RESP" | sed -n 's/^volume=//p')"
    MUTED="$(echo "${'$'}RESP" | sed -n 's/^muted=//p')"
    [ -n "${'$'}VOL" ] || VOL=0
}

while [ ${'$'}# -gt 0 ]; do
    case "${'$'}1" in
        --get-volume|-g) read_volume; echo "${'$'}VOL"; exit 0 ;;
        --get-mute) read_volume; [ "${'$'}MUTED" = "1" ] && echo true || echo false; exit 0 ;;
        --set-volume) ACTION="set"; VALUE="${'$'}2"; shift ;;
        -i|--increase) ACTION="inc"; VALUE="${'$'}2"; shift ;;
        -d|--decrease) ACTION="dec"; VALUE="${'$'}2"; shift ;;
        -t|--toggle-mute) ACTION="toggle" ;;
        -m|--mute) ACTION="mute" ;;
        -u|--unmute) ACTION="unmute" ;;
        *) ;;
    esac
    shift
done

case "${'$'}ACTION" in
    set) alw-host-query "volume set ${'$'}VALUE" > /dev/null 2>&1 || exit 3 ;;
    inc) alw-host-query "volume step +${'$'}VALUE" > /dev/null 2>&1 || exit 3 ;;
    dec) alw-host-query "volume step -${'$'}VALUE" > /dev/null 2>&1 || exit 3 ;;
    toggle) alw-host-query "volume mute toggle" > /dev/null 2>&1 || exit 3 ;;
    mute) alw-host-query "volume mute 1" > /dev/null 2>&1 || exit 3 ;;
    unmute) alw-host-query "volume mute 0" > /dev/null 2>&1 || exit 3 ;;
    "") read_volume; echo "${'$'}VOL" ;;
esac
exit 0
"""

    const val NMCLI_SHIM = """#!/bin/sh
# ALW PRoot shim: nmcli -> Android host bridge (TASK-025)
RESP="$(alw-host-query "network" 2>/dev/null)"
CONNECTED="$(echo "${'$'}RESP" | sed -n 's/^connected=//p')"
SSID="$(echo "${'$'}RESP" | sed -n 's/^ssid=//p')"
SIGNAL="$(echo "${'$'}RESP" | sed -n 's/^signal=//p')"
IP="$(echo "${'$'}RESP" | sed -n 's/^ip=//p')"
TRANSPORT="$(echo "${'$'}RESP" | sed -n 's/^transport=//p')"
[ -n "${'$'}TRANSPORT" ] || TRANSPORT="none"
[ -n "${'$'}SIGNAL" ] || SIGNAL=0

TERSE=0
case " ${'$'}* " in
    *" -t "*|*" --terse "*) TERSE=1 ;;
esac

case "${'$'}{1:-}" in
    general)
        if [ "${'$'}2" = "status" ]; then
            if [ "${'$'}CONNECTED" = "1" ]; then STATE="connected"; CONN="full"; else STATE="disconnected"; CONN="none"; fi
            echo "STATE         CONNECTIVITY  WIFI-HW  WIFI     WWAN-HW  WWAN"
            echo "${'$'}STATE  ${'$'}CONN       enabled  enabled  enabled  enabled"
        fi
        exit 0
        ;;
esac

case "${'$'}*" in
    *"dev wifi"*)
        if [ "${'$'}CONNECTED" = "1" ] && [ -n "${'$'}SSID" ]; then
            if [ "${'$'}TERSE" = "1" ]; then echo "yes:${'$'}SSID"; else echo "${'$'}SSID"; fi
        elif [ "${'$'}TERSE" = "1" ]; then
            echo "no:"
        fi
        ;;
    *"connection show"*)
        if [ "${'$'}CONNECTED" = "1" ]; then
            echo "ALW-Host:${'$'}TRANSPORT:wlan0"
        fi
        ;;
    *"device status"*)
        echo "wlan0  wifi  connected"
        ;;
    *)
        echo "ALW nmcli shim (ssid=${'$'}SSID signal=${'$'}SIGNAL ip=${'$'}IP)"
        ;;
esac
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
            "xdg-open" to XDG_OPEN_SHIM,
            "alw-host-query" to ALW_HOST_QUERY_SHIM,
            "alw-gui-run" to ALW_GUI_RUN_SHIM,
            "brightnessctl" to BRIGHTNESSCTL_SHIM,
            "wpctl" to WPCTL_SHIM,
            "pamixer" to PAMIXER_SHIM,
            "nmcli" to NMCLI_SHIM
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
