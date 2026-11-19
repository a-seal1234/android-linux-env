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

## 4b. Host CachyOS Lua import + Sekai Pop rice (follow-up)

The host's native Hyprland 0.57 Lua tree (`/home/seal/.config/hypr/`) is imported
verbatim into `/home/alw/.config/hypr/` and `/root/.config/hypr/` inside the guest
rootfs (`hyprland.lua`, `config/*.lua`, `hyprland/*.lua`). Host-only NVIDIA env
lines in `config/environment.lua` (`GBM_BACKEND`, `__GLX_VENDOR_LIBRARY_NAME`,
`LIBVA_DRIVER_NAME`, `__GL_GSYNC_ALLOWED`) are commented out in the guest copy,
because a `nvidia-drm` GBM backend breaks the kms_swrast EGL init.

`/tmp/android-session.lua` now (in order):

1. prepends `/home/alw/.config/hypr/?.lua;/home/alw/.config/hypr/?/init.lua;/root/.config/hypr/?.lua;`
   to `package.path` so the modular `require("config.*")` calls resolve;
2. `pcall(dofile, "/home/alw/.config/hypr/hyprland.lua")` (falling back to
   `keybinds.lua`) so a host-specific helper failure never aborts startup;
3. `pcall(dofile, ".../sekaipop-theme.lua")` for the rice aesthetics;
4. **re-applies** `xwayland.enabled = false`, `cursor.no_hardware_cursors = 1`,
   `WAYLAND_DISPLAY`/`AQ_BACKEND`/`WLR_BACKENDS`/`LIBSEAT_BACKEND` env and the
   `hl.monitor({ output = "", mode = "preferred", position = "auto", scale })`
   rule **after** the user config, plus the essential SUPER binds.

Packages installed in the guest (Arch Linux ARM): `swaybg`, `hyprpaper`, `mako`,
`jq`, `ttf-jetbrains-mono-nerd`, `papirus-icon-theme` (Waybar, wofi, kitty,
konsole were already present). `matugen` is AUR-only, so the Sekai Pop palette is
applied statically from `sekaipop/services/PaletteService.qml`
(`#555473`, `#6e6d94`, `#04feee`, `#00ffcb`, `#d9fff6`).

Deployed rice assets (both `/home/alw` and `/root`): Sekai Pop wallpaper
(`~/.config/hypr/assets/sekaipop-wallpaper.png`), Waybar
(`~/.config/waybar/sekaipop/{config.jsonc,style.css}`), the theme layer
(`~/.config/hypr/sekaipop-theme.lua`), `hyprpaper.conf`, GTK 3/4 settings
(Adwaita-dark + Papirus-Dark + JetBrainsMono Nerd Font), Qt5/Qt6 ct, wofi and
mako styling. `hl.on("hyprland.start")` starts hyprpaper (swaybg/swww fallbacks)
and Waybar.

**PRoot compatibility:** the Waybar layout deliberately has no
`hyprland/workspaces` module (Hyprland's IPC socket path exceeds the 108-byte
`sun_path` limit under PRoot). The active modules are `clock`, `battery` (fake
`BAT0`), `pulseaudio` (`wpctl` shim), `network` (`nmcli` shim) and `backlight`
(the shim's `brightnessctl` path; Waybar's own backlight module needs libudev).

| AC | Criterion | Status |
|----|-----------|--------|
| **AC-11** | Host CachyOS Lua tree imported into both guest homes, modular `require()` resolving | **Done** |
| **AC-12** | User entry loaded via `pcall(dofile, ...)` before the Android/PRoot overrides | **Done** — no Lua errors in the compositor log |
| **AC-13** | Rice packages installed in the guest | **Done** — swaybg, hyprpaper, mako, jq, nerd font, Papirus |
| **AC-14** | Sekai Pop wallpaper/Waybar/theme/GTK/Qt/wofi/mako assets deployed | **Done** |
| **AC-15** | PRoot-safe Waybar (no `hyprland/workspaces`) using the TASK-025 shims + wallpaper/bar autostart | **Done** |
| **AC-16** | Rice renders on-device; screenshot captured | **Done** — [screen_hyprland_sekaipop_rice.png](evidence/screen_hyprland_sekaipop_rice.png) |

### Rice limitations

1. **swaybg cannot decode images under PRoot**: its gdk-pixbuf/glycin loader
   spawns `bwrap --unshare-all`, which PRoot forbids (`Failed to load image`).
   `hyprpaper` 0.8 is used instead (block syntax `wallpaper { monitor/path }`),
   verified painting the wallpaper ([hyprpaper_wallpaper.log](evidence/hyprpaper_wallpaper.log)).
2. **Sekai Pop ships a Quickshell shell** (`qs -c end4-pC`). Quickshell is not
   installed in the guest, so the desktop is realized as a Waybar-based
   adaptation of the same palette/visuals; the shell's keybinds from the host
   config still load (D-Bus-dependent helpers are no-ops).
3. `hyprland/workspaces` stays disabled for the `sun_path` reason above.

## 4c. Pointer latency, Quickshell shell and Kitty terminal

**Pointer latency**

- `WaylandSurfaceView.onGenericMotionEvent` now calls `requestUnbufferedDispatch(event)`
  for mouse/stylus `ACTION_MOVE`/`ACTION_HOVER_MOVE`, so Android stops batching
  pointer samples to the next VSync.
- The pacing dead-man timer now flushes at the live refresh interval
  (`max(1, 1000/hz)`) instead of `2 x interval`, halving worst-case callback
  latency when host VSync callbacks pause.
- The bridge caches the client cursor sprite (`wl_pointer.set_cursor` +
  the cursor surface commit) for both **wl_shm** and **dma-buf** buffers,
  keeps a clean shadow copy of the last frame, and composites the sprite at the
  latest pointer position with dirty-rect restores — no full 3200x2136 re-blit.

**Finding — hardware cursors are not exported by this backend.** With
`cursor:no_hardware_cursors = 0`, Aquamarine's nested Wayland backend calls
`wl_pointer.set_cursor(serial, NULL, ...)` (verified in logcat:
`wl_pointer.set_cursor: surface=0`) and does not composite a cursor of its own,
so **no cursor is visible at all**. `= 1` (compositor-composited software cursor)
renders correctly. The session therefore keeps `no_hardware_cursors = 1`, and the
overlay path stays in place for backends that do hand over a sprite. Because the
compositor redraws its own cursor, `WLR_NO_HARDWARE_CURSORS` is no longer forced
by ALW.

**Quickshell**

- `quickshell` 0.3.2 installed from `extra` (aarch64) in the guest rootfs.
- Sekai Pop's shell (`sekaipop/`) deployed to `~/.config/quickshell/sekaipop`
  for both `/home/alw` and `/root`; started as `qs -c sekaipop` (Waybar remains
  the fallback when `qs` is missing).
- New `alw-gui-run` shim resolves the compositor's Wayland socket explicitly
  (`ls -t $XDG_RUNTIME_DIR/wayland-* | grep -v .lock`) before `exec`, because
  `hl.exec_cmd` children sometimes inherited an empty `WAYLAND_DISPLAY`
  (`Gtk-WARNING: cannot open display:`); it is used for kitty, hyprpaper,
  Quickshell and the Waybar fallback.
- `matugen` is not installed (AUR-only): the shell logs a hint and falls back to
  its built-in palette. No `hyprctl` IPC is required for the dock/bar to render
  (the shell degrades gracefully when the socket is missing).

**Kitty**

- `~/.config/kitty/kitty.conf` for both homes with the Sekai Pop palette:
  `background #2a293d`, `background_opacity 0.88`, `foreground #d9fff6`,
  `cursor #04feee`, `selection_background #555473`, accents `#04feee`/`#00ffcb`,
  `font_family JetBrainsMono Nerd Font`, `font_size 12.0`,
  `window_padding_width 14`.
- `hl.on("hyprland.start")` launches
  `alw-gui-run kitty --hold -e /bin/bash -c "fastfetch; exec bash"` and the
  `^(kitty)$` window rule floats/centers it at 62% x 62%.

| AC | Criterion | Status |
|----|-----------|--------|
| **AC-17** | Unbuffered pointer dispatch + 1x refresh pacing | **Done** |
| **AC-18** | Overlay cursor compositor (shm + dmabuf sprite, dirty-rect restore) | **Done** — implemented; dormant because Aquamarine never exports a sprite |
| **AC-19** | Quickshell installed and the Sekai Pop shell/dock runs | **Done** — bar, dock, clock widget and welcome tour render |
| **AC-20** | Kitty is the default terminal, themed with the Sekai Pop palette | **Done** |

## 4d. GPU path, FPS/scale telemetry and Ctrl passthrough

**Turnip / Zink**

- The Turnip ICD at `/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json` is exported
  for the whole session. Verified in the guest:
  `vulkaninfo` → `deviceName = Adreno (TM) 825`, `driverName = turnip Mesa driver`;
  `eglinfo -B` → `OpenGL renderer: zink Vulkan 1.4(Adreno (TM) 825 (MESA_TURNIP))`,
  `OpenGL 4.6 (Core Profile) Mesa 27.0.0`.
- **The compositor cannot run on zink here.** With
  `MESA_LOADER_DRIVER_OVERRIDE=zink` Hyprland never produces a frame
  (`GBM platform: eglInitialize failed`, zero blits at the bridge, black screen).
  The session therefore keeps `kms_swrast` for the compositor and
  `alw-gui-run` exports Turnip + Zink (`MESA_LOADER_DRIVER_OVERRIDE=zink`,
  `GALLIUM_DRIVER=zink`, `LIBGL_ALWAYS_SOFTWARE=0`, `TU_DEBUG=noconform`,
  `MESA_VK_WSI_PRESENT_MODE=immediate`) for every guest application.
- `android_dri.c` already reports `DRM_CAP_PRIME=3`, an explicit
  `DRM_CAP_SYNCOBJ=0`, and `-ENOTTY` for unknown DRM/MSM ioctls.

**Presentation pipeline**

- `blitBufferToWindow` now has an EGL/GLES3 presenter (Adreno 825, textured
  fullscreen quad, `GL_BGRA_EXT` upload, `GL_EXT_unpack_subimage`-equivalent
  padded-stride support via ES3 `GL_UNPACK_ROW_LENGTH`). It initializes and swaps
  successfully (`GLES blit #1: 2400x1602`), but the composed frame does not reach
  the SurfaceView yet, so it is **opt-in** (`ALW_GLES_BLIT=1`) and the CPU blit
  remains the default. `ANativeWindow_lock` is never mixed with an EGL-owned
  window; a failed swap drops the frame instead of corrupting the window.

**Live FPS**

- The bridge counts completed blits and samples them once per second
  (`m_actualFps`), exposed as `nativeGetActualFps()` and through the host socket:
  `alw-host-query fps` → `fps=3 res=2400x1602 scale=0.75 gpu=zink-adreno825`.
- A dedicated Quickshell pill (`qs -c alw-fps`) polls it every second and renders
  `󰓅 <N> FPS <scale>x` in the bottom-right overlay
  (`assets/quickshell-alw-fps/shell.qml`). Idle numbers are damage-driven: with a
  static desktop and a software compositor only 1-5 blits/s occur.

**Dynamic render scale**

- `renderScale` (default **0.75**, clamped 0.35-1.0) is applied atomically; the
  window geometry becomes `align2(width*scale) x align2(height*scale)` and every
  client receives an updated `wl_output.mode`, `xdg_toplevel.configure` and
  `xdg_surface.configure`, so Hyprland resizes without restarting.
  Verified live: `Render scale 0.85 -> output 2720x1816`, then back to
  `0.75 -> 2400x1602` ([render_scale.log](evidence/render_scale.log)).
- Host commands: `scale get` / `scale set <0.35-1.0>`; Android HUD cycler pill
  (`0.50x → 0.75x → 0.85x → 1.00x`).
- Caveat: Hyprland validates the monitor scale on every mode change; rapid
  repeated changes log `Invalid scale passed to monitor WAYLAND-1: 1.75`, so the
  desktop can need a reload after several consecutive switches.

**Ctrl passthrough**

- `InputDispatcher` forwards `KeyEvent.metaState`; the bridge merges it with the
  tracked modifier keys and only emits `wl_keyboard.modifiers` when the mask
  changes (`META_SHIFT_ON`, `META_CTRL_ON`, `META_ALT_ON`, `META_META_ON`).
  `KEYCODE_CTRL_LEFT/RIGHT` map to evdev 29/97.
- Verified on-device: typing in Kitty then `KEYCODE_CTRL_LEFT + X` / `+ C` produces
  `^X^C` in the shell ([ctrl_passthrough_cat_v.png](evidence/ctrl_passthrough_cat_v.png)).
- Android HUD gained a sticky **CTRL** pill (injects KEY_LEFTCTRL + `META_CTRL_ON`)
  next to the render-scale pill.

| AC | Criterion | Status |
|----|-----------|--------|
| **AC-21** | Turnip/Zink verified; apps render on the Adreno 825 | **Done** — `vulkaninfo`/`eglinfo` on-device; `alw-gui-run` exports the Zink path |
| **AC-22** | GPU presentation path (EGL/GLES on ANativeWindow) | **Implemented, opt-in** — initializes/swaps on Adreno 825; frame content not yet on screen, so CPU blit stays default |
| **AC-23** | Live FPS counter + `fps` host command + Quickshell pill | **Done** — pill polls every second |
| **AC-24** | Dynamic render scale 0.35-1.0 with live reconfigure + HUD cycler + `scale` command | **Done** — verified 0.85 → 2720x1816 → 0.75 → 2400x1602 |
| **AC-25** | Ctrl passthrough for terminals (physical + touch pill) | **Done** — `^X^C` verified |

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
Quickshell: [quickshell_shell.log](evidence/quickshell_shell.log);
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
