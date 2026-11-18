# TASK-023 — Universal PRoot Compatibility Layer, KDE Plasma 6 (Wayland) & Turnip+Zink Pipeline

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md) / [EPIC-ALW-P3 — Phase 3](../../initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Subsystem Integration, Architecture Hardening & On-Device Desktop Validation  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `bridge/wayland/`, `core/proot/`, `core/session/`, `app/`, `docs/tasks/TASK-023/`  
**Risk:** Critical  
**Status:** In Progress / Compositor Gate Identified (2026-11-18): Hyprland and KWin 6.8 both require `zwp_linux_dmabuf_v1` + a DRM render node, which the bridge/sandbox does not provide. SHM-based LabWC + Konsole render verified on-device.

---

## 1. Outcome & Deliverables

1. **Universal PRoot Compatibility & Redirection Layer:**
   - Shims in `/usr/local/bin` (`startplasma-wayland`, `plasma-session-start`, `systemctl`, `service`, `loginctl`, `org_kde_powerdevil`, `upowerd`, `udisksd`, `pkexec`, `sudo`, `fakeroot`, `makepkg`, `xdg-open`).
   - Global environment variables preventing Android 16 Seccomp crashes (`UV_USE_IO_URING=0`, `APPIMAGE_EXTRACT_AND_RUN=1`, `MESA_LOADER_DRIVER_OVERRIDE=zink`, `GALLIUM_DRIVER=zink`, `TU_DEBUG=noconform`).
2. **KWin 6 Required Wayland Bridge Global Protocols:**
   - Monotonic clock & 144Hz frame pacing via `wp_presentation`.
   - `wp_single_pixel_buffer_manager_v1`, `wp_viewporter`, `zwp_pointer_constraints_v1`, `xdg_toplevel_icon_manager_v1`, `zwp_keyboard_shortcuts_inhibit_manager_v1`.
3. **Hardware Acceleration Pipeline (Adreno 825 Turnip + Zink):**
   - Point `VK_ICD_FILENAMES` and `VK_DRIVER_FILES` exclusively to `/opt/drivers/turnip/active/freedreno_icd.aarch64.json` bound to `/dev/kgsl-3d0`.
   - Native OpenGL-on-Vulkan acceleration through Mesa Zink without bogus driver paths.
4. **Subprocess PTY Native Streamline & Signal 31 (SIGSYS) Resolution:**
   - Native fork/exec in `pty_bridge.cpp` using `forkpty`, `prctl(PR_SET_DUMPABLE, 1)`, and `fcntl(masterFd, F_SETFD, flags | FD_CLOEXEC)`.
   - Clean dynamic linker environment isolation: stripped `LD_LIBRARY_PATH` from `runnerEnv` to prevent glibc dynamic loader from traversing Android app native libraries and triggering kernel Seccomp `SIGSYS` stops.
5. **On-Device Package Provisioning & Verification:**
   - Arch Linux ARM rootfs provisioned with `plasma-desktop 6.8.2`, `kwin 6.8.2`, `konsole`, `fastfetch`, `mesa`, `vulkan-tools`, `fakeroot-tcp`.
   - Live execution on Xiaomi Pad 8 display (`screen_kde_plasma_turnip.png`).

---

## 2. Acceptance Criteria & Status

| Criterion | Description | Status | Evidence |
|-----------|-------------|--------|----------|
| **AC-01** | Global Env & Shims synthesized in `/usr/local/bin` | **Done** | [RiceShimGenerator.kt](../../../core/proot/src/main/java/com/alw/workstation/core/proot/shim/RiceShimGenerator.kt), [RiceShimGeneratorTest.kt](../../../core/proot/src/test/java/com/alw/workstation/core/proot/shim/RiceShimGeneratorTest.kt) |
| **AC-02** | Wayland Bridge KWin 6 Protocol support added | **Done** | [wayland_bridge.cpp](../../../bridge/wayland/src/main/cpp/wayland_bridge.cpp) |
| **AC-03** | Resolve Signal 31 / Exit 255 crash on Android 16 Seccomp | **Done** | [ProotRunner.kt](../../../core/proot/src/main/java/com/alw/workstation/core/proot/ProotRunner.kt), [pty_bridge.cpp](../../../bridge/wayland/src/main/cpp/pty_bridge.cpp) |
| **AC-04** | Provision KDE Plasma 6 & Konsole packages in guest rootfs | **Done** | Verified on device (`kwin 6.8.2`, `plasmashell 6.8.2`, `konsole`) |
| **AC-05** | Adreno 825 Turnip KGSL hardware acceleration confirmed | **Done** | `vulkaninfo --summary` and `fastfetch` report Turnip Adreno 825 |
| **AC-06** | Unit tests passing across all modules (`./gradlew test`) | **Done** | 159 tests passing cleanly |
| **AC-07** | Live nested KDE Plasma 6 + Konsole desktop rendered at 3200x2136 | **Blocked (AC-09)** | KWin 6.8 refuses to composite on this bridge — see AC-09 |
| **AC-08** | In-app Signal 31 / exit 255 root cause & fix (2026-11-17) | **Done** | Zygote `untrusted_app` seccomp traps PRoot `SYSCALL_AVOIDER (x8=-1)` and guest `rseq` before PRoot can rewrite them (`run-as` unaffected, AT_SECURE=0 vs 1). Fixed by rebuilding `lib_proot.so` from proot v5.4.0 with a SIGSYS-suppression patch in `tracee/event.c` (aborted syscalls degrade to `-ENOSYS`), `unsetenv(LD_LIBRARY_PATH/LD_PRELOAD)` in `pty_bridge.cpp`, and `GLIBC_TUNABLES=glibc.pthread.rseq=0` hardening. [Patch](evidence/proot-android16-sigsys.patch) |
| **AC-09** | KWin 6.8 compositing gate (root-caused 2026-11-18) | **Blocked (hard)** | `WaylandBackend::supportedCompositors()` (kwin v6.8.2 `src/backends/wayland/wayland_backend.cpp:608`) only offers OpenGL when `m_display->linuxDmabuf() && m_renderDevice`. The bridge exposes no `zwp_linux_dmabuf_v1`, and the sandbox has no DRM render node (`/dev/dri` is absent; only `/dev/kgsl-3d0`), so `GpuManager::compatibleRenderDevice()` finds nothing. KWin logs "Configured compositor not supported by Platform… quitting KWin now as it is broken" and exits 0 (no more SIGSEGV once llvmpipe/EGL replaces zink). Requires implementing linux-dmabuf **and** a DRM render-node/buffer-export shim — major work. |
| **AC-10** | Hyprland/Aquamarine evaluation (2026-11-18) | **Blocked (hard)** | Hyprland 0.57.2 loads the synthesized session config and reaches backend init, then aborts with `CBackend::create() failed!`. Aquamarine v0.16.0 `src/backend/Wayland.cpp` requires `zwp_linux_dmabuf_v1` and `linux_dmabuf feedback mainDevice` → fails identically to KWin. Config mitigations are in place (`LIBSEAT_BACKEND=noop`, no Xwayland, `no_hardware_cursors`, float rule, waybar+konsole autostart, `GALLIUM_DRIVER=zink`, `MESA_GL_VERSION_OVERRIDE=4.6`), and EGL itself works on the bridge (llvmpipe GL 4.6 / ES 3.2 via `eglinfo`). |
| **AC-11** | SHM desktop render verification (2026-11-18) | **Done** | LabWC 0.20.2 (pixman) + Konsole 26.8.3 (Qt software) render end-to-end at 3200x2136 with `fastfetch` showing Arch Linux ARM, 655 packages, Adreno 825. Proves the proot/PTY/bridge/wl_shm pipeline. [Screenshot](evidence/screen_labwc_konsole_shm.png) |

---

## 3. Resume / Handoff Steps

1. ~~Verify device connection / install APK / trigger session~~ — done 2026-11-17.
2. ~~Evaluate Hyprland as primary compositor~~ — done 2026-11-18 (AC-10): blocked by Aquamarine's
   `zwp_linux_dmabuf_v1` requirement; matches KWin's gate in AC-09.
3. ~~KWin 6.8 bisect~~ — done 2026-11-18 (AC-09): `--xwayland` removed, `QT_LOGGING_RULES`
   enabled, optional protocol globals bisected harmless; the blocker is the platform's
   `supportedCompositors()` gate (`linuxDmabuf()` + DRM render device), not malformed events.
4. **Next (major):** implement `zwp_linux_dmabuf_v1` in `wayland_bridge.cpp` **and** a DRM
   render-node / buffer-export shim (AHardwareBuffer import from dma-buf, `linux-dmabuf`
   feedback mainDevice) so KWin/Aquamarine can composite. Alternative: keep the SHM-only
   bridge and adopt LabWC (pixman) as the always-available desktop, documenting GPU
   acceleration as requiring the dmabuf work.
5. Rendered-desktop evidence for the current SHM path: `evidence/screen_labwc_konsole_shm.png`
   (LabWC 0.20.2 + Konsole 26.8.3 + fastfetch, 3200x2136).
