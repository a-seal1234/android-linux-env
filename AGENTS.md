# Agent instructions

Read [TASKS.md](TASKS.md) before starting work and keep it updated when claiming, progressing,
blocking, completing, or handing off a task. Read the linked source documents and ADRs before changing
the behavior they describe.

## Repository Boundaries & Standards

- This repository hosts the **Android Linux Workstation** project: a native Android application designed
  to turn an unrooted Android tablet (primary target: Xiaomi Pad 8, Snapdragon 8s Gen 4, Adreno 825, HyperOS)
  into a full Arch Linux ARM (`aarch64`) development workstation with a native Wayland desktop.
- The project follows **Dev Framework v0.2** (defined in [docs/dev-framework/DEV-FRAMEWORK-v0.2.md](docs/dev-framework/DEV-FRAMEWORK-v0.2.md)).
- All user configuration must remain non-destructive via the `StackProfile` JSON model; user dotfiles inside
  the guest rootfs must never be destructively overwritten.
- Decouple the UI, session orchestration, PRoot execution, and Wayland display bridge so alternative
  backends can be substituted without cascading rewrites.

## Workspace & Git Rules

- Preserve existing local changes. Before editing, verify working tree cleanliness via `git status --short --branch`.
- Never execute destructive git commands (`git reset --hard`, `git clean -fd`) without explicit authorization.
- Keep one writer per file in shared working trees. Coordinate file scopes on the task board.

## Shared Task Handoffs

- Claim a task in [TASKS.md](TASKS.md) before implementation. Record the owner, harness, branch, write scope,
  and acceptance criteria.
- Update `docs/tasks/<TASK-ID>/TASK.md` as the authoritative task detail record.
- Record revision-bound evidence in `docs/tasks/<TASK-ID>/evidence/`.
- Mark work **Done** only when all acceptance criteria are met, required gates pass, and evidence is linked.
