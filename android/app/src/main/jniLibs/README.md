# jniLibs — libkugou_server.so

Embedded KuGou concept-lite API server (Rust cdylib), taken from the
MD3Music project (https://github.com/zzyoxml/md3Music, AGPL-3.0),
directory `kugou_api_server/rust`, build script `build_android.sh`.

- Loaded via JNI by `com.md3music.premium.KugouApiService` (the JNI symbols
  inside the .so are bound to that exact class name).
- Exposed to the WebView through `top.izuna.foliamajor.KugouServerPlugin`
  (Capacitor plugin `KugouServer`).
- Listens on 127.0.0.1 with a random port; the JS side picks it up via
  `src/services/kugouEmbeddedServer.ts` and routes the KuGou provider there
  with zero user configuration (concept/lite protocol baked into the server).

To rebuild the .so from source: clone MD3Music, run
`kugou_api_server/rust/build_android.sh` with NDK + rustup android targets,
then overwrite the four ABI files here.
