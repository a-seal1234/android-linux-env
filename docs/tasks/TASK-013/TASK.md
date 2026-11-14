# TASK-013 — Stack Configurator UI, Shared Driver Store Manager & Live Compositor Switcher

**Epic:** [EPIC-ALW-P3 — Phase 3](../../initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Engineering Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** Standard  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)

---

## 1. Outcome

Implement the complete Stack Configurator UI, Shared Turnip Driver Store Manager, Live Compositor Switcher, and the Arch Linux ARM rootfs downloader & extraction engine to resolve the Step 3 onboarding gap identified during the on-device hardware smoke test.

---

## 2. Source Requirements

- **REQ-CONF-001:** `StackProfile` JSON Model & Schema Validation
- **REQ-CONF-002:** Non-Destructive Runtime Injection Principle
- **REQ-CONF-004:** Live Compositor IPC (`hyprctl keyword` / `hyprctl reload`)
- **REQ-CONF-005:** One-Tap Safe Mode (`LabWC + llvmpipe`)
- **REQ-PROOT-002:** Rootfs Download, Import & Extraction Tarballs
- **REQ-GPU-001:** External Mesa Turnip Driver Library Manager & UI

---

## 3. Planned Scope & Acceptance Criteria

1. **Rootfs Downloader Engine & Step 3 Integration:**
   - Implement `RootfsDownloader` supporting streamed HTTP/HTTPS downloads with mirror fallbacks (`http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz`, `http://mirror.archlinuxarm.org/...`, `http://fl.us.mirror.archlinuxarm.org/...`), redirect handling, download caching, and progress reporting.
   - Update `AndroidManifest.xml` with `INTERNET`, `ACCESS_NETWORK_STATE`, and `android:usesCleartextTraffic="true"`.
   - Wire `WizardViewModel` so selecting "Arch Linux ARM (Default)" downloads the rootfs if not already cached, extracts it via `RootfsExtractor`, and provisions via `ArchProvisioner`.

2. **Stack Configurator UI (`StackConfiguratorViewModel` & `StackConfiguratorScreen`):**
   - Interactive editor for `StackProfile` models:
     - Compositor selector: `HYPRLAND` vs `LABWC`.
     - GPU driver mode: `TURNIP_KGSL` / `PROPRIETARY_ZINK` / `LLVMPIPE`.
     - Adreno 825 (A8xx) `TU_DEBUG=noconform` toggle.
     - Display resolution, refresh rate (144Hz preference), and HiDPI scale factor slider (`1.0x`..`2.5x`).
     - Input hardware mapping (`rebindCapsToSuper`, natural scroll).
     - Environment variables key-value editor.
     - Storage bridge & build cache redirection toggles.
   - Profile validation via `ProfileValidator` and atomic persistence via `ProfileRepository`.

3. **Shared Turnip Driver Store Manager UI (`DriverStoreManagerScreen`):**
   - Manage installed driver versions in `files/drivers/turnip/<version>/`.
   - Query available online GitHub releases via `GitHubReleaseFetcher`.
   - Download & install Turnip releases with SHA256 checksum validation.
   - Inspect ELF ABI via `ElfAbiValidator` (`GLIBC_AARCH64` vs Android Bionic).
   - Set active Turnip version and delete unused versions.

4. **Live Compositor & Safe Mode Switcher:**
   - Live switch between `Hyprland` and `LabWC` Safe Mode from Dashboard and Floating Session Controls.
   - Regenerate default dotfiles non-destructively via `ConfigSynthesizer`.
   - Dispatch live IPC commands via `HyprlandIpcClient`.

5. **Verification & Testing:**
   - Comprehensive unit tests covering `RootfsDownloader`, `StackConfiguratorViewModel`, and `DriverStoreManager`.
   - Full Gradle build verification (`./gradlew test` and `./gradlew assembleDebug`).

---

## 4. Dependencies & Gates

- **Prerequisites:** TASK-010, TASK-011, TASK-012.
- **Status:** Done.
- **Evidence:** [evidence/verification.md](evidence/verification.md)
  - Physical Xiaomi Pad 8 Dashboard Smoke: [evidence/task013_smoke.png](evidence/task013_smoke.png)
  - Stack Configurator UI: [evidence/task013_configurator.png](evidence/task013_configurator.png)
  - Shared Driver Store Manager: [evidence/task013_driverstore.png](evidence/task013_driverstore.png)
