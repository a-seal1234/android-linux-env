# TASK-005 — Arch Linux ARM Automated Provisioning Script

**Epic:** [EPIC-ALW-P1 — Phase 1](../../initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Automated Rootfs Provisioning  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** Standard (Automated rootfs setup, pacman key initialization, global flags)  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md) (REQ-ONBOARD-002, REQ-ONBOARD-003, REQ-ONBOARD-004, REQ-ONBOARD-005)

---

## 1. Outcome

Implement the automated, zero-terminal Arch Linux ARM provisioning subsystem in `core:proot`:
1. **Provisioning Script Generator (`ArchProvisioningScriptBuilder`):**
   - Step 1: Pacman keyring initialization (`pacman-key --init`, `pacman-key --populate archlinuxarm`).
   - Step 2: Mirrorlist configuration & repository synchronization (`pacman -Syu --noconfirm`).
   - Step 3: Workstation core package installation (`xorg-xwayland`, `mesa-utils`, `vulkan-tools`, `pipewire`, `pipewire-pulse`, `wireplumber`, `hyprland`, `labwc`, `kitty`, `neovim`, `git`, `sudo`, `base-devel`).
   - Step 4: Non-root user setup (`useradd -m -s <shell> <user>`, passwordless `sudo` wheel group configuration).
   - Step 5: AUR helper `makepkg` wrapper (`/usr/local/bin/makepkg-wrapper` invoking `makepkg --mflags "-A"` so x86_64-tagged PKGBUILDs compile natively on aarch64).
   - Step 6: Global Wayland and Sandbox flags:
     - `/etc/environment` (`ELECTRON_OZONE_PLATFORM_HINT=wayland`, `MOZ_ENABLE_WAYLAND=1`, `QT_QPA_PLATFORM=wayland`, `XDG_SESSION_TYPE=wayland`).
     - `/etc/chromium-flags.conf` (`--ozone-platform-hint=wayland`, `--no-sandbox`, `--disable-gpu-sandbox`).
2. **Provisioning Orchestration Engine (`ArchProvisioner`):**
   - Synthesizes the provisioning script into an ephemeral script file.
   - Executes the script inside the target rootfs container via `ProotRunner` and `ProotProcessManager`.
   - Parses stdout progress in real time (reporting current step, percentage, and log messages to listeners).
   - Marks the rootfs as provisioned via `RootfsManager.markProvisioned(rootfsId, true)` upon successful exit.

---

## 2. Planned Scope & Acceptance Criteria

- **AC-1 (Script Synthesis):** `ArchProvisioningScriptBuilder` generates a valid POSIX shell script containing all 6 automated onboarding steps with `set -eo pipefail`.
- **AC-2 (Pacman Keyrings & Mirrors):** Script properly initializes pacman keys (`--init`, `--populate archlinuxarm`) and configures mirror options.
- **AC-3 (Core Package Installation):** Script includes `xorg-xwayland`, `pipewire`, `wireplumber`, compositors, and development toolchain.
- **AC-4 (AUR Makepkg Wrapper):** Installs executable `/usr/local/bin/makepkg-wrapper` with `--mflags "-A"` parameter injection for native aarch64 AUR builds.
- **AC-5 (Global Wayland & Electron Flags):** Correctly writes `/etc/environment` and `/etc/chromium-flags.conf` overrides.
- **AC-6 (Execution & Progress Tracking):** `ArchProvisioner` executes the provisioning sequence via `ProotRunner`, parses step milestones (`==> Step X/Y`), emits progress events, and writes `.alw-provisioned` upon success.
- **AC-7 (Test Coverage & Gates):** Full suite of unit tests covers script generation and provisioning orchestration. `./gradlew test` and `./gradlew assembleDebug` pass with 100% success.

---

## 3. Dependencies & Gates

- **Prerequisites:** TASK-003 (Done), TASK-004 (Done).
- **Implementation Gate:** Complete.
- **Verification Gate:** Complete (clean `./gradlew test` with 5 passing unit tests for TASK-005, 44 total project unit tests, and `./gradlew assembleDebug` passing).
- **Review Gate:** Complete (self-review against Dev Framework v0.2 and SRS v0.1).

---

## 4. Actual Changes & Evidence

- Implemented `ProvisioningConfig` specifying user account, shell, core package list, and provisioning step toggles.
- Implemented `ArchProvisioningScriptBuilder` generating POSIX bash provisioning scripts with `set -eo pipefail`:
  - Step 1: Pacman keyring initialization (`pacman-key --init`, `pacman-key --populate archlinuxarm`).
  - Step 2: System update (`pacman -Syu --noconfirm`).
  - Step 3: Workstation core packages (`xorg-xwayland`, `mesa-utils`, `pipewire`, `wireplumber`, `hyprland`, `labwc`, `kitty`, etc.).
  - Step 4: Non-root user creation and wheel group passwordless sudo configuration.
  - Step 5: AUR makepkg wrapper (`/usr/local/bin/makepkg-wrapper` injecting `--mflags "-A"` for native aarch64 builds).
  - Step 6: Global Wayland flags in `/etc/environment` and Chromium sandbox flags in `/etc/chromium-flags.conf`.
- Implemented `ArchProvisioner` orchestrating guest script execution via `ProotRunner` and `ProotProcessManager`, parsing step milestones in real time, emitting progress events, and creating `.alw-provisioned` markers.
- Created `ArchProvisioningScriptBuilderTest` and `ArchProvisionerTest` with 5 passing unit tests.
- Documented revision-bound verification evidence in [evidence/verification.md](evidence/verification.md).
