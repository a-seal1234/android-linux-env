# EPIC-ALW-P1 — Phase 1 — Core Runtime, Multi-Rootfs & PRoot Engine

**Initiative:** [Android Linux Workstation](../../INITIATIVE.md)  
**Type:** Phase Epic; groups Phase 1 deliverables. It has no workflow status.  
**Central Board:** [TASKS.md](../../../../../TASKS.md) is authoritative for child task status.  
**Risk:** High Risk  
**Source:** [SRS v0.1](../../../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Implement the core multi-module project structure (`app-ui`, `session-core`, `proot-runtime`, `wayland-bridge`), the `StackProfile` configuration engine, PRoot v5.4.0+ execution harness, multi-rootfs directory management (`files/rootfs/<id>`), automated Arch Linux ARM rootfs provisioning script (`pacman`, `xorg-xwayland`, `pipewire`, `yay -A`), and selective storage bind-mounts with build cache redirection.

---

## 2. Contextual SRS Requirements

- REQ-CONF-001, REQ-CONF-002 (`StackProfile` JSON Engine & Non-Destructive Invariant)
- REQ-PROOT-001 to REQ-PROOT-005 (Multi-rootfs, Import/Export, Ptrace, `--symlink2file`)
- REQ-ONBOARD-002, REQ-ONBOARD-003, REQ-ONBOARD-004, REQ-ONBOARD-005 (Auto-provisioning)
- REQ-STORAGE-001, REQ-STORAGE-002, REQ-STORAGE-003 (Storage Bridge & Build Cache Redirection)

---

## 3. Epic Acceptance / Exit Criteria

1. Android multi-module project builds cleanly using Gradle 8.11.1, AGP 8.7.2, NDK r27c.
2. PRoot executes an unprivileged shell inside `files/rootfs/archlinux-default`.
3. Automated provisioning script successfully initializes pacman keys, installs core packages, and sets global Electron/Chromium flags.
4. Storage bridge mounts `/storage/emulated/0` to `/mnt/sdcard` while excluding `/Android/data`.
5. Build caches (`CARGO_TARGET_DIR`, `UV_PROJECT_ENVIRONMENT`, `PNPM_STORE_DIR`) are proven to redirect to internal UFS storage.

---

## 4. Child Tasks

| Child Task | Outcome | Prerequisites |
|---|---|---|
| [TASK-002](../../../../tasks/TASK-002/TASK.md) | Scaffolding, Multi-Module Gradle & Toolchain | TASK-001 |
| [TASK-003](../../../../tasks/TASK-003/TASK.md) | `session-core` State Machine & `StackProfile` Engine | TASK-002 |
| [TASK-004](../../../../tasks/TASK-004/TASK.md) | `proot-runtime` PRoot Harness & Multi-Rootfs Manager | TASK-002 |
| [TASK-005](../../../../tasks/TASK-005/TASK.md) | Arch Linux ARM Automated Provisioning Script | TASK-003, TASK-004 |
| [TASK-006](../../../../tasks/TASK-006/TASK.md) | Selective Storage Bridge & Build Cache Redirection | TASK-004 |
