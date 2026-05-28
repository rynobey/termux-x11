// Native bridge for the Vortek integration.
//
// Phase 1 contents: a tiny helper that creates a listening AF_UNIX SOCK_STREAM
// socket in the FILESYSTEM namespace at the given path, and returns the listening
// fd. Android's public Java API only exposes abstract-namespace bind through
// LocalServerSocket(String); the Vortek client uses sun_path (filesystem), so
// we do socket()+bind()+listen() in C and hand the fd to Java, which wraps it
// in LocalServerSocket(FileDescriptor) via reflection.
//
// Built into libXlorie.so (added to the Xlorie source list in recipes/xserver.cmake).
//
// Phase 2 TODO: this file will also host the JNI bridge from VortekRendererComponent's
// @Keep callbacks (getWindowHardwareBuffer / updateWindowContent / getWindowWidth/Height)
// to Lorie's per-X-window AHardwareBuffer plumbing in buffer.c.

#define _GNU_SOURCE
#include <jni.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <android/log.h>

#define VK_BRIDGE_TAG "vortek_bridge"
#define VK_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VK_BRIDGE_TAG, __VA_ARGS__)
#define VK_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  VK_BRIDGE_TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_com_winlator_xenvironment_components_VortekServer_bindUnixServerSocket(
        JNIEnv *env, jclass cls, jstring jpath) {
    (void)cls;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return -EINVAL;

    struct sockaddr_un addr;
    if (strlen(path) >= sizeof(addr.sun_path)) {
        VK_LOGE("path too long (>%zu): %s", sizeof(addr.sun_path) - 1, path);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -ENAMETOOLONG;
    }

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        int e = errno;
        VK_LOGE("socket(AF_UNIX, SOCK_STREAM) failed: %s", strerror(e));
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -e;
    }

    // Clear stale socket file from a previous run.
    unlink(path);

    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        int e = errno;
        VK_LOGE("bind(%s) failed: %s", path, strerror(e));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -e;
    }

    if (listen(fd, 8) < 0) {
        int e = errno;
        VK_LOGE("listen failed: %s", strerror(e));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -e;
    }

    VK_LOGI("vortek socket listening at %s (fd=%d)", path, fd);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return fd;
}
