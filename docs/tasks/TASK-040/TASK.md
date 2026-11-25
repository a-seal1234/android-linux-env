# TASK-040 — Per-App PRoot Isolation Engine & Crash Cascade Shield

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Subsystem Architecture, Process Isolation & Crash Shield  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `core/session/`, `core/proot/`, `app/`, `docs/tasks/TASK-040/`  
**Risk:** Medium  
**Status:** **In Progress** — Claimed 2026-11-24 following user approval of PLAN-ZERO-COPY-APP-ISOLATION.md.  
**Depends on:** TASK-025 (`alw-host.sock`), TASK-004 (`ProotProcessManager`), TASK-036 (`RiceShimGenerator` wrappers).  
**Verification target:** Headless unit tests + on-device verification on Xiaomi Pad 8 (`192.168.51.161:44021`).  

---

## 1. Problem Statement

On unrooted Android, all guest processes in the current architecture run under a **single monolithic PRoot supervisor instance** using Linux `ptrace`. When complex multi-process applications (such as VS Code/Electron, Chromium, Firefox) spawn worker threads, execute unsupported or corner-case syscalls (`clone3`, `pidfd_open`, `epoll_pwait2`, `prctl(PR_SET_SECCOMP)`), or encounter fatal signals (`SIGSEGV`, `SIGBUS`, `SIGSYS`):
1. The PRoot supervisor process encounters a ptrace synchronization panic or aborts.
2. The entire guest process tree (Hyprland, Waybar, Quickshell, D-Bus, and all client windows) is terminated immediately (**Crash Cascade**).
3. Simple apps (Dolphin, Kitty, Konsole) survive because they are single-process, standard POSIX Qt/terminal applications, but modern productivity suites frequently crash the entire desktop session.

## 2. Architecture

```
[ Android Host Service / RiceHostBridge ]
       │
       ├─► (Master PRoot Instance 1: PID A)
       │         └─► Hyprland Compositor + Waybar + Quickshell + Session D-Bus
       │                   ▲                           ▲
       │                   │ (wayland-0)               │ (wayland-0)
       │                   │                           │
       ├─► (Isolated PRoot Instance 2: PID B)          │
       │         └─► VS Code (code-oss) ───────────────┘
       │
       └─► (Isolated PRoot Instance 3: PID C)
                 └─► Heavy GUI Application (Firefox / Chromium)
```

1. **Host Side (`core:session` / `RiceHostBridge.kt` + `AppProcessLauncher`):**
   - Introduces `APP_SPAWN <cmd> <args...>` verb on `$XDG_RUNTIME_DIR/alw-host.sock`.
   - Host `AppProcessLauncher` receives the command and spawns an independent `ProcessBuilder` executing `proot` targeting the same guest rootfs, binding `/tmp/rt` (`wayland-0`, `dbus`, pulse/pipewire sockets), storage, and `/dev/kgsl-3d0`.
   - The spawned app runs in its own independent `ptrace` domain.

2. **Guest Side (`core:proot` / `RiceShimGenerator.kt` + `/usr/local/bin/alw-app-exec`):**
   - Introduces `/usr/local/bin/alw-app-exec`: connects to `alw-host.sock` and requests `APP_SPAWN`.
   - Fallback: If `alw-host.sock` is unavailable, executes in-process directly.
   - Chromium, Electron, and VS Code wrappers route through `alw-app-exec`.

3. **Crash Shield Isolation:**
   - If the isolated app crashes or triggers ptrace panic, **only that app's PRoot instance terminates**.
   - Hyprland receives a standard Wayland client disconnect event, closes the client window, and keeps the desktop session completely alive.

## 3. Phases

- **Phase 1 — Host IPC & Isolated Launcher Engine (`core/session/`, `core/proot/`):**
  Implement `AppProcessLauncher` interface and `ProotAppLauncher` using `ProotRunner.buildLaunchCommand(profile, sessionRuntimeDir, customExecCommand, customArgs)`.
  Wire `APP_SPAWN` into `RiceHostBridge.kt` and attach launcher in `DashboardViewModel`.

- **Phase 2 — Guest Execution Shim & Wrapper Integration (`core/proot/src/main/java/.../shim/RiceShimGenerator.kt`):**
  Generate `/usr/local/bin/alw-app-exec`.
  Update `generateChromiumWrapper` to invoke via `alw-app-exec` when available.

- **Phase 3 — Unit Tests & Verification:**
  Unit tests covering `RiceHostBridge` `APP_SPAWN` parsing, `ProotAppLauncher` argument generation, and crash shield resilience.
  Full `./gradlew test assembleDebug` verification.

## 4. Acceptance Criteria

| ID | Description | Status |
|---|---|---|
| **AC-01** | `RiceHostBridge` accepts `APP_SPAWN <cmd>` and routes to `AppProcessLauncher` | **Done** (verified via `RiceHostBridgeAppSpawnTest`) |
| **AC-02** | `ProotAppLauncher` builds independent PRoot command preserving Wayland/D-Bus/GPU bindings | **Done** (verified via `ProotAppLauncherTest`) |
| **AC-03** | `/usr/local/bin/alw-app-exec` shim connects to `alw-host.sock` with graceful local fallback | **Done** (verified via `RiceShimGeneratorTest`) |
| **AC-04** | Unit tests pass for `APP_SPAWN` IPC and launcher argument formatting | **Done** (all unit tests green) |
| **AC-05** | `./gradlew test assembleDebug` passes with zero errors | **Done** (build green, [evidence](evidence/verification.md)) |
