# TASK-020: Verification Evidence

## 1. Scope & Objective
- **Objective**: Implement a native POSIX pseudoterminal (PTY) engine and interactive Terminal Console UI in Jetpack Compose to give the user immediate, full-featured interactive Linux shell access (`/bin/bash`) inside the PRoot Arch Linux container (Phase A).
- **Modules Involved**: `bridge:wayland`, `app`.

## 2. Implementations
1. **Native POSIX PTY Engine (`pty_bridge.cpp`)**:
   - Uses `<pty.h>` (`openpty`, `forkpty`) and `<sys/ioctl.h>` (`TIOCSWINSZ`).
   - `nativeCreatePty`: Allocates master/slave pseudoterminal pair.
   - `nativeForkSubprocess`: Forks and connects child process to slave PTY, configures working directory, environment variables, and executes `/system/bin/linker64` with PRoot arguments.
   - Compiled and packaged into `libwayland_bridge.so` (arm64-v8a).

2. **Kotlin PTY Engine & Stream Management**:
   - `PtyBridge.kt`: JNI interface with host JVM fallbacks.
   - `PtySession.kt`: Manages active PTY session with coroutine stream pump, `SharedFlow<String>` output buffer, thread-safe input writes (`write(String)`, `write(ByteArray)`), and window resize.

3. **Jetpack Compose Terminal Console UI**:
   - `TerminalScreen.kt`:
     - Monospace dark-mode terminal buffer (`FontFamily.Monospace`) with auto-scroll to bottom.
     - Full physical keyboard listener via `Modifier.onKeyEvent`: maps `Enter` (`\r`), `Backspace` (`0x7f`), `Tab` (`\t`), `Escape` (`\x1b`), arrow keys (`\x1b[A..D`), and `Ctrl+C`, `Ctrl+D`, `Ctrl+Z`, `Ctrl+L`.
     - Tablet virtual keys toolbar: `ESC`, `TAB`, `CTRL`, `ALT`, `↑`, `↓`, `←`, `→`, `|`, `/`, `-`, `~`, `Ctrl+C`, `Ctrl+D`.
     - Unprovisioned rootfs prompt with one-tap extraction action.
   - `TerminalViewModel.kt`: Manages rootfs validation, PRoot command construction, process lifecycle, and output buffering.

4. **Dashboard & Navigation Integration**:
   - Added `>_ Terminal` button on Dashboard top bar and `>_ Shell` on Active Stack Profile card.
   - Wired `AppScreen.TERMINAL` into `MainActivity` navigation.

## 3. Test Results

### `bridge:wayland` Unit Tests
- `PtyBridgeTest`:
  - `testCreatePtyFallback`: Passed.
  - `testSetWindowSize`: Passed.
  - `testForkSubprocessFallback`: Passed.
  - `testPtySessionStreamPumping`: Passed.

### `app` Unit Tests
- `TerminalViewModelTest`:
  - `testInitialStateUnprovisioned`: Passed.
  - `testStartSessionWhenValid`: Passed (verified `PTY attached`, input write, and session stop).
  - `testCtrlKeyEncoding`: Passed (verified Ctrl state latch and encoding).

### Build Verification
- `./gradlew test`: 100% passed across all modules.
- `./gradlew assembleDebug`: Clean build completed (exit code 0).

### Physical Device Verification (Xiaomi Pad 8)
- Verified direct PRoot execution on Snapdragon 8s Gen 4:
  ```
  Linux localhost 6.6.118-android15-8-ge56cf6b09cca-ab15511674-4k #1 SMP PREEMPT Thu May 28 07:48:46 UTC 2026 aarch64 aarch64 aarch64 GNU/Linux
  PRETTY_NAME="Ubuntu 22.04 LTS"
  NAME="Ubuntu"
  VERSION_ID="22.04"
  VERSION="22.04 (Jammy Jellyfish)"
  VERSION_CODENAME=jammy
  root
  ```
