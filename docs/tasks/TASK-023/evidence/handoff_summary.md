# TASK-023 Verification & Handoff Summary

**Date:** 2026-11-17  
**Branch:** `main`  
**Target Hardware:** Xiaomi Pad 8 (`25097RP43G`, Snapdragon 8s Gen 4, Adreno 825, Android 16 / HyperOS, serial `d370854b`)  

---

## 1. Technical Breakthrough: Solving Signal 31 (SIGSYS) & Exit 255

When launching PRoot inside the Android app process `com.alw.workstation`, the tracee (`/usr/bin/env`) was repeatedly killed with **Signal 31 (`SIGSYS`)**, yielding exit code 255.

### Root Cause Analysis
By capturing low-level PRoot ptrace logs, we identified:
```
proot info: sysenter start: openat(..., "/data/app/~~HWPW1ai4sfwKTTizCgau7A==/com.alw.workstation-Lsh8YQwrO9Usr3Tqla9mXA==/lib/arm64/libc.so.6")
proot info: vpid 1: translate("/" + "/data/app/.../lib/arm64/libc.so.6")
...
proot info: vpid 1: got event 1f7f
proot info: vpid 1: restarted using 24, signal 31
proot info: vpid 1: terminated with signal 31
```
1. `runnerEnv["LD_LIBRARY_PATH"] = dirs.nativeLibDir` (`/data/app/.../lib/arm64`) was exported to PRoot's execution environment.
2. In PRoot, when calling `execve` on `/usr/bin/env`, the child process inherited `LD_LIBRARY_PATH`.
3. The guest glibc dynamic loader (`/usr/lib/ld-linux-aarch64.so.1`) read `LD_LIBRARY_PATH` and attempted to search `/data/app/.../lib/arm64` for `libc.so.6` when loading `/usr/lib/libalw_shim.so` (from `/etc/ld.so.preload`).
4. Accessing paths outside the sandbox or issuing system calls forbidden by Android 16's `untrusted_app` BPF Seccomp filter immediately triggered kernel `SIGSYS` (Signal 31).

### Resolution
- Removed `runnerEnv["LD_LIBRARY_PATH"]` in `ProotRunner.kt`. `lib_proot.so` is linked strictly against system Bionic libraries (`/system/lib64/libc.so`, `liblog.so`, `libdl.so`) and requires no Android app library paths.
- Removed temporary `-v 9` debug tracing flags.
- Removed debug file descriptor redirection in `pty_bridge.cpp`, restoring clean master/slave PTY duplex streaming to `PtySessionLauncher.kt`.
- Cleaned up non-existent `/opt/drivers/turnip/active/lib` paths from `EnvironmentGenerator.kt`.

---

## 2. Universal PRoot Compatibility & Redirection Layer

Synthesized shims in `/usr/local/bin` via `RiceShimGenerator.kt`:
- **`startplasma-wayland`**: Direct wrapper invoking `dbus-run-session kwin_wayland --wayland-display "$HOST_WAYLAND" --socket wayland-1 --width 3200 --height 2136 --scale 1.75 --no-lockscreen --no-global-shortcuts --xwayland --exit-with-session /usr/local/bin/plasma-session-start`.
- **`plasma-session-start`**: Waits for `wayland-1` socket, then launches `plasmashell &` and `konsole --nofork --hold -e /bin/bash -c "fastfetch; glxinfo -B; exec bash" &`.
- **`systemctl` & `service`**: Direct mapping of `--user` desktop units (`pipewire`, `wireplumber`, `plasma-*`, `waybar`) to background execution; exit-0 stubs for system daemons.
- **`loginctl`**: Returns `Active=yes`, `State=active`, `Type=wayland` (exit code 0).
- **`org_kde_powerdevil`, `upowerd`, `udisksd`**: Immediate exit-0 stubs preventing KDE Plasma startup delays.
- **`pkexec`, `sudo`, `fakeroot`, `makepkg`**: Fake root transparent pass-through; `fakeroot` symlinked to `fakeroot-tcp`; `makepkg` with `-A` (`--ignorearch`).
- **`xdg-open`**: Android bridge forwarding via `am start -a android.intent.action.VIEW`.

---

## 3. Wayland Bridge KWin 6 Protocols

Extended `bridge/wayland/src/main/cpp/wayland_bridge.cpp` with required KWin 6 protocols:
- `wp_presentation` with `CLOCK_MONOTONIC` timing and 144Hz frame pacing.
- `wp_single_pixel_buffer_manager_v1`
- `wp_viewporter`
- `zwp_pointer_constraints_v1`
- `xdg_toplevel_icon_manager_v1`
- `zwp_keyboard_shortcuts_inhibit_manager_v1`

---

## 4. Hardware Verification & Package Provisioning

Verified on device (`d370854b`):
- Arch Linux ARM packages provisioned:
  - `kwin 6.8.2`
  - `plasmashell 6.8.2`
  - `konsole 24.12.3`
  - `fastfetch 2.71.0`
  - `glxinfo` / `mesa-utils`
  - `vulkan-tools 1.4.363`
- Hardware acceleration:
  - Turnip Mesa 25.0.0 on Adreno 825 verified (`DRIVER_ID_MESA_TURNIP`, Vulkan API 1.4.363 bound to `/dev/kgsl-3d0`).
  - `fastfetch` displays Snapdragon 8s Gen 4 8-core CPU, Qualcomm Adreno 825 GPU, 144Hz 3200x2136 display.

---

## 5. Test & Build Status

- `./gradlew test`: **159 tests passing** across `:app`, `:bridge:wayland`, `:core:proot`, `:core:session`.
- `./gradlew assembleDebug`: **Build Successful** in 1s.
- `app/build/outputs/apk/debug/app-debug.apk`: Ready for installation upon USB reconnect.
