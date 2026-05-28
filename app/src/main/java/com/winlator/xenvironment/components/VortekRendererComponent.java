package com.winlator.xenvironment.components;

import android.util.Log;

import androidx.annotation.Keep;

/**
 * Termux-X11 fork of Winlator's VortekRendererComponent.
 *
 * The package + class name MUST stay com.winlator.xenvironment.components.VortekRendererComponent —
 * the prebuilt libvortekrenderer.so resolves its JNI entry points by full Java symbol
 * path (e.g. Java_com_winlator_xenvironment_components_VortekRendererComponent_createVkContext).
 * The native-method names and signatures must match exactly. The closed lib also looks up
 * the four @Keep callbacks (getWindowWidth/Height/HardwareBuffer/updateWindowContent) via
 * JNI GetMethodID with the same names + signatures.
 *
 * Phase 1 (this file): class compiles, lib loads (pulls in libwinlator.so via NEEDED),
 * the four callbacks are stubs. A VortekServer in this package wraps the accept/handshake
 * loop on a filesystem-path Unix socket.
 *
 * Phase 2 (TODO): wire the four callbacks to a LorieVortekBridge whose native side lives
 * in app/src/main/cpp/lorie/vortek_bridge.c — it looks up the X window XID in Lorie and
 * returns/manages its backing AHardwareBuffer.
 *
 * Phase 3 (TODO): drop the open Vortek client (libvulkan_vortek.so + ICD json, from
 * brunodev85/vortek recompiled with VORTEK_SERVER_PATH matching VortekServer.DEFAULT_PATH)
 * into a proot Ubuntu and validate with vulkaninfo / vkcube / Zink.
 */
@Keep
public class VortekRendererComponent {
    private static final String TAG = "VortekRenderer";

    public static final int VK_MAX_VERSION = vkMakeVersion(1, 3, 128);
    public static final short IMAGE_CACHE_SIZE = 256;

    static {
        // libvortekrenderer.so NEEDS libwinlator.so + system libs (libandroid, libEGL,
        // libGLES{v2,v3}, libjnigraphics). Both closed libs go into jniLibs/arm64-v8a/
        // via scripts/extract-vortek-libs.sh. Neither has JNI_OnLoad, so static-init is
        // a clean no-op apart from address-space mapping.
        System.loadLibrary("vortekrenderer");
        Log.i(TAG, "libvortekrenderer.so loaded");
    }

    /** Per-context options. Field names + types match what the closed lib reads via JNI. */
    @Keep
    public static class Options {
        public int vkMaxVersion = VK_MAX_VERSION;
        public short maxDeviceMemory = 0;
        public short imageCacheSize = IMAGE_CACHE_SIZE;
        public byte resourceMemoryType = 0;
        public String[] exposedDeviceExtensions = null; // null => expose all
        public String libvulkanPath = null;             // null => adrenotools loads system Vulkan
    }

    private final Options options;

    /**
     * @param nativeLibraryDir applicationInfo.nativeLibraryDir — the dir holding
     *                         libvortekrenderer.so + libwinlator.so. The native init uses
     *                         this + adrenotools to load the vendor Vulkan driver.
     */
    public VortekRendererComponent(String nativeLibraryDir, Options options) {
        this.options = options;
        initVulkanWrapper(nativeLibraryDir, options.libvulkanPath);
        Log.i(TAG, "Vortek Vulkan wrapper initialized (driver=" +
                (options.libvulkanPath != null ? options.libvulkanPath : "system") + ")");
    }

    public Options getOptions() { return options; }

    // Package-private bridges used by VortekServer.
    long createContextForClient(int clientFd) { return createVkContext(clientFd, options); }
    void destroyContext(long contextPtr)      { destroyVkContext(contextPtr); }
    boolean dispatchExtraDataRequest(long contextPtr, int requestCode, int requestLength) {
        return handleExtraDataRequest(contextPtr, requestCode, requestLength);
    }

    // -------------------------------------------------------------------------
    // @Keep callbacks invoked BY libvortekrenderer.so via JNI.
    // Names + signatures MUST match what the closed lib looks up.
    //   getWindowWidth/Height : (I)I
    //   getWindowHardwareBuffer : (IZ)J   — returns AHardwareBuffer* as long
    //   updateWindowContent  : (I)V
    //
    // Phase 2 TODO: replace stubs with calls into a LorieVortekBridge that maps X window
    // XIDs to Lorie's per-window AHardwareBuffer (via Lorie's LorieBuffer helpers in
    // app/src/main/cpp/lorie/buffer.{c,h}).
    // -------------------------------------------------------------------------

    @Keep
    private int getWindowWidth(int windowId) {
        // TODO Phase 2: query Lorie for the X window's width.
        Log.w(TAG, "getWindowWidth(" + windowId + ") — STUB, returning 0");
        return 0;
    }

    @Keep
    private int getWindowHeight(int windowId) {
        // TODO Phase 2: query Lorie for the X window's height.
        Log.w(TAG, "getWindowHeight(" + windowId + ") — STUB, returning 0");
        return 0;
    }

    @Keep
    private long getWindowHardwareBuffer(int windowId, boolean useHALPixelFormatBGRA8888) {
        // TODO Phase 2: return the AHardwareBuffer* (as a long) backing this X window's
        // content. Create on first call via Lorie's LorieBuffer helpers; resize on
        // ConfigureNotify. halBGRA selects BGRA8888 vs default (RGBA8888).
        Log.w(TAG, "getWindowHardwareBuffer(" + windowId + ", halBGRA=" +
                useHALPixelFormatBGRA8888 + ") — STUB, returning 0 (swapchain creation will fail)");
        return 0L;
    }

    @Keep
    private void updateWindowContent(int windowId) {
        // TODO Phase 2: damage the X window in Lorie + tell the GLES compositor to re-upload.
        Log.w(TAG, "updateWindowContent(" + windowId + ") — STUB, no-op");
    }

    // -------------------------------------------------------------------------
    // Native methods provided by libvortekrenderer.so.
    //   initVulkanWrapper        : (Ljava/lang/String;Ljava/lang/String;)V
    //   createVkContext          : (ILcom/winlator/xenvironment/components/VortekRendererComponent$Options;)J
    //   destroyVkContext         : (J)V
    //   handleExtraDataRequest   : (JII)Z
    // -------------------------------------------------------------------------

    private native void initVulkanWrapper(String nativeLibraryDir, String libvulkanPath);
    private native long createVkContext(int clientFd, Options options);
    private native void destroyVkContext(long contextPtr);
    private native boolean handleExtraDataRequest(long contextPtr, int requestCode, int requestLength);

    public static int vkMakeVersion(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | patch;
    }
}
