# TASK-020 — Native PTY Subprocess Engine & Interactive Terminal Console UI

**Epic:** [EPIC-ALW-P3 — Phase 3](../../initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Usability Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** High Risk  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Implement a native POSIX pseudoterminal (PTY) engine (`openpty` / `forkpty`) and an interactive, hardware-keyboard enabled Terminal Console UI in Jetpack Compose, enabling direct, full-featured interactive shell (`/bin/bash`) access inside the PRoot Arch Linux container before or alongside graphical Wayland compositors.

---

## 2. Source Requirements

- **Interactive Shell Usability**: Provide an immediate interactive console inside the guest rootfs with full TTY capabilities (ncurses, job control, line editing, `pacman`, `nano`, `htop`).
- **Dev Framework v0.2**: Maintain non-destructive configuration and clear separation of PTY lifecycle and UI layers.

---

## 3. Planned Scope & Acceptance Criteria

1. **Native PTY Bridge (`bridge:wayland` / C++20):**
   - Implement `pty_bridge.cpp` using `<pty.h>` (`openpty`, `ioctl(TIOCSWINSZ)`).
   - Expose JNI methods: `nativeCreatePty(cols, rows)`, `nativeSetWindowSize(masterFd, cols, rows)`, `nativeCloseFd(fd)`.
   - Support spawning child processes attached to the slave PTY or plumbing stdin/stdout/stderr file descriptors to PRoot.

2. **PTY Session Manager in Kotlin (`PtySession`):**
   - Provide non-blocking bidirectional IO pumps:
     - Outbound: PTY master output stream -> shared flow of terminal output text.
     - Inbound: user input (characters, keystrokes, escape sequences) -> PTY master input stream.
   - Support window resizing on configuration/orientation changes.

3. **Jetpack Compose Terminal Console UI (`TerminalScreen.kt`):**
   - Fullscreen monospace terminal view with ANSI color support, auto-scroll, and copy/paste buffer.
   - Virtual terminal modifier bar: `ESC`, `TAB`, `CTRL`, `ALT`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `ENTER`.
   - Hardware keyboard listener routing standard and modifier keys (`Ctrl+C`, `Ctrl+D`, `Ctrl+Z`, `Ctrl+L`) to PTY input.

4. **Rootfs Provisioning & Terminal Launch Integration:**
   - Add "Open Terminal" / "Launch Shell" directly on the Dashboard.
   - If rootfs is not extracted, automate extraction of the Arch Linux ARM rootfs archive (or imported tarball) before launching.
   - Launch PRoot with `/bin/bash` attached to the PTY.

5. **Testing & Hardware Verification:**
   - Unit tests covering PTY lifecycle, state flow, and command builder.
   - On-device test on Xiaomi Pad 8: launch interactive `/bin/bash`, verify interactive commands (`uname -a`, `whoami`, `ls -la /`).

---

## 4. Dependencies & Gates

- **Prerequisites:** TASK-004, TASK-012, TASK-014.
- **Status:** Done.
- **Evidence:** [Verification Evidence](evidence/verification.md)
