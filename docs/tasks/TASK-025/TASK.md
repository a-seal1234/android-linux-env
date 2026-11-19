# TASK-025 — Desktop Rice Command & Sysfs Redirection Layer

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Host Integration, Guest Compatibility Shims & Fake Sysfs  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `core/session/`, `core/proot/`, `app/`, `docs/tasks/TASK-025/`  
**Risk:** Medium  
**Status:** **Done** — verified on the Xiaomi Pad 8 (`d370854b`): fake BAT0/backlight sysfs, brightnessctl/wpctl/pamixer/nmcli/xdg-open/systemctl/loginctl shims drive real device state, and Waybar runs as a rice status bar on the Hyprland session.  
**Depends on:** TASK-024 (Hyprland 0.57.2 + Konsole renders through the DMA-BUF bridge).  
**Fallback preserved:** LabWC Safe Mode and the TASK-020 shims keep working unchanged.

---

## 1. Problem Statement

Community Hyprland rices (Waybar, AGS/Astal, Quickshell, Eww, SwayNC, sekaipop-style dotfiles)
query desktop services that do not exist inside an unrooted PRoot Arch guest:

| Query | Rice component | Present in guest? |
|---|---|---|
| `/sys/class/power_supply/BAT0/*` | Waybar battery, Astal `Battery` | no (Android exposes only `BatteryManager`) |
| `/sys/class/backlight/*` | Waybar backlight, `brightnessctl` | no |
| `wpctl` / `pamixer` (PipeWire/PulseAudio control) | Waybar pulseaudio, SwayNC | binaries exist, no device/session control |
| `nmcli` | Waybar network, AGS network | binary absent, NetworkManager absent |
| `xdg-open` | rice launchers, notification actions | stub without a host hand-off |
| `systemctl poweroff` / `loginctl` | power menus, lock/session scripts | systemd absent |

The Android host owns every one of these resources. This task adds a small host bridge
that answers them over a Unix domain socket, materialises matching fake `/sys` nodes and
installs guest CLI shims so stock rice code works without modification.

## 2. Architecture

```
┌──────────────────────── guest (Arch Linux ARM, PRoot) ────────────────────────┐
│  Waybar / AGS / Quickshell / eww / swaync                                     │
│    ├─ cat /sys/class/power_supply/BAT0/capacity   ──► fakefs BAT0 files       │
│    ├─ cat /sys/class/backlight/alw_backlight/*    ──► fakefs backlight files   │
│    └─ brightnessctl / wpctl / pamixer / nmcli / xdg-open / systemctl          │
│           │  /usr/local/bin shims (PATH precedence over /usr/bin)             │
└───────────┼──────────────────────────────────────────────────────────────────┘
            │ AF_UNIX SOCK_STREAM, line protocol
            │ $XDG_RUNTIME_DIR/alw-host.sock  (guest) =
            │ <files>/fakefs/tmp/rt/alw-host.sock (host)
┌───────────▼──────────────── Android app ──────────────────────────────────────┐
│  RiceHostBridge                                                               │
│    battery   → BatteryManager (sticky ACTION_BATTERY_CHANGED)                 │
│    brightness→ WindowManager.LayoutParams.screenBrightness (session window)   │
│    volume    → AudioManager.STREAM_MUSIC                                      │
│    network   → ConnectivityManager / WifiManager                              │
│    open      → Intent.ACTION_VIEW                                             │
│  + periodic writer for BAT0/backlight fakefs nodes (battery + brightness)      │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Socket protocol

One request per line, one response block per request (newline-terminated `key=value`
lines), then the server closes the connection.

| Request | Response |
|---|---|
| `battery` | `capacity=<0-100>`, `status=<Charging\|Discharging\|Full\|Not charging\|Unknown>`, `present=1`, `voltage_now=<µV>`, `current_now=<µA>`, `temperature=<tenths °C>` |
| `brightness get` | `brightness=<0-100>` |
| `brightness set <0-100>` | `brightness=<applied>` |
| `volume get` | `volume=<0-100>`, `muted=<0\|1>` |
| `volume set <0-100>` | `volume=<applied>`, `muted=<0\|1>` |
| `volume step <±delta>` | `volume=<applied>`, `muted=<0\|1>` |
| `volume mute toggle` | `volume=<applied>`, `muted=<0\|1>` |
| `network` | `connected=<0\|1>`, `ssid=<name>`, `signal=<0-100>`, `ip=<IPv4>`, `transport=<wifi\|ethernet\|cellular\|none>` |
| `open <uri-or-path>` | `ok=1` (or `error=<reason>`) |

Unknown commands answer `error=unknown-command`; the shims degrade gracefully when the
socket is absent (session not running or host bridge stopped).

### 2.2 Fake sysfs nodes

Materialised under `<files>/fakefs/sys/class/...` and bind-mounted into the guest:

- `/sys/class/power_supply/BAT0/{capacity,status,present,type,voltage_now,current_now,temperature}`
- `/sys/class/backlight/alw_backlight/{brightness,max_brightness,actual_brightness}`

`RiceHostBridge` refreshes both trees every 10 s while the session runs (battery from the
sticky battery intent, backlight from the last applied brightness), so Waybar/Astal read
values that track the real hardware without a socket round-trip.

### 2.3 Guest CLI shims (`/usr/local/bin`, before `/usr/bin` in `PATH`)

| Shim | Supported surface |
|---|---|
| `alw-host-query` | helper: one line to the socket, prints the response (python3) |
| `brightnessctl` | `-m`, `get`, `max`, `set <val>`, `set <n>%`, `set +<n>%`, `set <n>%-`, `-d …` |
| `wpctl` | `get-volume @DEFAULT_AUDIO_SINK@`, `set-volume …`, `set-mute … 0|1|toggle` |
| `pamixer` | `--get-volume`, `--get-mute`, `-i <n>`, `-d <n>`, `-t`, `-u`, `-m`, `--set-volume <n>` |
| `nmcli` | `-t -f active,ssid dev wifi`, `general status`, `-t -f NAME,TYPE,DEVICE connection show --active` |
| `xdg-open` | forwards URLs and local paths to the host |
| `systemctl` | existing rice-compat behaviour + `poweroff`/`reboot`/`suspend`/`is-active` without D-Bus |
| `loginctl` | `list-sessions`, `terminate-session`, `lock-session`, power verbs |

## 3. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | TASK-025 registered with architecture, protocol and AC table | **Done** |
| **AC-02** | `RiceHostBridge` serves `fakefs/tmp/rt/alw-host.sock` while a GUI session is active | **Done** — bound natively and accepted/read/written through JNI helpers; started/stopped with the session. |
| **AC-03** | Fake `/sys/class/power_supply/BAT0/*` readable in the guest and tracks real battery | **Done** — `capacity=10/11`, `status=Charging`, `voltage_now≈3.66V` matched `dumpsys battery`; refreshed every 10 s. |
| **AC-04** | Fake `/sys/class/backlight/alw_backlight/*` readable; `brightnessctl` controls real screen brightness | **Done** — `brightnessctl -m` → `alw_backlight,backlight,50,50%`, `set +10%` → 60% applied to the session window. |
| **AC-05** | `wpctl get-volume @DEFAULT_AUDIO_SINK@` and `pamixer` report/control `STREAM_MUSIC` | **Done** — `Volume: 0.05`, `[MUTED]` state, `pamixer --get-volume`/`-i` track `AudioManager.STREAM_MUSIC`. |
| **AC-06** | `nmcli general status` and `nmcli -t -f active,ssid dev wifi` return parsed data | **Done** — `STATE CONNECTIVITY…` shows `connected full`; SSID is empty without location permission (documented fallback). |
| **AC-07** | `xdg-open https://example.com` opens the host browser | **Done** — Chrome opened `example.com` ([screenshot](evidence/xdg_open_host_browser.png)). |
| **AC-08** | `systemctl poweroff\|reboot\|suspend` and `loginctl terminate-session` exit 0 without D-Bus errors | **Done** — both print an ALW host-managed notice and exit 0. |
| **AC-09** | Unit tests pass; screenshot/trace evidence captured; commit pushed to `origin/main` | **Done** — tests green; evidence in `docs/tasks/TASK-025/evidence/`. |
| **AC-10** | At least one real rice (Waybar modules) runs against the shims | **Done** — Waybar renders a bar (clock, battery, network, pulseaudio) on the Hyprland output; battery module now discovers `BAT0` ([screenshot](evidence/screen_hyprland_waybar_shims.png)). |

## 4. Verification Plan

1. Launch the Hyprland profile and open a Konsole.
2. In the guest: `cat /sys/class/power_supply/BAT0/capacity`, `brightnessctl -m`,
   `brightnessctl set +10%`, `wpctl get-volume @DEFAULT_AUDIO_SINK@`, `pamixer --get-volume`,
   `nmcli general status`, `nmcli -t -f active,ssid dev wifi`, `xdg-open https://example.com`.
3. Start a rice (`waybar` and/or a sekaipop-style dotfiles config) and confirm the
   battery/backlight/audio/network modules render and react.
4. Capture screenshots/logs into `docs/tasks/TASK-025/evidence/`.

## 5. On-device evidence (Xiaomi Pad 8, `d370854b`)

```
== fake sysfs ==
capacity=10        status=Charging        voltage_now=3661000
max_brightness=100 brightness=50
== brightnessctl ==            alw_backlight,backlight,50,50%  ->  set +10%  ->  60%
== wpctl ==                    Volume: 0.00 [MUTED]  ->  5%+  ->  Volume: 0.05
== pamixer ==                  5  ->  -i 5  ->  10
== nmcli ==                    connected  full   (SSID empty without location permission)
== systemctl/loginctl ==       handled by the Android host, exit 0
```

Full log: [guest_shim_verification.log](evidence/guest_shim_verification.log);
Waybar: [waybar_modules.log](evidence/waybar_modules.log);
desktop: [screen_hyprland_waybar_shims.png](evidence/screen_hyprland_waybar_shims.png);
host browser: [xdg_open_host_browser.png](evidence/xdg_open_host_browser.png).

### Implementation notes / deviations

1. **Socket transport.** Android's public API cannot bind an `AF_UNIX` filesystem
   path (`android.net.LocalSocketAddress` is not a `java.net.SocketAddress` in the
   SDK stubs), and `LocalServerSocket(FileDescriptor)` crashes ART inside
   `Linux_accept` on this device. The listening socket is therefore created,
   accepted, read and written by small JNI helpers in `libwayland_bridge`
   (`nativeCreateUnixServer/nativeAccept/nativeReadLine/nativeWriteFd/nativeCloseFd`);
   only plain fds cross the JNI boundary.
2. **View-system safety.** `WindowManager.LayoutParams.screenBrightness` is applied
   from a 64 ms deferred main-thread task; mutating window attributes directly from
   a posted runnable that lands inside a VSync dispatch re-enters the view
   hierarchy and crashes the app's Choreographer.
3. **Waybar backlight module** uses libudev enumeration, and there is no udev daemon
   inside PRoot, so that module is disabled in the demo bar; the `brightnessctl`
   shim (and the fake sysfs tree) cover backlight control.
4. **SSID** requires `ACCESS_FINE_LOCATION` on modern Android; without it `nmcli`
   reports transport/connectivity and an empty SSID instead of failing.
5. **Vendor gralloc import disabled by default.** While profiling this task the
   `AHardwareBuffer_createFromHandle()` fast path was found to crash inside
   `SnapAllocCore::Retain()` on HyperOS when importing a PRoot-emulated dma-buf
   handle (it killed the app mid-session). It is now opt-in via `ALW_DMABUF_AHB=1`;
   the linear `mmap` path renders the buffers, so presentation is unaffected.
6. **Optional session hook**: `/root/alw-session-hook.sh` is executed by the
   synthesized Hyprland Lua config at `hyprland.start` when present (used here for
   diagnostics and to launch the Waybar rice). It is a no-op when absent.

## 5. Risks & Mitigations

- **SSID access**: reading the SSID requires location permission on modern Android. Mitigation:
  fall back to transport/connectivity only (`ssid=`), never fail the command.
- **Brightness**: `screenBrightness` is per-window; it is applied on the session window and only
  while the app is foreground. Mitigation: also update the fake `alw_backlight` nodes so rice
  UIs stay consistent.
- **Socket path length**: keep the socket directly in `$XDG_RUNTIME_DIR` (`/tmp/rt/alw-host.sock`),
  which stays far below `sockaddr_un.sun_path`.
- **Shim shadows a real binary**: only install when the package is absent, or keep the same CLI
  surface; `wpctl`/`xdg-open` forward to the real binary when the host bridge is unavailable.
