# StackProfile Schema & Injection Specification

**Status:** Approved Specification (Phase 0)  
**Document:** `docs/architecture/STACK-PROFILE-SCHEMA.md`  
**Schema Version:** 1.0.0  

---

## 1. Overview

The `StackProfile` is an immutable, versioned data structure that encapsulates all user and runtime configurations required to initialize an Android Linux Workstation session.

### The Non-Destructive Invariant
> No configuration in `StackProfile` may overwrite, replace, or truncate user dotfiles in `/home/<user>/`.

All properties are materialized at runtime through:
1. PRoot CLI flags (`-b`, `-w`, `-q`, `-0`).
2. Environment variables set on process invocation.
3. Ephemeral session configuration files created exclusively under `/tmp/`.
4. Dynamic IPC commands issued over compositor UNIX domain sockets.

---

## 2. JSON Schema Definition

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "StackProfile",
  "type": "object",
  "required": [
    "schemaVersion",
    "id",
    "name",
    "distro",
    "compositor",
    "gpu",
    "display",
    "storage",
    "input",
    "emulation"
  ],
  "properties": {
    "schemaVersion": {
      "type": "string",
      "enum": ["1.0.0"]
    },
    "id": {
      "type": "string",
      "pattern": "^[a-zA-Z0-9_-]+$"
    },
    "name": {
      "type": "string"
    },
    "distro": {
      "type": "object",
      "required": ["rootfsId", "architecture", "defaultUser", "shell"],
      "properties": {
        "rootfsId": { "type": "string" },
        "architecture": { "type": "string", "enum": ["aarch64"] },
        "defaultUser": { "type": "string" },
        "shell": { "type": "string" }
      }
    },
    "compositor": {
      "type": "object",
      "required": ["type", "execCommand", "configMode"],
      "properties": {
        "type": { "type": "string", "enum": ["hyprland", "labwc", "plasma", "custom"] },
        "execCommand": { "type": "string" },
        "configMode": { "type": "string", "enum": ["wrap_and_override", "direct", "safe_mode"] },
        "userConfigPath": { "type": "string" },
        "sessionConfigPath": { "type": "string" },
        "extraArgs": { "type": "array", "items": { "type": "string" } }
      }
    },
    "gpu": {
      "type": "object",
      "required": ["driverMode", "externalDriverId", "kgslNode"],
      "properties": {
        "driverMode": { "type": "string", "enum": ["turnip_kgsl", "turnip_zink", "virgl", "llvmpipe"] },
        "externalDriverId": { "type": "string" },
        "kgslNode": { "type": "string", "default": "/dev/kgsl-3d0" },
        "mesaLoaderOverride": { "type": "string" },
        "environmentOverrides": {
          "type": "object",
          "additionalProperties": { "type": "string" }
        }
      }
    },
    "display": {
      "type": "object",
      "required": ["resolutionMode", "scaleFactor", "refreshRateHz"],
      "properties": {
        "resolutionMode": { "type": "string", "enum": ["native", "custom", "dynamic"] },
        "customWidth": { "type": "integer" },
        "customHeight": { "type": "integer" },
        "scaleFactor": { "type": "number", "minimum": 0.5, "maximum": 4.0 },
        "refreshRateHz": { "type": "integer", "default": 144 }
      }
    },
    "storage": {
      "type": "object",
      "required": ["bindSdcard", "sdcardMountPoint", "excludePaths", "cacheRedirection"],
      "properties": {
        "bindSdcard": { "type": "boolean", "default": true },
        "sdcardMountPoint": { "type": "string", "default": "/mnt/sdcard" },
        "excludePaths": {
          "type": "array",
          "items": { "type": "string" },
          "default": ["/storage/emulated/0/Android/data", "/storage/emulated/0/Android/obb"]
        },
        "cacheRedirection": {
          "type": "object",
          "required": ["enabled", "internalBaseDir"],
          "properties": {
            "enabled": { "type": "boolean", "default": true },
            "internalBaseDir": { "type": "string", "default": "/home/alw/.cache" }
          }
        },
        "enableSymlink2File": { "type": "boolean", "default": true }
      }
    },
    "input": {
      "type": "object",
      "required": ["requestPointerCapture", "rebindCapsToSuper", "touchpadNaturalScroll"],
      "properties": {
        "requestPointerCapture": { "type": "boolean", "default": true },
        "rebindCapsToSuper": { "type": "boolean", "default": true },
        "touchpadNaturalScroll": { "type": "boolean", "default": true },
        "xkbOptions": { "type": "string", "default": "caps:super" }
      }
    },
    "emulation": {
      "type": "object",
      "required": ["enabled", "engine"],
      "properties": {
        "enabled": { "type": "boolean", "default": false },
        "engine": { "type": "string", "enum": ["fex", "box64"] },
        "thunkLibsEnabled": { "type": "boolean", "default": true }
      }
    }
  }
}
```

---

## 3. Example Instance: Arch Linux ARM Default on Xiaomi Pad 8

```json
{
  "schemaVersion": "1.0.0",
  "id": "arch-xiaomi-pad-8",
  "name": "Arch Linux ARM (Hyprland + Turnip KGSL)",
  "distro": {
    "rootfsId": "archlinux-default",
    "architecture": "aarch64",
    "defaultUser": "alw",
    "shell": "/bin/zsh"
  },
  "compositor": {
    "type": "hyprland",
    "execCommand": "Hyprland",
    "configMode": "wrap_and_override",
    "userConfigPath": "/home/alw/.config/hypr/hyprland.conf",
    "sessionConfigPath": "/tmp/android-session.conf",
    "extraArgs": ["-c", "/tmp/android-session.conf"]
  },
  "gpu": {
    "driverMode": "turnip_kgsl",
    "externalDriverId": "turnip-mesa-24.3-adreno825",
    "kgslNode": "/dev/kgsl-3d0",
    "mesaLoaderOverride": "kgsl",
    "environmentOverrides": {
      "TU_DEBUG": "noconform",
      "MESA_VK_WSI_PRESENT_MODE": "mailbox"
    }
  },
  "display": {
    "resolutionMode": "native",
    "customWidth": 3200,
    "customHeight": 2136,
    "scaleFactor": 1.75,
    "refreshRateHz": 144
  },
  "storage": {
    "bindSdcard": true,
    "sdcardMountPoint": "/mnt/sdcard",
    "excludePaths": [
      "/storage/emulated/0/Android/data",
      "/storage/emulated/0/Android/obb"
    ],
    "cacheRedirection": {
      "enabled": true,
      "internalBaseDir": "/home/alw/.cache"
    },
    "enableSymlink2File": true
  },
  "input": {
    "requestPointerCapture": true,
    "rebindCapsToSuper": true,
    "touchpadNaturalScroll": true,
    "xkbOptions": "caps:super"
  },
  "emulation": {
    "enabled": false,
    "engine": "fex",
    "thunkLibsEnabled": true
  }
}
```

---

## 4. Runtime Injection Mechanics

### 4.1 Hyprland Configuration Wrapping (`wrap_and_override`)
When `configMode == "wrap_and_override"`, the session launcher generates `/tmp/android-session.conf`:

```ini
# Auto-generated by Android Linux Workstation (ALW) - DO NOT EDIT MANUALLY
# Step 1: Source the user's authentic config first (preserves user customizations)
source = ~/.config/hypr/hyprland.conf

# Step 2: Append workstation hardware & Wayland bridge overrides
monitor = WL-1, 3200x2136@144, 0x0, 1.75

input {
    kb_layout = us
    kb_options = caps:super
    follow_mouse = 1
    touchpad {
        natural_scroll = true
    }
}

env = WAYLAND_DISPLAY,wayland-0
env = XDG_CURRENT_DESKTOP,Hyprland
env = ELECTRON_OZONE_PLATFORM_HINT,wayland
```

### 4.2 Safe Mode Preset
When Safe Mode is invoked:
- `compositor.type` -> `labwc`
- `gpu.driverMode` -> `llvmpipe`
- `gpu.mesaLoaderOverride` -> `llvmpipe`
- `gpu.externalDriverId` -> `system-fallback`
- All experimental ptrace extensions and x86_64 translation layers are disabled.
