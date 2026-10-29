package com.md3music.premium;

/**
 * JNI bridge into libkugou_server.so (Rust cdylib, from the MD3Music project's
 * embedded KuGou concept-lite API server).
 *
 * The JNI symbols inside the .so are bound to THIS exact fully-qualified class
 * name (Java_com_md3music_premium_KugouApiService_*), so the package and class
 * name must not be changed unless the .so is rebuilt.
 *
 * The library exposes a tiny HTTP server on 127.0.0.1 implementing the
 * KuGouMusicApi route set with the concept (lite) protocol baked in.
 */
public class KugouApiService {
    private static boolean sLoadAttempted = false;
    private static boolean sLoaded = false;

    /** Best-effort load; returns false instead of throwing when the so is missing. */
    public static synchronized boolean ensureLoaded() {
        if (sLoaded) return true;
        if (sLoadAttempted) return false;
        sLoadAttempted = true;
        try {
            System.loadLibrary("kugou_server");
            sLoaded = true;
            return true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            return false;
        }
    }

    // Instance methods (JNI symbols are compiled against jobject receivers).
    public native int nativeStartNode(int port, String dataDir);
    public native boolean nativeIsNodeRunning();
    public native void nativeStopNode();
}
