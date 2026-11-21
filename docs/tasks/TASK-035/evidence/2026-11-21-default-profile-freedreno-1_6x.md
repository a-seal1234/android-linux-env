# Default profile = Hyprland + freedreno, monitor scale 1.6x (on-device, 2026-11-21)

**Device:** Xiaomi Pad 8 (Adreno 825, HyperOS), wireless ADB `192.168.51.161:45527`.
**Build:** `:app:assembleDebug` @ branch `main`, `adb install -r -d` succeeded.

## What changed

1. **`display.scaleFactor` 1.75 → 1.6 on every profile default** (`StackProfile.DisplayConfig`
   default, JSON parse fallback, `defaultArchXiaomiPad8`, `safeModeProfile`, `kdePlasmaProfile`)
   and in `ConfigSynthesizer.generateDefaultHyprlandUserConfig()`.
   3200x2136 / 1.6 = **2000x1335**, an exact integer monitor mode, so Hyprland no longer rounds
   the requested scale and stops emitting `Invalid scale passed to monitor WAYLAND-1`.
2. **The shipped default profile is now Hyprland + freedreno KGSL**:
   `StackProfile.defaultArchXiaomiPad8()` returns `driverMode = FREEDRENO_KGSL` (no
   `mesaLoaderOverride`), and `DashboardViewModel.refresh()` selects the first
   `DriverMode.FREEDRENO_KGSL` profile instead of preferring `arch-kde-plasma`. A KDE/Zink
   profile still loads and is still selectable — it is just no longer the default.
3. `.commandcode/profile-backup-kde.json` and `.commandcode/profile-backup-zink.json` were the
   temporary on-device profile swaps; the KDE profile was restored to the tablet and the local
   backups were removed.

## On-device proof

`logcat` from the launched session:

```
ALW::PtyBridgeNative:  argv[92] = FD_MESA_DEBUG=sysmem,noubwc
ALW::WaylandBridgeNative: Wayland protocol server started ... (3200x2136 @ scale 1.60)
ALW::WaylandBridgeNative: GLES blit #61: 3200x2136 (scale 1.00, fps 19.9)
```

No `Invalid scale passed to monitor WAYLAND-1: 1.75` / `using rounded scale` line appears anywhere
in the session log, and `fastfetch` inside the guest reports:

```
Display (WAYLAND-1): 3200x2136 @ 1.59, 60 Hz
Window Manager: Hyprland (0.57.2, Wayland)
GPU: Qualcomm Adreno (TM) 825 [Integrated]
```

Screenshot: `.commandcode/scale-1.6.png` (HUD `1.00x`, guest at 1.59 monitor scale).

The session also proves the profile resolution end-to-end: the profile JSON carries no
`FD_MESA_DEBUG`/`MESA_LOADER_DRIVER_OVERRIDE` key, yet the launched environment contains
`FD_MESA_DEBUG=sysmem,noubwc` (from `EnvironmentGenerator`'s `FREEDRENO_KGSL` branch), so the
freedreno profile is the one the app picked and launched.

Note the two different scales: the **monitor scale** (1.6, guest/logical pixels) is what Hyprland
rounds, while the **render scale** (1.0, bridge blit resolution on the Android surface) is the
`1.00x` in the HUD — the 1.0x default from the previous change is unchanged.

## Tests

`./gradlew :core:session:test :core:proot:test :app:assembleDebug` → **BUILD SUCCESSFUL**
(102 tests, 0 failures). Turnip-specific tests now build their profile explicitly through a
`turnipProfile()` helper, and `ProotRunnerTest#testBuildLaunchCommandArguments` asserts the new
default (`MESA_LOADER_DRIVER_OVERRIDE=kgsl`, `GALLIUM_DRIVER=kgsl`,
`FD_MESA_DEBUG=sysmem,noubwc`, `ALW_GL_BACKEND=freedreno`).
