# WireGuard WS — Android GUI with WebSocket/wstunnel support

> **Unofficial fork — not the official WireGuard app.** This is based on the official
> [WireGuard for Android](https://www.wireguard.com/) app and adds support for tunnelling WireGuard
> over **WebSocket / wstunnel**, so you can reach a server on networks that block plain UDP.
>
> It installs under its own application id (`com.danielealbano.wireguard.ws`) and can be installed
> alongside the official `com.wireguard.android` app.

This is an Android GUI for [WireGuard](https://www.wireguard.com/). It [opportunistically uses the kernel implementation](https://git.zx2c4.com/android_kernel_wireguard/about/), and falls back to using the non-root [userspace implementation](https://git.zx2c4.com/wireguard-go/about/).

## WebSocket / wstunnel support

This build carries the per-peer WebSocket/wstunnel transport in the config model and the tunnel editor
UI. The app only *consumes* that transport — running it **requires the matching server-side forks**:

- **wireguard-go fork** — <https://github.com/danielealbano/wireguard-go> — the userspace core
  (compiled into `libwg-go.so`) that implements the WebSocket/wstunnel transport.
- **wireguard-tools fork** — <https://github.com/danielealbano/wireguard-tools> — the byte-compatible
  `wg`/`wg-quick` config surface for the server side.

Standard UDP tunnels keep working unchanged; a peer uses the WebSocket path only when its endpoint is a
`ws://` / `wss://` URL (or its transport is set explicitly). All WebSocket parameters are editable in
the tunnel editor, with a document picker for TLS material.

## Building

```
$ git clone --recurse-submodules https://github.com/danielealbano/wireguard-android
$ cd wireguard-android
$ ./gradlew assembleRelease
```

The native build needs the Android NDK/CMake and downloads a pinned Go toolchain via
`tunnel/tools/libwg-go/Makefile`. macOS users may need [flock(1)](https://github.com/discoteq/flock).

## Embedding

The tunnel library is [on Maven Central](https://search.maven.org/artifact/com.wireguard.android/tunnel), alongside [extensive class library documentation](https://javadoc.io/doc/com.wireguard.android/tunnel).

```
implementation 'com.wireguard.android:tunnel:$wireguardTunnelVersion'
```

The library makes use of Java 8 features, so be sure to support those in your gradle configuration with [desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring):

```
compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
    coreLibraryDesugaringEnabled = true
}
dependencies {
    coreLibraryDesugaring "com.android.tools:desugar_jdk_libs:2.0.3"
}
```

## Translating

Please help us translate the app into several languages on [our translation platform](https://crowdin.com/project/WireGuard).
