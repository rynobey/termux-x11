# Vortek ↔ Termux-X11 integration (`feat/vortek-integration`)

GPU-accelerated native Linux apps (Vulkan + GL via Zink) in a proot Ubuntu, displayed
through Termux-X11, on Pixel 10 / Tensor G5 / PowerVR DXT.

## What this is

[Vortek](https://github.com/brunodev85/vortek) is Winlator's Vulkan wrapper. Its closed
**server** half (`libvortekrenderer.so`) loads the device's vendor Vulkan driver via
[adrenotools](https://github.com/bylaws/libadrenotools), implements per-driver workarounds,
and presents into **AHardwareBuffer**-backed `VkImage`s. Its open **client** half
(`libvulkan_vortek.so` + ICD json) sits in the proot rootfs as a Vulkan ICD; the two
halves talk over a filesystem Unix socket + shared-memory ring buffers.

This branch integrates the **server** into Termux-X11 — so Lorie provides the per-X-window
`AHardwareBuffer`s the server needs, and the closed lib runs inside our app's process
instead of Winlator's. End result: a Vulkan-capable proot Ubuntu where Vulkan/GL apps
render on the real PowerVR GPU and present through Lorie's X server.

For the full reverse-engineered ABI / protocol / present semantics, see
`linux-setups/pixel/docs/podroid-gpu-strategy.md` §13.

## Why the package is `com.winlator.xenvironment.components`

The closed lib resolves its JNI entry points by full Java symbol path
(`Java_com_winlator_xenvironment_components_VortekRendererComponent_createVkContext`,
etc.) and looks up the four `@Keep` callbacks by name + signature via `GetMethodID` on
the host instance. So the package and class name are fixed — they don't depend on
Termux-X11's own packages.

## Status

- [x] **Phase 1 — Scaffold (this commit).**
  - `app/src/main/java/com/winlator/xenvironment/components/VortekRendererComponent.java`
    declares the four native methods and the four `@Keep` callbacks (stubs returning 0).
  - `VortekServer.java` opens a filesystem-namespace Unix socket, accepts a client,
    reads the 1-byte handshake, and hands the fd to `createVkContext` — which (in
    libvortekrenderer.so) allocates the shared-memory ring buffers and SCM_RIGHTS them
    back to the client.
  - `scripts/extract-vortek-libs.sh` pulls `libvortekrenderer.so` + `libwinlator.so` out
    of a Winlator APK into `app/src/main/jniLibs/arm64-v8a/` (gitignored — do not commit
    the closed binaries), and `vortek-2.1.tzst` (the open client) into `third_party/`.
  - **Not yet wired into MainActivity** — running the existing Termux-X11 app behaves
    exactly as before.

- [ ] **Phase 2 — Wire callbacks to Lorie.**
  - New file `app/src/main/cpp/lorie/vortek_bridge.{c,h}` with native impls for
    "look up X window XID → backing `AHardwareBuffer`". Use Lorie's existing `LorieBuffer`
    helpers (`buffer.{c,h}`) — `LorieBuffer_wrapAHardwareBuffer`, allocation via
    `AHardwareBuffer_allocate`. Damage path on `updateWindowContent` goes through
    Lorie's renderer queue.
  - New Java class `LorieVortekBridge` with static native methods bridging the
    `@Keep` callbacks in `VortekRendererComponent` into the bridge.
  - Hookup from `MainActivity` (or `CmdEntryPoint`): instantiate `VortekRendererComponent`
    + start `VortekServer` once the X server is up.

- [ ] **Phase 3 — Client deployment + smoke test.**
  - Recompile the open Vortek client (`brunodev85/vortek`) with
    `VORTEK_SERVER_PATH = "/data/data/com.termux.x11/files/vortek/V0"` (or whatever
    `VortekServer` is configured with). Extract its build outputs into a proot Ubuntu:
    `libvulkan_vortek.so` → `/usr/lib/`, ICD json → `/usr/share/vulkan/icd.d/`.
  - From inside proot:
    `VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/vortek_icd.aarch64.json DISPLAY=:0 vulkaninfo`
    → expect a PowerVR device. Then `vkcube` → renders into a Lorie window.
  - Install Mesa **Zink** in the proot for GL apps; then XFCE.

## Build prerequisites

```bash
# 1. Switch to this branch
git checkout feat/vortek-integration

# 2. Pull submodules (if you haven't)
git submodule update --init --recursive

# 3. Extract the closed Vortek libs from a Winlator APK
./scripts/extract-vortek-libs.sh          # downloads Winlator 11.0 to /tmp
# or
./scripts/extract-vortek-libs.sh /path/to/Winlator_11.0.apk
```

The libs are excluded by `.gitignore` (`*.so` and `third_party/`) — every developer
extracts locally; they are not redistributed in this repo.

## Risks / open questions still in play

- **libwinlator.so deps when loaded outside the Winlator app.** It has no `JNI_OnLoad`
  and its JNI methods are for Winlator-only Java classes (`GPUImage`, `XInputStream`, …)
  that we don't have. They'll sit unused. Verified-safe at load time; runtime behaviour
  to confirm during Phase 2 bring-up.
- **`Os.bind` with `UnixSocketAddress` requires API 33+.** Pixel 10 (Android 16) is
  fine; pre-33 needs a small NDK helper.
- **GPU hangs are real on the PowerVR driver** — Heaven with tessellation hard-hung the
  whole device. Treat GPU work as crash-prone until driver matures.
