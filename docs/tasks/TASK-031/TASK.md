# TASK-031 — Bi-Directional Clipboard (`wl_data_device`) & Android Share Sheet Bridge

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Wayland Data-Transfer Protocol, Android Clipboard & Intent Integration  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `bridge/wayland/`, `core/session/`, `app/`, `docs/tasks/TASK-031/`  
**Risk:** Medium  
**Status:** **Planned** — spec registered; implementation starts after TASK-026/027/028 are complete.  
**Depends on:** TASK-025 (`alw-host.sock` line protocol + native AF_UNIX server).  
**Fallback preserved:** if the Android clipboard is unavailable (permission/OEM restriction) or the image MIME cannot be decoded, text sync degrades independently and the guest still sees a valid local `wl_data_device` implementation (copy/paste within guest apps keeps working). The share receiver never overwrites existing guest files.

---

## 1. Problem Statement

`wl_data_device_manager` is announced (global 8, version 3) and `WaylandClient` tracks
`dataSources`/`dataDevices`, but in `bridge/wayland/src/main/cpp/wayland_bridge.cpp` only the
create/destroy opcodes are consumed. No selection, offer, send or receive events exist, so:

| Area | Current state | Gap |
|---|---|---|
| `wl_data_device_manager` | `get_data_source`/`get_data_device` object ids only | No `wl_data_source`/`wl_data_device`/`wl_data_offer` semantics |
| Selection | None | `data_device.selection(offer\|null)` never sent; `wl-copy`/`wl-paste` see nothing |
| Guest → host | None | No `wl_data_source.send(mime, fd)` handler |
| Host → guest | None | No `wl_data_offer.receive(mime, fd)`; Android clipboard never read |
| Android clipboard | None | No `ClipboardManager` integration |
| Share sheet | None | No `ACTION_SEND`/`ACTION_SEND_MULTIPLE` receiver; no `/home/alw/Inbox/` bridge |

## 2. Architecture — clipboard and share paths

```
┌──────────────────────── guest (Hyprland) ────────────────────────────────────┐
│  wl-copy "text"  ▸ wl_data_source.offer(text/plain;charset=utf-8, UTF8_STRING)│
│                  ▸ data_device.set_selection(source)                          │
│  wl-paste        ▸ data_device selection offer ▸ wl_data_offer.receive(fd)    │
└──────────────┬───────────────────────────────────────────────────────────────┘
               │
┌──────────────▼──────────── Android host (wayland_bridge.cpp) ─────────────────┐
│  wl_data_device_manager v3                                                    │
│    wl_data_source  : offer · send(mime, fd) · cancelled · target              │
│    wl_data_device  : data_offer · enter/leave · motion · drop · selection      │
│    wl_data_offer   : accept · receive(mime, fd) · destroy · finish             │
│                                                                               │
│  spool: $XDG_RUNTIME_DIR/alw-clipboard/                                        │
│    manifest  (mime=… size=… seq=…)                                            │
│    payload   (raw bytes)                                                      │
└──────────────┬────────────────────────────────────────────────────────────────┘
               │  poke: alw-host.sock  "clipboard set" / "clipboard get"
┌──────────────▼──────────── Kotlin (core/session) ─────────────────────────────┐
│  ClipboardBridge : ClipboardManager.OnPrimaryClipChangedListener              │
│    host → spool → native selection offer                                       │
│    guest → spool → ClipboardManager.setPrimaryClip                              │
│                                                                               │
│  ShareReceiverActivity (app/) : ACTION_SEND / ACTION_SEND_MULTIPLE             │
│    ContentResolver.openInputStream → files/rootfs/<id>/home/alw/Inbox/         │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Protocol requirements

- Announce `wl_data_device_manager` version 3. Handle:
  - `wl_data_device_manager.create_data_source(new_id)` and `get_data_device(new_id, seat)`.
  - `wl_data_source.offer(mime)` (accumulate, do not drop), `destroy`; emit `target(mime)`,
    `send(mime, fd)`, `cancelled()`.
  - `wl_data_device.set_selection(source, serial)`, `start_drag(...)` (return an error / no-op
    safely), `release`; emit `data_offer(new_id)`, `enter`, `leave`, `motion`, `drop`,
    `selection(offer|null)`.
  - `wl_data_offer.accept(mime)`, `receive(mime, fd)`, `destroy`, `finish`; emit `offer(mime)`.
- Advertised MIME set, in this order: `text/plain;charset=utf-8`, `UTF8_STRING`,
  `text/plain`, `image/png`, `image/jpeg`.
- Every `receive`/`send` fd is written and closed exactly once; a client that closes early must
  not crash the event loop (`EPIPE` handling).
- `selection(offer)` must be emitted on `set_selection` and when the host clipboard changes
  while the client is focused.

### 2.2 Host bridge and spool

- The native event loop must never call into the JVM. Use the existing file-spool pattern under
  `$XDG_RUNTIME_DIR/alw-clipboard/` and the `alw-host.sock` line protocol for pokes.
- `ClipboardBridge` (new, `core/session`) owns `ClipboardManager`:
  - `OnPrimaryClipChangedListener` → decode `ClipData.Item` (text or `Uri`/`InputStream`) →
    write `manifest` + `payload` → poke `alw-host.sock` `clipboard changed` → native emits
    `selection`.
  - Guest copy: native writes the spool and pokes `clipboard set`; `ClipboardBridge` reads it and
    calls `setPrimaryClip` **while the session activity is foreground** (Android 10+ restricts
    background clipboard access; documented and handled).
- Size caps (text ~1 MB, images ~16 MB) and MIME validation; reject unsupported types with a
  logged no-op.

### 2.3 Android Share Sheet

- `ShareReceiverActivity` (`app/`) with `ACTION_SEND` and `ACTION_SEND_MULTIPLE`, MIME
  `text/plain`, `image/*`, and `*/*` fallback; `exported="true"` (it must be reachable from other
  apps) with no dangerous permissions.
- Resolve each `content://` URI with `ContentResolver.openInputStream`, sanitise the display name
  (strip path separators, NUL, `..`), enforce a size cap, and copy to
  `files/rootfs/<id>/home/alw/Inbox/` (the guest sees `/home/alw/Inbox/`). Uniquify on collision;
  never overwrite.
- If no session is running, store the file and show a notification/banner; when the session
  starts, the Inbox is already populated.
- Trigger the guest file manager via the existing `alw-host.sock` `open` command (optional).

## 3. Phases

### Phase 0 — Protocol scaffolding and text selection
- Implement the `wl_data_source`/`wl_data_device`/`wl_data_offer` object graph and selection
  events; verify copy/paste between two guest apps with `wl-copy`/`wl-paste` (no Android yet).

### Phase 1 — Host → guest text
- Add `ClipboardBridge` (text only) + spool; Android copy appears in the guest `wl-paste`.
- Handle Android's clipboard-restriction behaviour; degrade to guest-local clipboard.

### Phase 2 — Guest → host text and images
- Native `send` handler writes the spool; `ClipboardBridge` sets the Android clipboard.
- Add `image/png` and `image/jpeg` encode/decode (Bitmap ↔ PNG/JPEG) both directions.
- Verify round-trips and MIME negotiation between `wl-copy`, a browser, and an Android app.

### Phase 3 — Share sheet → guest Inbox
- Add `ShareReceiverActivity` + manifest intent filters; file copy/sanitisation; notification
  when idle; evidence with a real shared photo/document.

### Phase 4 — Hardening and tests
- fd-leak and EPIPE tests; malformed offer handling; large-payload limits; unit tests for the
  MIME/selection state machine and filename sanitiser; session lifecycle cleanup.

## 4. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture, phases and this AC table | **Done** |
| **AC-02** | `wl-copy` in the guest makes the text available to Android (`ClipboardManager`), and Android copies appear in `wl-paste` | Planned |
| **AC-03** | PNG/JPEG images round-trip guest ↔ Android via `wl-copy`/`wl-paste` and an image-aware app | Planned |
| **AC-04** | Guest-internal copy/paste works even when the host clipboard is unavailable | Planned |
| **AC-05** | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` from another Android app lands in `/home/alw/Inbox/` with a sanitised, non-colliding filename; no overwrite | Planned |
| **AC-06** | No fd leaks or event-loop stalls on early client close / rejected MIME; sustained copy/paste soak passes | Planned |
| **AC-07** | Clipboard access honours Android 10+ foreground restrictions and never crashes in the background | Planned |
| **AC-08** | Unit tests + `./gradlew assembleDebug` green; evidence in `docs/tasks/TASK-031/evidence/`; commit pushed to `origin/main` | Planned |

## 5. Verification Plan

1. Guest-only: `wl-copy hello` in one terminal, `wl-paste` in another (Phase 0).
2. Host → guest: copy text in an Android app, `wl-paste`; screenshot/terminal capture.
3. Guest → host: `wl-copy` text and a PNG; paste into an Android app; screenshot.
4. Images: `wl-copy < screenshot.png`; paste into an Android image-capable app and back.
5. Share sheet: share a photo from Gallery to the workstation; verify `Inbox/` contents and that
   special characters in names are sanitised.
6. Soak: 100 alternating copy/paste operations; check fds (`ls /proc/<pid>/fd`) and no stalls.

## 6. Risks & Mitigations

- **Android background clipboard restrictions** → only read/write while the session UI is
  foreground; document the limitation and keep guest-local clipboard functional.
- **Binary payloads through a line protocol** → never send bytes in the protocol; use the spool
  file + a one-line poke.
- **Unsanitised shared filenames (path traversal)** → strict basename sanitisation + uniqueness;
  reject `..`, separators and NUL.
- **fd leaks in `receive`/`send`** → central helper that always closes, plus the soak test.
- **Vendor clipboard differences (HyperOS)** → verify `ClipDescription` MIME handling on the
  Xiaomi Pad 8; fall back to `text/plain` when a custom MIME is rejected.
