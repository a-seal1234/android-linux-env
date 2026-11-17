# TASK-023 — Universal PRoot Compatibility Layer, KDE Plasma 6 (Wayland) & Turnip+Zink Pipeline

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md) / [EPIC-ALW-P3 — Phase 3](../../initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Subsystem Integration, Architecture Hardening & On-Device Desktop Validation  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `bridge/wayland/`, `core/proot/`, `core/session/`, `app/`, `docs/tasks/TASK-023/`  
**Risk:** Critical  
**Status:** In Progress / On-Device Re-Verified (2026-11-17; launch pipeline fixed, desktop crash blocker remains)  

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
| **AC-07** | Live nested KDE Plasma 6 + Konsole desktop rendered at 3200x2136 | **Blocked (see AC-08)** | Session bootstraps: bridge↔KWin connected, `plasmashell`/`konsole` spawn; KWin SIGSEGV prevents rendering |
| **AC-08** | In-app Signal 31 / exit 255 root cause & fix (2026-11-17) | **Done** | Zygote `untrusted_app` seccomp traps PRoot `SYSCALL_AVOIDER (x8=-1)` and guest `rseq` before PRoot can rewrite them (`run-as` unaffected, AT_SECURE=0 vs 1). Fixed by rebuilding `lib_proot.so` from proot v5.4.0 with a SIGSYS-suppression patch in `tracee/event.c` (aborted syscalls degrade to `-ENOSYS`), `unsetenv(LD_LIBRARY_PATH/LD_PRELOAD)` in `pty_bridge.cpp`, and `GLIBC_TUNABLES=glibc.pthread.rseq=0` hardening. [Patch](evidence/proot-android16-sigsys.patch) |
| **AC-09** | KWin guest-side SIGSEGV ~8-10s into desktop | **Open** | All KWin instances (vpid status 139), silent, no stderr; occurs with `KWIN_COMPOSE=Q` (software) too; blocks AC-07 screen capture. Suspects: `wayland_bridge.cpp` protocol conformance (`wp_presentation`/dmabuf) or nested Turnip/EGL path. Requires dedicated debug session. |

---

## 3. Resume / Handoff Steps

1. ~~Verify device connection / install APK / trigger session~~ — done 2026-11-17.
2. **Next: debug the KWin guest-side SIGSEGV (AC-09).** Captured facts: KWin connects to the host Wayland bridge and serves `wayland-1`; `plasmashell`/`konsole` spawn; all KWin instances die with SIGSEGV (proot vpid status 139) ~8-10s after start, with zero stderr output, even with `KWIN_COMPOSE=Q` and `QT_QUICK_BACKEND=software`. Debug approach: enable `QT_LOGGING_RULES="*.debug=true"` in `RiceShimGenerator.PLASMA_SESSION_START_SHIM` and re-run (guest stderr is the fastest observation channel); audit `wayland_bridge.cpp` protocol implementations (`wp_presentation` timestamps, `zwp_linux_dmabuf`, shm buffer lifecycle) against KWin 6.8.2 expectations; bisect with `--xwayland` disabled.
3. Once KWin survives: capture `adb exec-out screencap -p > docs/tasks/TASK-023/evidence/screen_kde_plasma_turnip.png`, mark Done, commit.
