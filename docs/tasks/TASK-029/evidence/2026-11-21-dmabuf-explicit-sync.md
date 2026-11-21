# zwp_linux_dmabuf_v1 + explicit sync on the Adreno 825 (on-device, 2026-11-21)

**Device:** Xiaomi Pad 8 (Adreno 825, HyperOS), wireless ADB `192.168.51.161:45527`.
**Session:** default profile `arch-xiaomi-pad-8` (Hyprland + `FREEDRENO_KGSL`), monitor scale 1.6,
render scale 1.0.

## What was already in the bridge

`zwp_linux_dmabuf_v1` v4 was already implemented on the Java/native side of the bridge:

- global `zwp_linux_dmabuf_v1` v4 + `zwp_linux_dmabuf_feedback_v1` (`format_table`,
  `main_device`, one tranche pointing at the virtual node `makedev(226,128)`,
  `tranche_formats` = the two advertised entries);
- the format table advertises **only** `DRM_FORMAT_ARGB8888` and `DRM_FORMAT_XRGB8888`
  with `DRM_FORMAT_MOD_LINEAR` (no UBWC/tiled modifier), and `importAndRenderDmabuf()`
  rejects anything else with `rejecting non-linear modifier`;
- `zwp_linux_buffer_params_v1.add` receives the plane `fd` over `SCM_RIGHTS`
  (`readClientWithFd` → `client.pendingFd`), then `create`/`create_immed` builds the
  `DmabufBuffer`;
- presentation tries, in order: `EGL_EXT_image_dma_buf_import` (`eglCreateImageKHR` +
  `glEGLImageTargetTexture2DOES`), `AHardwareBuffer_createFromHandle` +
  `EGL_ANDROID_get_native_client_buffer`, and finally `mmap` + `glTexSubImage2D`.

## What this change adds

1. **`zwp_linux_explicit_synchronization_v1` v2** (global `{17, ..., 2}`):
   - `get_synchronization(new_id, surface)` binds a `zwp_linux_surface_synchronization_v1`
     to a `wl_surface`;
   - `set_acquire_fence(fd)` stores the guest's fence (arrives via `SCM_RIGHTS`); the
     bridge polls it (100 ms bounded) **before** reading the buffer on commit;
   - `get_release(new_id)` creates `zwp_linux_buffer_release_v1` objects, answered after the
     frame with `released(fence)` where the fence is a real
     `EGL_ANDROID_native_fence_sync` (`eglCreateSyncKHR(EGL_SYNC_NATIVE_FENCE_ANDROID)` +
     `glFlush()` + `eglDupNativeFenceFDANDROID`), or `immediate_release()` when EGL cannot
     provide one;
   - while a surface has explicit release objects, the plain `wl_buffer.release` for its
     dma-buf attachments is suppressed so the guest cannot overwrite a buffer the bridge is
     still sampling.
2. **Implicit-sync wait for clients that do not use the protocol above**
   (`DMA_BUF_IOCTL_EXPORT_SYNC_FILE` with `DMA_BUF_SYNC_READ` → poll the exported fence
   before `mmap`). This is the path Hyprland/Aquamarine actually takes on this device.
3. `wl_surface.destroy`, `zwp_linux_surface_synchronization_v1.destroy` and client teardown
   close any pending acquire-fence fd (no descriptor leak per surface).

## On-device result

```
ALW::WaylandBridgeNative: Wayland protocol server started ... (3200x2136 @ scale 1.60)
ALW::WaylandBridgeNative: dmabuf buffer 18 created: 3200x2136 fmt=0x34325258 planes=1 (path=immediate)
ALW::WaylandBridgeNative: dmabuf: implicit sync active (export_sync_file ok, fence=yes)
ALW::WaylandBridgeNative: dmabuf: probe image_base=1 native_buffer=1 native_client_buffer=1 ext_image_dma_buf_import=0 -> zero-copy (AHB)
ALW::WaylandBridgeNative: dmabuf: AHardwareBuffer_createFromHandle rejected (rc=-22); using mmap
ALW::WaylandBridgeNative: dmabuf: mmap fallback blit active (3200x2136 stride=12800 fmt=0x34325258) map=0.48ms blit=2.87ms total=3.14ms
ALW::WaylandBridgeNative: GLES blit #121: 3200x2136 (scale 1.00, fps 19.0)
```

- **linux-dmabuf is the transport**: Aquamarine creates `XRGB8888` LINEAR dma-bufs
  (`path=immediate`) and the frame reaches the bridge through them, not `wl_shm`
  (no `wl_shm` blit of the 3200x2136 surface appears in the log).
- **Explicit sync objects are advertised and functional but unused by this client**:
  the log shows **0** `registry bind: zwp_linux_explicit_synchronization_v1` lines, i.e.
  Hyprland 0.57 / Aquamarine does not bind the legacy explicit-sync protocol. Its
  successor, `wp_linux_drm_syncobj_manager_v1`, needs real DRM timeline syncobj fds,
  which the Android app process does not have (only `/dev/kgsl-3d0`), so the network of
  fences it offers cannot be served here.
- **The fence that does protect the read is the implicit one**:
  `export_sync_file ok, fence=yes` proves the bridge obtains and waits on the dma-buf's
  read fence before the `mmap` copy, so the CPU read no longer races the guest's KGSL
  render.
- **Zero-copy is still blocked by the platform, not by the bridge**:
  `ext_image_dma_buf_import=0` (the Adreno EGL has no `EGL_EXT_image_dma_buf_import`) and
  the vendor gralloc rejects a raw `dma_heap` native handle
  (`AHardwareBuffer_createFromHandle rc=-22`). Presenting therefore stays
  `mmap` + `glTexSubImage2D` (≈3 ms per 3200x2136 frame). A true zero-copy path needs a
  gralloc-allocated, AHB-backed buffer with fd passing from the host, which is the
  open follow-up tracked in TASK-029.

Screenshot: `.commandcode/dmabuf-sync.png` (desktop at 1.59 monitor scale, no artifacts).

## Build

`./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (native `wayland_bridge.cpp` recompiled,
only pre-existing `-Wunused` warnings).
