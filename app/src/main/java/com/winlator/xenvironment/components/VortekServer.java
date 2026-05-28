package com.winlator.xenvironment.components;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.system.Os;
import android.system.OsConstants;
import android.system.UnixSocketAddress;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Accepts Vortek-client connections on a filesystem-path Unix socket.
 *
 * Wire protocol per Pt.1 of the Vortek Internals RE: client connects, sends a single
 * byte == 1 as handshake, then the server creates two ashmem regions (server ring
 * 4 MiB, client ring 256 KiB) and sends both fds back via sendmsg/SCM_RIGHTS. All
 * of that happens inside the closed native createVkContext(clientFd, options); our
 * job is just to listen, accept, read the handshake, and hand the fd in.
 *
 * Socket path is FILESYSTEM-namespace (not abstract). The open Vortek client
 * (brunodev85/vortek) must be recompiled with VORTEK_SERVER_PATH set to whatever
 * path is passed to the constructor (default suggestion: app filesDir + "/vortek/V0").
 *
 * RequiresApi 33 because we use android.system.UnixSocketAddress. The user's target
 * (Pixel 10 / Android 16) far exceeds this. For pre-33 support we'd need a small NDK
 * helper to socket()+bind()+listen() with a sockaddr_un.
 */
@Keep
@RequiresApi(33)
public class VortekServer {
    private static final String TAG = "VortekServer";

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
            f.delete(); // clear any stale socket file
            serverSocket = bindFilesystemSocket(socketPath);
            Log.i(TAG, "listening on " + socketPath);
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

    private static LocalServerSocket bindFilesystemSocket(String path) throws Exception {
        FileDescriptor fd = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0);
        Os.bind(fd, UnixSocketAddress.createFileSystem(path));
        Os.listen(fd, 8);
        return new LocalServerSocket(fd);
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
            // sends their fds back over the accepted socket via SCM_RIGHTS, and spawns
            // its own worker thread. We can return from this Java thread once it returns.
            long ctx = renderer.createContextForClient(clientFd);
            if (ctx > 0) {
                Log.i(TAG, "Vortek context created: ptr=0x" + Long.toHexString(ctx) + " clientFd=" + clientFd);
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

    /** Extract the int fd from a FileDescriptor (Android-internal API; stable since API 1). */
    private static int getInt(FileDescriptor fd) throws Exception {
        Method m = FileDescriptor.class.getDeclaredMethod("getInt$");
        m.setAccessible(true);
        return (Integer) m.invoke(fd);
    }
}
