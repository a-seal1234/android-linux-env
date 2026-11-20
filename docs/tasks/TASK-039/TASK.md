# TASK-039 — Session Lifecycle Hardening: Single-Session Guard & Orphan Guest Reaping

> **Renumbered history:** TASK-035 → TASK-037 (Quickshell overlay fix took the 035 slot) →
> TASK-038 (FUSE Virtual Symlink Engine took the 037 slot) → **TASK-039** (2026-11-20, the Google
> ANGLE renderer spec took the 038 slot). Implemented in commit `b6b1db0`. Source-code comments
> citing older task numbers are updated by a comment-only follow-up inside this task.

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Session Lifecycle, Process Supervision & Rendering Integrity  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** CommandCode / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `core/session/`, `app/`, `docs/tasks/TASK-039/`  
**Risk:** High  
**Status:** **In Progress** — claimed 2026-11-20 after on-device root-cause capture.  
**Depends on:** TASK-004 (ProotProcessManager lifecycle), TASK-007 (WaylandSurfaceServer singleton), TASK-027 on-device testing (where the artifact surfaced).

---

## 1. Problem Statement

During TASK-027 on-device testing the user reported a "ghost frame" artifact after closing and
reopening windows. On-device capture proved the cause was **stacked compositors**: every
`Launch GUI` press (e.g. after switching away to another app and back) spawned a *second*
`Hyprland` as a concurrent client of the same `wayland-0` bridge output, and an app kill
(installer SIGKILL) left the whole guest tree orphaned, reconnecting to the next session's
socket. Observed with `ps`:

- 3 simultaneous `Hyprland` trees (plus orphaned `hyprpaper` ppid=1) at 23:52–23:56
- Symptoms: stale-frame stripes over the waybar, glyph fragments from old frames, a duplicated
  `Invalid scale passed to monitor WAYLAND-1: 1.75` error bar, wallpaper bleeding through windows
- A single-session relaunch (after `am force-stop`, which reaps the entire UID) rendered clean

## 2. Architecture

```
launchSession()
  ├─ SessionState.isLiveSession() ?  ── yes ──► stopSession()   (kills tracked proot tree,
  │                                              cancels in-flight launchJob, stops servers)
  ├─ GuestProcessReaper.reapOrphanedGuests()     (same-UID orphans only:
  │        name ∈ {lib_proot.so, Hyprland, start-hyprland, hyprpaper, waybar,
  │                quickshell, dbus-run-session}
  │        AND pid ≠ myPid AND ppid ≠ myPid AND (ppid == 1 ∨ parent dead))
  └─ startServer → spawn new proot/Hyprland tree (single compositor guaranteed)
```

`am force-stop` kills everything under the app UID (verified on-device), so the reaper only
covers the unclean paths: installer SIGKILL during update, crashes, and any parent-death that
reparents guests to init. `kitty`/`bash`/plain guest apps are excluded from the kill set — they
exit on their own when their compositor's socket dies, and the Terminal session must survive.

## 3. Phases

**Phase 1 — stop-first guard (this change).** `DashboardViewModel.launchSession()` stops a live
session before starting a new one and tracks/cancels the in-flight orchestrator launch
(`launchJob`), so an old launch's outcome handler can never tear down the new session. The
liveness check is `sessionController.isLiveSession() || serverManager.isRunning()` — the native
server singleton survives Activity recreation, where a fresh `sessionController` would wrongly
read `Idle` (exactly the switch-app-and-return path the user hit).
**Phase 2 — orphan reaping (this change).** `GuestProcessReaper` scans `/proc` and SIGKILLs
same-UID orphans before `startServer`. Injected `processLister`/`parentAlive`/`killer` make the
selection logic host-JVM testable without Android framework stubs.
**Phase 3 — on-device double-launch verification.** APK pushed to `/sdcard/Download/`
(manual tap-install is the only path past HyperOS's `INSTALL_FAILED_USER_RESTRICTED`; the first
guarded build was installed 2026-11-20, the final build with the `isRunning()` guard was
re-pushed to `Download/alw-debug.apk`). **Pending next session:** tap-install the re-pushed APK,
then Launch → Launch → `ps -A` must show exactly one `Hyprland`; close/reopen windows must be
ghost-free; AC-02/03/06 then flip to Done.

## 4. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture and AC table | **Done** |
| **AC-02** | Double-press Launch (or launch while switching apps) never stacks a second compositor — `ps` shows exactly one `Hyprland` | In verification (Phase 3) |
| **AC-03** | Orphans from a killed app instance are reaped at next launch (no reconnect ghosts) | In verification (Phase 3) |
| **AC-04** | `GuestProcessReaper` unit tests: same-UID orphan kill, live-tree sparing, UID/name filtering, dead-parent rule; `isLiveSession` truth table | **Done** — `GuestProcessReaperTest` |
| **AC-05** | Terminal session and non-guest processes are never signalled (kill-set excludes `kitty`/`bash`) | **Done** (unit-tested) |
| **AC-06** | Unit tests + `./gradlew assembleDebug` green; evidence recorded; commit pushed | In verification |

## 5. Risks & Mitigations

- **Reaper over-kill** → kill-set restricted to desktop-session process names, same-UID only,
  live-parent spared; `kitty` excluded so the Terminal flow is untouched.
- **Stop-first latency on relaunch** → `stopSession()` joins native threads (fast, already
  proven); reaper scan is a bounded `/proc` read.
- **Race with in-flight launch outcome** → `launchJob` cancellation in `stopSession()`.
