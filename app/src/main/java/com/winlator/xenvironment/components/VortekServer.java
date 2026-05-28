package com.winlator.xenvironment.components;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Accepts Vortek-client connections on a filesystem-path Unix socket.
 *
 * Wire protocol per Pt.1 of the Vortek Internals RE: client connects, sends a
 * single byte == 1 as handshake; the server then creates two ashmem regions
 * (server ring 4 MiB, client ring 256 KiB) and sends both fds back via
 * sendmsg/SCM_RIGHTS. All of that happens inside the closed native
 * createVkContext(clientFd, options); our job is just to listen, accept, read
 * the handshake byte, and hand the accepted fd in.
 *
 * Filesystem-namespace binding goes through {@link #bindUnixServerSocket} —
 * a small JNI helper in {@code app/src/main/cpp/lorie/vortek_bridge.c} built
 * into libXlorie.so. Android's public Java API (LocalServerSocket(String))
 * binds only in the abstract namespace; the Vortek client uses sun_path
 * (filesystem), so we do socket()+bind()+listen() in C and wrap the returned
 * int fd into a {@link LocalServerSocket} via reflection on FileDescriptor.
 *
 * The open Vortek client (brunodev85/vortek) must be recompiled with
 * VORTEK_SERVER_PATH set to whatever path is passed to this constructor.
 */
@Keep
public class VortekServer {
    private static final String TAG = "VortekServer";

    static {
        // libXlorie.so is the Termux-X11 native lib; we extended it with the
        // vortek_bridge.c JNI helper. Loading is idempotent if LorieView has
        // already loaded it on the UI thread.
        System.loadLibrary("Xlorie");
    }

    private final String socketPath;
    private final VortekRendererComponent renderer;
    private volatile boolean running;
    private Thread acceptThread;
    private LocalServerSocket serverSocket;

    public VortekServer(String socketPath, VortekRendererComponent renderer) {
        this.socketPath = socketPath;
        this.renderer = renderer;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "vortek-accept");
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (acceptThread != null) {
            try { acceptThread.join(500); } catch (InterruptedException ignored) {}
        }
    }

    private void acceptLoop() {
        try {
            File f = new File(socketPath);
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            // vortek_bridge.c does socket()+bind()+listen() and returns the listening fd
            // (or a negative errno on failure).
            int fd = bindUnixServerSocket(socketPath);
            if (fd < 0) {
                Log.e(TAG, "bindUnixServerSocket(" + socketPath + ") failed, errno=" + (-fd));
                running = false;
                return;
            }
            serverSocket = new LocalServerSocket(wrapFd(fd));
            Log.i(TAG, "listening on " + socketPath + " (fd=" + fd + ")");
        } catch (Exception e) {
            Log.e(TAG, "bind failed on " + socketPath + ": " + e);
            running = false;
            return;
        }
        while (running) {
            try {
                LocalSocket client = serverSocket.accept();
                new Thread(() -> handleClient(client), "vortek-client").start();
            } catch (Exception e) {
                if (running) Log.e(TAG, "accept failed: " + e);
            }
        }
    }

    private void handleClient(LocalSocket client) {
        try {
            InputStream in = client.getInputStream();
            int b = in.read();
            if (b != 1) {
                Log.w(TAG, "rejecting client: bad handshake byte 0x" + Integer.toHexString(b));
                client.close();
                return;
            }
            int clientFd = getInt(client.getFileDescriptor());
            // createVkContext (in libvortekrenderer.so) creates the two shm rings,
            // sends their fds back over the accepted socket via SCM_RIGHTS, and
            // spawns its own worker thread. We can return from this Java thread
            // once it returns.
            long ctx = renderer.createContextForClient(clientFd);
            if (ctx > 0) {
                Log.i(TAG, "Vortek context created: ptr=0x" + Long.toHexString(ctx)
                        + " clientFd=" + clientFd);
                // TODO Phase 2: track ctx <-> client to call destroyVkContext on socket close.
            } else {
                Log.e(TAG, "createVkContext returned " + ctx);
                client.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "handleClient: " + e);
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    /** Bind a filesystem-namespace AF_UNIX SOCK_STREAM listening socket.
     *  Implemented in {@code app/src/main/cpp/lorie/vortek_bridge.c} (libXlorie.so).
     *  Returns the listening fd on success, or a negative errno on failure. */
    private static native int bindUnixServerSocket(String path);

    /** Read the int fd from an existing FileDescriptor (used for the accepted client). */
    private static int getInt(FileDescriptor fd) throws Exception {
        Method m = FileDescriptor.class.getDeclaredMethod("getInt$");
        m.setAccessible(true);
        return (Integer) m.invoke(fd);
    }

    /** Wrap an int fd into a FileDescriptor for {@link LocalServerSocket}. */
    private static FileDescriptor wrapFd(int fd) throws Exception {
        FileDescriptor fileDescriptor = new FileDescriptor();
        Method setInt = FileDescriptor.class.getDeclaredMethod("setInt$", int.class);
        setInt.setAccessible(true);
        setInt.invoke(fileDescriptor, fd);
        return fileDescriptor;
    }
}
