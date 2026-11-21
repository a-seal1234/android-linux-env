# TASK-036 — Session Lifecycle Guard, Duplicate Detection Prompt, Close All Sessions & App Crash Isolation

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Session Lifecycle UX, Crash Recovery & Guest Sandbox Hardening  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `app/`, `core/session/`, `core/proot/`, `bridge/wayland/`, `docs/tasks/TASK-036/`  
**Risk:** Medium  
**Status:** **In Progress** — Headless complete; Code Complete — On-Device Verification Pending (Xiaomi Pad 8 offline overnight).  
**Depends on:** TASK-038 (single-session guard + `GuestProcessReaper` — this task generalises it into user-facing recovery flows), TASK-004 (`ProotProcessManager` two-phase kill), TASK-007 (`WaylandSurfaceServer` singleton + `flock`), TASK-019 (`FakeFsManager` owns `/dev/shm`).  
**Verification target:** Xiaomi Pad 8, wireless ADB `192.168.51.161:44021`.  
**Fallback preserved:** the automatic single-session guard from TASK-038 (stop-first + orphan reaping) stays the silent default; the new dialog/button flows only add explicit control. LabWC Safe Mode and the Terminal session are untouched.

---

## 1. Problem Statement

Launching heavy apps such as Firefox inside the PRoot guest can crash the whole guest session:

| Crash | Trigger | Aftermath |
|---|---|---|
| `SIGSYS` (seccomp sandbox) | Firefox content-sandbox syscalls not permitted under PRoot translation | Compositor/session tree dies mid-flight |
| `SIGBUS` (`/dev/shm` / `memfd`) | Shared-memory allocations hitting the fake `dev/shm` bind (TASK-019) or `memfd_create` translation limits | Guest apps die; sessions leave stale buffers |
| GPU context faults | Turnip+Zink context loss under the virtual DRM node | Hyprland exits, tree partially orphaned |

Every one of these leaves behind **zombie PRoot/compositor processes** and **stale runtime
artifacts** (`wayland-*` sockets, `alw-host.sock`, `.hyprland.lock`), which cascade into crashes
or overlapping sessions on subsequent launches. TASK-038 already reaps orphans and stops live
sessions before a relaunch; this task adds the **explicit recovery UX** (Close All Sessions,
Resume-vs-Fresh prompt) and the **Firefox crash isolation** so the rice survives guest app
crashes.

Naming note: the repo has no `SessionManager` class — session state is `SessionController`
(state machine over `SessionState`, with `SessionState.Idle` as the idle state). All Phase 1/2
references to "SessionManager" map onto `SessionController`.

## 2. Architecture

```
Dashboard / HUD
  ├─ Launch GUI ────────────────► pre-launch probe (Phase 2)
  │       healthy session live?  ──► dialog: [Resume Existing] (re-attach WaylandSurfaceView)
  │                                        [Close & Start Fresh] (closeAllSessions() → boot)
  │       only stale sockets/zombies? ──► silent auto-clean → boot
  ├─ Close All Sessions ────────► closeAllSessions() (Phase 1)
  │        SIGTERM → grace → SIGKILL guest tree (lib_proot.so, Hyprland, quickshell, …)
  │        purge $XDG_RUNTIME_DIR (wayland-*, alw-host.sock, .hyprland.lock)
  │        reset native Wayland bridge listener state (JNI)
  │        SessionController → SessionState.Idle
  └─ guest Firefox crash (Phase 3)
           MOZ_DISABLE_CONTENT_SANDBOX=1, MOZ_ENABLE_WAYLAND=1, writable /dev/shm bind
           → Hyprland rice survives; only the crashing app dies
```

## 3. Phases

**Phase 1 — hard cleanup & `Close All Sessions` (`core/session/`, `core/proot/`, `bridge/wayland/src/main/cpp/wayland_bridge.cpp`, `app/`).**
New `closeAllSessions()` in `core/session` (built on `GuestProcessReaper` + the
`ProotProcessManager` two-phase kill): (a) terminate active PRoot and guest child processes with
`SIGTERM` → grace window → `SIGKILL`; (b) purge stale runtime sockets/locks under
`$XDG_RUNTIME_DIR` (`wayland-*`, `wayland-*.lock`, `alw-host.sock`, `.hyprland.lock`, the
session-scoped runtime dirs); (c) reset the native Wayland bridge listener state (extend the
existing `stop()` path / `flock` release — no new listener duplication); (d) reset
`SessionController` to `SessionState.Idle` and the Dashboard UI state. Expose a
**`Close All Sessions`** button on the Dashboard beside `Launch GUI` and in the in-session HUD
menu. The native change must respect the TASK-026 owner's write scope on `wayland_bridge.cpp` —
coordinate on the board before touching it.

**Phase 2 — pre-launch duplicate detection & prompt (`app/src/main/java/.../MainActivity.kt`, `DashboardViewModel`).**
Before `launchSession()`: probe (1) `SessionController`/`serverManager.isRunning()`, (2) live
guest processes (reaper scan), (3) runtime sockets. Healthy active session → Compose dialog
**`Resume Existing`** (navigate to `SessionSurfaceScreen`, re-attach `WaylandSurfaceView` to the
running server — no relaunch) vs **`Close & Start Fresh`** (`closeAllSessions()` first, then
boot clean). Only dead/stale state → silently auto-clean (reuse TASK-038's stop-first +
reaping) and boot. Remember the choice per session with a "don't ask again" toggle persisted in
preferences.

**Phase 3 — app crash isolation & `/dev/shm` hardening (`core/proot/.../ProotRunner.kt`, `core/proot/.../FakeFsManager.kt`, `core/proot/.../shim/RiceShimGenerator.kt`).**
Verify the writable `/dev/shm` bind-mount produced by `FakeFsManager` survives the Firefox
allocation pattern (including `memfd_create` behaviour under PRoot translation) and widen it if
needed; export crash-prevention defaults in the guest environment / `alw-gui-run` shim:
`MOZ_DISABLE_CONTENT_SANDBOX=1`, `MOZ_ENABLE_WAYLAND=1` (Firefox scope only — do not weaken the
sandbox for other guests). A Firefox `SIGSYS`/`SIGBUS` must kill only Firefox; the Hyprland rice,
Quickshell bar and the bridge must keep presenting.

**Phase 4 — unit tests & on-device verification.**
Unit tests for duplicate detection (healthy/stale/zombie matrices) and stale-socket cleanup
purge list — `./gradlew test assembleDebug`. On-device (`192.168.51.161:44021`): Resume path,
Close & Start Fresh path, `Close All Sessions` after a hard kill (`am force-stop` + orphan
planting), and a full Firefox launch → force-kill Firefox → rice survives. Evidence in
[docs/tasks/TASK-036/evidence/verification.md](evidence/verification.md).

## 4. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture, phases and AC table | **Done** |
| **AC-02** | `closeAllSessions()` terminates the guest tree (SIGTERM→grace→SIGKILL), purges stale sockets/locks, resets the bridge listener and `SessionController` to Idle | **Code Complete — On-Device Verification Pending** |
| **AC-03** | `Close All Sessions` button present on the Dashboard (beside Launch GUI) and in the in-session HUD menu | **Code Complete — On-Device Verification Pending** |
| **AC-04** | Pre-launch probe: healthy session → Resume/Close-&-Start-Fresh dialog; stale-only → silent auto-clean; choice persistable | **Code Complete — On-Device Verification Pending** |
| **AC-05** | Firefox launches without `SIGSYS`/`SIGBUS` tearing down the session (`MOZ_*` env + verified `/dev/shm` bind); killing Firefox leaves the rice alive | **Code Complete — On-Device Verification Pending** |
| **AC-06** | Unit tests (duplicate detection + stale purge) green; on-device verification of all flows on the Pad 8 with evidence; commit pushed to `origin/main` | **Code Complete — On-Device Verification Pending** |

## 5. Verification Plan

1. Unit: detection matrix (`SessionState` × server-running × sockets × live processes) and the
   purge list against a fake runtime dir.
2. On-device: (a) launch → leave session → return → expect Resume dialog; (b) pick both branches
   once each; (c) `am force-stop` mid-session, plant an orphan `hyprpaper`, relaunch → auto-clean;
   (d) `Close All Sessions` from Dashboard and from the HUD; (e) Firefox launch + `kill -9` on
   Firefox; rice must survive (`qs` bar still updates, `alw-host-query fps` still answers).
3. Regression: TASK-038 double-launch guard still holds; Terminal session unaffected by
   `Close All Sessions` only when explicitly invoked (document the choice).

## 6. Risks & Mitigations

- **`wayland_bridge.cpp` write conflict with the TASK-026 owner** → Phase 1 native changes are
  limited to the existing `stop()`/lock-release path; board-coordinate before editing.
- **Aggressive cleanup killing the Terminal session** → `Close All Sessions` is explicit
  (button), and the silent auto-clean path is restricted to the desktop-session process set from
  TASK-038 (`kitty`/`bash` spared).
- **Resume path attaching to a half-dead server** → the probe requires
  `serverManager.isRunning()` AND a live socket; otherwise fall through to auto-clean.
- **Firefox hardening may regress other guests** → env scoped to the Firefox launch
  (`MOZ_*`), not the global guest environment.
