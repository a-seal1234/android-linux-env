# TASK-035 on-device verification (2026-11-20)

**Device:** Xiaomi Pad 8, wireless ADB `192.168.51.161:45539` (the wireless debugging port is
reassigned on reboot/restart — always re-check `adb devices`).
**Build:** `app-debug.apk` installed via the manual `/sdcard/Download/` tap-install
(`lastUpdateTime=2026-11-20 22:56:15`; HyperOS rejects `adb install`).

## Result: overlay artifact GONE

`qs_overlay_after_fix.png` — Hyprland + Quickshell (Sekai Pop bar, right-side clock/date widget,
bottom dock, ALW HUD pill, kitty fastfetch). The **repeat-slanted macro-tile shear is gone**:
the bar, widgets and dock render cleanly, the wallpaper shows through correctly and the HUD pill
(`100.0x`, `CTRL`, PEN, 0.0 ms) is intact.

Root cause confirmed by A/B during the fix (recorded in the task spec, Phase 2b): the CPU path
rendered the overlay pixel-perfect while the GPU path sheared diagonally, because guests commit a
padded overlay buffer (`2400x1602 stride=9728`) and the Adreno ES driver ignores
`GL_UNPACK_ROW_LENGTH`. `presentWithGl()` now repacks padded rows to a tight stride.

Corroborating log traps for this run:

- **zero** `rejecting non-linear modifier …` lines — no client ever presents a UBWC/tiled buffer
  any more (`TU_DEBUG=noconform,noubwc` + LINEAR-only advertisement), so the import guard never
  has to fire.
- `mmap fallback blit active` count: 82 (CPU-path frames for cursor/offscreen buffers).
- Presenter frames: `GLES blit #5041: 3200x2136 (scale 1.00, fps 8.0–10.0)` — idle desktop
  (damage-driven compositor), consistent with the pre-fix gate measurements; input-driven drag
  FPS remains the TASK-026 metric (~30 FPS per the gate).

## Refresh rate: request set, panel refuses

`dumpsys display` → `mActiveModeId=4` (60 Hz) while a session runs. The app logs
`ALW::RefreshRate: requested preferredDisplayModeId=2 (modes=1:120Hz, 2:144Hz, …, 4:60Hz)`, i.e.
the 144 Hz mode is selected correctly in `WindowManager.LayoutParams`, but **HyperOS keeps the
panel on mode 4**. `WaylandSurfaceView.applyForcedRefreshRate()` still pins the surface to the
profile rate with `setFrameRate(…, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)`, so the guest pacer
runs at the requested cadence even though the panel itself stays at 60 Hz.

**Open:** forcing the physical panel to 144 Hz needs a privileged/system API
(`DisplayManager.setPreferredDisplayMode` under Shizuku, or `settings put system peak_refresh_rate`).
Tracked for a follow-up; not silently claimed as done.

## Also verified in this build

- Immersive focus mode: status/navigation bars hidden for the session lifetime.
- `misc.vfr` red banner: gone (the key is no longer emitted — Hyprland 0.57 rejects it).
