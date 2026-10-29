package top.izuna.foliamajor;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.md3music.premium.KugouApiService;

/**
 * Capacitor plugin hosting the embedded KuGou concept-lite API server
 * (libkugou_server.so, Rust cdylib from the MD3Music project).
 *
 * The server listens on 127.0.0.1 with a random port and implements the
 * KuGouMusicApi route set (lite protocol baked in), so the WebView talks to
 * http://127.0.0.1:<port> with zero user configuration.
 */
@CapacitorPlugin(name = "KugouServer")
public class KugouServerPlugin extends Plugin {

    private KugouApiService service;
    private int port = 0;

    @PluginMethod
    public void start(PluginCall call) {
        // start_server does dlopen-free JNI init + bind; keep it off the UI thread.
        Thread worker = new Thread(() -> {
            try {
                if (!KugouApiService.ensureLoaded()) {
                    call.reject("libkugou_server.so not available");
                    return;
                }
                if (service == null) {
                    service = new KugouApiService();
                }
                if (port > 0 && service.nativeIsNodeRunning()) {
                    JSObject ready = new JSObject();
                    ready.put("port", port);
                    call.resolve(ready);
                    return;
                }
                String dataDir = bridge.getContext().getFilesDir().getAbsolutePath();
                int actual = service.nativeStartNode(0, dataDir);
                if (actual <= 0) {
                    call.reject("start_server failed (no free port after retries)");
                    return;
                }
                port = actual;
                JSObject result = new JSObject();
                result.put("port", port);
                call.resolve(result);
            } catch (Throwable t) {
                // UnsatisfiedLinkError / RuntimeException: report instead of crashing.
                call.reject("Kugou server start failed: " + t.getClass().getSimpleName());
            }
        }, "kugou-server-start");
        worker.setDaemon(true);
        worker.start();
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject result = new JSObject();
        boolean running = false;
        try {
            running = service != null && KugouApiService.ensureLoaded() && service.nativeIsNodeRunning();
        } catch (Throwable ignored) {
            running = false;
        }
        result.put("running", running);
        call.resolve(result);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            if (service != null && KugouApiService.ensureLoaded()) {
                service.nativeStopNode();
            }
            port = 0;
            call.resolve();
        } catch (Throwable t) {
            call.reject("Kugou server stop failed: " + t.getClass().getSimpleName());
        }
    }
}
