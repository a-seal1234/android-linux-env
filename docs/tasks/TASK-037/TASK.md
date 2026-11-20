# TASK-037 — FUSE Virtual Soft Symlink Engine (O(1) LUT + Persistent Mini-DB) & Electron / `yay` / Antigravity Desktop Runtime Support

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Guest Filesystem Transparency, Native Syscall Interception & Desktop Application Runtime  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `core/proot/`, `core/proot/src/main/cpp/` (new native PRoot path-translation module), `docs/tasks/TASK-037/`  
**Risk:** Medium  
**Status:** **Ready** — registered 2026-11-20 (slot reassigned from the session-lifecycle task, now [TASK-038](../TASK-038/TASK.md)).  
**Depends on:** TASK-004 (vendored PRoot harness, preload libs), TASK-006 (StorageBridgeManager defines the FUSE-backed guest prefixes), TASK-019 (FakeFsManager owns `/dev/shm`), TASK-020 (makepkg `-A` shim), TASK-021 (base-devel provisioned; `libalw_shim.so` `/etc/ld.so.preload` mechanism), TASK-036 Phase 3 (Firefox `MOZ_*` env — implement once there, reference here).  
**Verification target:** Xiaomi Pad 8, wireless ADB `192.168.51.161:44021`.  
**Fallback preserved:** PRoot's built-in `--link2symlink` (`profile.storage.enableSymlink2File`, ProotRunner step 2) stays the default-safe symlink emulation; the LUT engine is additive and scoped to FUSE-backed prefixes only.

---

## 1. Problem Statement

Android FUSE shared storage (`/sdcard`, `/storage/emulated/0`) rejects `symlink()`/`symlinkat()`
with `EPERM`/`EOPNOTSUPP`. Today ProotRunner passes PRoot's built-in `--link2symlink`
(`profile.storage.enableSymlink2File`), which lets the *creation* succeed by writing `.lnk`
placeholder files — but `readlink`, `lstat` (`S_IFLNK`), and link-aware build tooling still see
a plain file, so real workloads break:

- `yay` / `makepkg` shared-library builds and packaging under `/sdcard`
- `npm` / `node_modules` layouts (thousands of relative symlinks)
- Desktop app archives (AppImage-style trees, Electron bundles) stored on shared storage

Separately, Electron-based desktop apps (Google Antigravity desktop) and Firefox crash inside
PRoot + Hyprland (Turnip/Zink) without explicit sandbox, `/dev/shm`, D-Bus and Wayland Ozone
flags — a crashed guest app can take the compositor session down (recovery UX is TASK-036).

**Repo facts the implementation must respect:** PRoot itself is a vendored prebuilt binary
(`lib_proot.so`, v5.4.0+ with env-gated extensions such as `ALW_ANDROID_DRI`) — its source is
not in this repository, so the syscall interception layer must be a **new native LD_PRELOAD
module** following the proven `libalw_shim.so` `/etc/ld.so.preload` mechanism (TASK-021), not a
modification of the vendored PRoot. The only in-repo native build today is
`bridge/wayland/src/main/cpp/CMakeLists.txt`; the new module adds the first one under
`core/proot/src/main/cpp/`.

## 2. Architecture

```
Guest process (makepkg / npm / Electron)
  └─ libc: symlink/readlink/lstat/unlink/rename …
       └─ libalw_vfs.so (LD_PRELOAD, core/proot/src/main/cpp/)   [native]
            ├─ FUSE prefix guard: path ∈ /sdcard | /storage/emulated/0 (from
            │  StorageBridgeManager mounts, exported to the module at launch)
            ├─ in-memory O(1) hash LUT: normalized_guest_link_path → target_path
            ├─ persistent Mini-DB: append-only binary WAL (default) or SQLite,
            │  in app filesDir (native ext4/f2fs), bind-mounted into the guest
            │  (e.g. /opt/alw/vfs/) by ProotRunner so state survives restarts
            ├─ sentinel stub: 0-byte file created on FUSE so getdents64/ls list it
            └─ resolution loop: iterative translate_path, depth limit 40 → ELOOP

Kotlin side (core/proot): ProotRunner binds the Mini-DB dir, exports prefix list +
DB path via env (ALW_VFS_DB=…, ALW_VFS_PREFIXES=…); RiceShimGenerator adds the
Electron/Firefox runtime wrappers (Phase 2); StorageBridgeManager owns the mount list.
```

**Mini-DB choice:** default to the **append-only binary WAL** (crash-safe, no SQLite dependency
inside the preload module, trivially replayable into the LUT on process start); SQLite remains
the alternative if queryability wins during implementation. One decision record in this file on
landing.

## 3. Phases

**Phase 1 — Hybrid Virtual Symlink Engine (`core/proot/src/main/cpp/libalw_vfs.cpp` + `ProotRunner.kt`).**
New native preload module intercepting, for FUSE-backed prefixes only:
- `symlink` / `symlinkat`: persist `(link → target)` to LUT + Mini-DB and create a **0-byte
  sentinel stub** on FUSE so `getdents64`/`ls` list entries naturally;
- `lstat` / `fstatat(AT_SYMLINK_NOFOLLOW)` / `statx`: report `S_IFLNK | 0777` and
  `st_size = target.length()` for LUT entries (real files fall through untouched);
- `readlink` / `readlinkat`: serve from the LUT in O(1);
- path resolution: iterative `translate_path` with a depth limit of 40 (returns `ELOOP`);
- `unlink` / `unlinkat` / `renameat` / `renameat2`: atomic cleanup/update of stub + LUT +
  Mini-DB (WAL append + in-memory eviction in one critical section).
Kotlin (`ProotRunner`): bind the Mini-DB directory into the guest, export the prefix list and
DB path, and load the module alongside the existing preload chain; the `--link2symlink` toggle
remains available and the LUT engine takes precedence on its prefixes (both may coexist for
non-FUSE emulated cases).

**Phase 2 — Electron & Browser Runtime Shims (`core/proot/.../shim/RiceShimGenerator.kt`, `ProotRunner.kt`).**
Electron/Chromium wrapper defaults (`alw-electron-run`-style shim or an env block in
`EnvironmentGenerator`): `--no-sandbox --disable-setuid-sandbox --disable-dev-shm-usage
--enable-features=UseOzonePlatform --ozone-platform=wayland --disable-gpu-sandbox
--password-store=basic`, plus a GPU-fallback toggle when ANGLE/Zink hits unsupported extensions
(reuse the established llvmpipe fallback pattern). Firefox flags (`MOZ_DISABLE_CONTENT_SANDBOX=1`,
`MOZ_ENABLE_WAYLAND=1`) and the writable `/dev/shm` + active D-Bus session checks are specified
by **TASK-036 Phase 3** — implement once there and reference from this task to avoid divergence;
this task consumes them for verification.

**Phase 3 — `yay`, Firefox & Google Antigravity Desktop verification (`192.168.51.161:44021`).**
Unit/integration tests for the FUSE symlink persistence set (`ln -s`, `readlink`, `lstat`, `rm`,
`mv`, and a `makepkg` symlink creation under `/sdcard` through the intercepted layer; the
Mini-DB reload path across a PRoot restart). On-device: ensure `base-devel` + `git` (TASK-021
already provisions `base-devel`), build/install `yay-bin` (aarch64; `makepkg` `-A` shim from
TASK-020 applies), install Google Antigravity desktop via `yay`, then verify Firefox and
Antigravity desktop launch cleanly inside Hyprland — the rice must survive app crashes
(TASK-036 recovery available). Evidence in `docs/tasks/TASK-037/evidence/`.

## 4. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture, phases and AC table | **Done** |
| **AC-02** | `libalw_vfs.so` intercepts symlink/lstat/readlink/unlink/rename on FUSE prefixes with the O(1) LUT + persistent Mini-DB and 0-byte sentinel stubs; depth-40 resolution returns `ELOOP` | Planned |
| **AC-03** | Symlink state survives a PRoot restart (Mini-DB replay) — unit/integration test proves `ln -s` → restart → `readlink` round-trip | Planned |
| **AC-04** | `makepkg`-driven symlink creation succeeds under `/sdcard` and `ls`/`getdents64` lists the stubs naturally | Planned |
| **AC-05** | Electron/Chromium wrapper defaults exported and an Electron app reaches the Wayland Ozone platform on the Turnip/Zink path (GPU fallback toggle available) | Planned |
| **AC-06** | `yay-bin` builds and installs on aarch64; Google Antigravity desktop installs via `yay`; Firefox and Antigravity launch cleanly in Hyprland on the Pad 8 with evidence | Planned |
| **AC-07** | Unit tests + `./gradlew test assembleDebug` green; evidence in `docs/tasks/TASK-037/evidence/`; commit pushed to `origin/main` | Planned |

## 5. Verification Plan

1. Unit: prefix guard (in/out of scope paths), LUT hit/miss/eviction, WAL replay, ELOOP depth,
   stub stat shape — host-JVM/native test split (native module covered by a device-run test
   harness or a guest-side script; Kotlin bind/env wiring unit-tested in `ProotRunnerTest`).
2. On-device: `ln -s /sdcard/...`, `readlink`, `stat`, `rm`, `mv` round-trips; `npm install` in a
   small package on shared storage; `makepkg` a package with symlinked libs; restart the session
   and re-verify links; `yay -S yay-bin`; launch Firefox and Antigravity desktop; capture
   screenshots + `wev`/compositor logs into `evidence/`.
3. Regression: `--link2symlink`-only profile unchanged; non-FUSE guest paths untouched
   (interceptor falls through outside the prefixes); TASK-020 shims unaffected.

## 6. Risks & Mitigations

- **LD_PRELOAD coverage gaps** (statically linked or `memfd`-loaded binaries bypass libc) →
  scope the guarantee to glibc-linked tooling (`yay`, `npm`, `makepkg`); document any escapes;
  keep `--link2symlink` as the fallback.
- **Mini-DB corruption on SIGKILL** → append-only WAL with length-prefixed records + replay
  validation; corrupt tail truncated on load.
- **Concurrent PRoot processes appending** → WAL appends under a single-writer lock file next to
  the DB (same-host `flock`, pattern already used by the Wayland bridge).
- **Preload ordering with `libalw_shim.so`** → the VFS module must chain after the PTY shim;
  verify `/etc/ld.so.preload` ordering on-device early (Phase 1 spike).
- **Antigravity desktop is a heavy Electron bundle** → Phase 2's `--disable-dev-shm-usage` +
  `/dev/shm` hardening (TASK-019/036) must land before the Phase 3 install attempt.
- **aarch64 AUR availability** → `yay-bin` is aarch64-packaged; if the AUR package breaks,
  fall back to building `yay` from source with the `makepkg -A` shim (TASK-020).
