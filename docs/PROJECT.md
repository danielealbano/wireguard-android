# wireguard-android — Project

**wireguard-android** is the official **Android GUI for [WireGuard](https://www.wireguard.com/)**
(application id `com.wireguard.android`). It lets a user create, import, edit, and toggle WireGuard
tunnels, and brings them up/down through one of two backends:

- a **non-root userspace backend** (`GoBackend`) that runs the `wireguard-go` userspace
  implementation — compiled to `libwg-go.so` — behind an Android `VpnService`; and
- a **root kernel backend** (`WgQuickBackend`) that drives the in-kernel WireGuard module via
  `wg-quick` + `wg` through a root shell, used only when the device is rooted and the kernel module
  is present.

The app [opportunistically uses the kernel implementation and falls back to the userspace one](https://git.zx2c4.com/wireguard-android/about/),
so the same config runs on any device.

> **This repository is a FORK** of upstream `WireGuard/wireguard-android` (`origin` =
> `github.com/danielealbano/wireguard-android`). The extensions targeted here are captured in
> [Roadmap](#roadmap): switching the userspace backend to the `danielealbano/wireguard-go` fork
> (which adds a per-peer WebSocket/wstunnel transport) and supporting that transport in the config
> model and UI, byte-compatible with the sibling `wireguard-tools` fork's config surface. Non-trivial
> work proceeds via the development pipeline (`.claude/rules/development_pipeline.md`); these
> canonical docs MUST be kept current as decisions land.

---

## What It Does

- Stores each tunnel as a standard **wg-quick `.conf`** file in app-internal storage (no database).
- Parses and validates configs into an immutable model (`com.wireguard.config`), and serializes them
  back to both the **wg-quick** form (kernel backend / export) and the **UAPI userspace** form
  (Go backend).
- Brings tunnels up/down, tracks state, and reports live **statistics** (rx/tx, last handshake).
- Imports configs from `.conf`/`.zip` files, QR codes (camera or image), and clipboard/text; exports
  all tunnels as a zip.
- Integrates with Android platform surfaces: **VpnService** (incl. Always-On VPN), a **Quick
  Settings tile**, **boot** restore, an **Android TV** (leanback) UI, biometric-gated key reveal,
  managed-device restrictions, and a remote-control broadcast API guarded by a custom permission.
- Ships a **non-Play self-updater** (Ed25519/signify-verified APK from `download.wireguard.com`,
  installed via `PackageInstaller`) that is disabled in the `googleplay` build type and whenever the
  app was installed from Google Play.

---

## Tech Stack

Versions are authoritative in `gradle/libs.versions.toml`, `gradle.properties`, the module
`build.gradle.kts` files, and `tunnel/tools/libwg-go/{go.mod,Makefile}`. Re-verify before bumping.

| Concern | Choice | Notes |
|---|---|---|
| App language | **Kotlin** (`:ui`) | Provided by AGP **built-in Kotlin** (AGP 9.0+); no Kotlin Gradle plugin. |
| Library language | **Java 17** (`:tunnel`) | Config/crypto/backends; published to Maven Central. |
| Userspace core | **Go** cgo `c-shared` (`libwg-go`) | Wraps `golang.zx2c4.com/wireguard`; built into `libwg-go.so`. |
| Native tools | **C** (`libwg`, `libwg-quick`) | `wireguard-tools` + `wg-quick`, built with `-Wall -Werror`. |
| Build | **Gradle 9.3.1** + **AGP 9.1.0** | `compileSdk=36`, `minSdk=24`, JVM 17, Kotlin DSL, version catalog. |
| Desugaring | `desugar_jdk_libs` | Core library desugaring in `:ui` (java.time etc. on minSdk 24). |
| UI | AndroidX + **Material 3**, DataBinding + ViewBinding, **Coroutines**, **DataStore** | KAPT via `com.android.legacy-kapt`. |
| QR | `zxing-android-embedded` | Camera scan + image decode. |
| Biometrics | `androidx.biometric` | Gates private-key reveal and config export. |
| VPN | Android `VpnService` / root `wg-quick` | Two backends (see below). |
| Library publish | `maven-publish` + GPG `signing` | Artifact `com.wireguard.android:tunnel` (+ javadoc/sources jars). |
| Release / CI | GitHub Actions + signed release | **ROADMAP** — not yet present. |

**No external service dependency**: all I/O is local (VPN interface, UDP sockets, the app's config
files, `logcat`) plus the updater's HTTPS fetch from `download.wireguard.com`.

---

## Repository Layout

| Path | Responsibility |
|---|---|
| `ui/` | **`:ui` — the Kotlin application.** Activities, fragments, view-model proxies, DataBinding, DataStore knobs, updater, Quick tile, boot receiver. Composition root is `Application`. |
| `ui/src/main/AndroidManifest.xml` | App components + permissions; `ui/src/googleplay/` is the `googleplay` build-type manifest overlay. |
| `ui/proguard-android-optimize.txt` | R8 keep-rules for the minified release. |
| `tunnel/` | **`:tunnel` — the reusable Java library** (published). |
| `tunnel/src/main/java/com/wireguard/config/` | Immutable config model + parser (`Config`, `Interface`, `Peer`, `InetEndpoint`, `InetNetwork`, `Attribute`, exceptions). |
| `tunnel/src/main/java/com/wireguard/crypto/` | Curve25519, `Key`, `KeyPair`, key formats. |
| `tunnel/src/main/java/com/wireguard/android/backend/` | `Backend`/`Tunnel` interfaces, `GoBackend` (+ `VpnService`), `WgQuickBackend`, `Statistics`, `BackendException`. |
| `tunnel/src/main/java/com/wireguard/android/util/` | `SharedLibraryLoader`, `RootShell`, `ToolsInstaller`. |
| `tunnel/src/test/java/` | JUnit 4 unit tests (`ConfigTest`, `BadConfigExceptionTest`). |
| `tunnel/tools/CMakeLists.txt` | Native build: `libwg-go.so` (Go), `libwg.so` + `libwg-quick.so` (C), ELF cleaning. |
| `tunnel/tools/libwg-go/` | Go JNI shim (`api-android.go`, `jni.c`), `go.mod`, cross-compile `Makefile`, Go runtime patch. |
| `tunnel/tools/wireguard-tools/`, `tunnel/tools/elf-cleaner/` | Git submodules (upstream C sources). |
| `gradle/libs.versions.toml` | Version catalog (single source of dependency/plugin versions). |
| `gradle.properties` | `wireguardVersionCode` / `wireguardVersionName` / `wireguardPackageName` + Gradle flags. |
| `docs/` | This document + `ARCHITECTURE.md` (and `docs/plans/` for pipeline plans). |

---

## The Two Backends

Both implement the `com.wireguard.android.backend.Backend` interface; the app selects one at runtime
(`Application.determineBackend()`) and never talks to the native layer directly.

### GoBackend (userspace, no root — the default)
- Loads `libwg-go.so` (`SharedLibraryLoader`) and calls it over JNI:
  `wgTurnOn`/`wgTurnOff`/`wgGetSocketV4`/`wgGetSocketV6`/`wgGetConfig`/`wgVersion`.
- Establishes an Android **`VpnService`** interface (addresses, DNS, routes, allowed/excluded apps,
  MTU, kill-switch), then hands the TUN fd + UAPI config string to `wgTurnOn`, and `protect()`s the
  underlying sockets so tunnel traffic does not loop.
- Supports **Always-On VPN** (system-started service + callback).

### WgQuickBackend (kernel module, root — opt-in)
- Used only when `UserKnobs.enableKernelModule` is set AND `/sys/module/wireguard` exists.
- Writes the config to a temp `.conf` and runs `wg-quick up/down` via a **root shell** (`RootShell`),
  installing the bundled `wg`/`wg-quick` tools first (`ToolsInstaller`). Reads stats via `wg show`.

The JNI names/signatures on `GoBackend`, the C bindings in `tunnel/tools/libwg-go/jni.c`, and the
Go `//export` functions in `api-android.go` form a **contract that MUST stay in sync**.

---

## Configuration & Runtime Surfaces

### Config model
`Config` = one `Interface` + a list of `Peer`s, parsed from a wg-quick `.conf`. Value types are
externally immutable and built via `Builder`s. Two serializations are produced:
`toWgQuickString()` (kernel backend + export) and `toWgUserspaceString()` (the UAPI `get=`/`set=`
form the Go backend feeds to `libwg-go`). Endpoints (`InetEndpoint`) are `host:port` and reject
`/?#`; DNS resolution happens off the main thread.

### Persistence
`FileConfigStore` keeps one `<name>.conf` per tunnel in the app's internal storage (`context.filesDir`).
Runtime knobs (backend choice, dark theme, multi-tunnel, restore-on-boot, remote-control gate,
last-used/running tunnels, updater state) live in a **Preferences DataStore** via `UserKnobs`;
managed-device restrictions (`disable_config_export`) come from `AdminKnobs` (`@xml/app_restrictions`).

### Android components (manifest)
- **Activities:** `MainActivity` (launcher, master/detail), `TvMainActivity` (leanback launcher),
  `SettingsActivity`, `TunnelCreatorActivity`, `LogViewerActivity` (+ non-exported
  `ExportedLogContentProvider`), `TunnelToggleActivity`, and zxing's `CaptureActivity`.
- **Receivers:** `BootShutdownReceiver` (boot restore / shutdown save, kernel backend),
  `Updater$AppUpdatedReceiver` (`MY_PACKAGE_REPLACED`), `TunnelManager$IntentReceiver` (remote
  up/down/refresh, guarded by the `CONTROL_TUNNELS` dangerous permission + a runtime knob).
- **Service:** `QuickTileService` (Quick Settings tile). `GoBackend.VpnService` is the VPN service.
- **Permissions:** `CAMERA`, `INTERNET`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_INSTALL_PACKAGES`
  (non-Play updater; removed in `googleplay`), `SYSTEM_ALERT_WINDOW` (SDK 34+ tile fallback), legacy
  `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`), and the custom `CONTROL_TUNNELS` permission.

---

## Build & Commands

The authoritative command surface is a root **`Makefile` wrapping `./gradlew`** — **ROADMAP; not yet
present**. Until it exists, use the Gradle wrapper directly (see
`.claude/rules/project.md` → Standard Commands for the full target ↔ command map):

- Build (debug): `./gradlew assembleDebug`
- Release APK / AAB (signed — see Roadmap): `./gradlew assembleRelease` / `./gradlew bundleRelease`
- Android Lint: `./gradlew :ui:lintDebug :tunnel:lint`
- Unit tests: `./gradlew :tunnel:test`
- Publish `:tunnel` (release only): `./gradlew :tunnel:publishReleasePublicationToSonatypeUploadRepository`
- Clean: `./gradlew clean`

Building from a fresh checkout requires `--recurse-submodules` (for `wireguard-tools` + `elf-cleaner`),
the **Android NDK/CMake**, and network access for the pinned **Go toolchain** the `libwg-go/Makefile`
downloads. macOS may need `flock(1)`.

---

## Testing

- Automated tests are **JVM JUnit 4 unit tests in `:tunnel` only** (`./gradlew :tunnel:test`) —
  config parsing, crypto, and config serialization. They run offline against the Android stub
  `android.jar`, so tested code stays free of Android framework calls.
- There is **NO** `:ui` unit/instrumentation test harness today (no Robolectric, Espresso, or
  Mockito), and the native code has no in-repo tests — its correctness comes from the build and from
  upstream `wireguard-go`/`wireguard-tools`. Adding any test harness is a tooling decision that
  requires explicit sign-off (see `.claude/rules/{kotlin,java,android}.md`).
- Tests MUST NEVER require a rooted device, a live network, or the Play Store.
- **On-device e2e (separate gate, not part of the JUnit suite):** `scripts/e2e-android.sh
  <config.conf>` (ROADMAP — delivered by the WebSocket plan) drives a real device over adb —
  debug-only intent surface, VPN consent via the `ACTIVATE_VPN` appop — to prove a config imports,
  the tunnel comes up (handshake + traffic), and the VPN survives a Wi‑Fi→cellular switch. It is a
  MANDATORY final gate for every plan (see `.claude/rules/project.md` → Testing).

See `docs/ARCHITECTURE.md` for how the modules, backends, native toolchain, and data model fit
together (with diagrams).

---

## Roadmap

Planned extensions to this fork (each proceeds through the development pipeline; nothing here is
implemented unless a plan under `docs/plans/` says so):

1. **Switch the userspace backend to `danielealbano/wireguard-go`** (a sibling checkout; the module
   path stays `golang.zx2c4.com/wireguard`, consumed via a `go.mod` `replace` directive pinned to
   the fork's **v1.3.0 UDP-parity contract** — commit-pinned to the tip of its parity branch until
   the `v1.3.0` tag is published, then re-pinned to the tag). The fork adds a **per-peer
   WebSocket/wstunnel transport**: every peer carries `transport=udp|websocket|wstunnel`,
   `endpoint=` stays a resolved `ip:port` for every transport, and the WS layer travels in a
   separate per-peer `ws_url` plus `ws_*` UAPI keys (contract: the fork's `docs/CONFIGURATION.md`
   and `docs/ANDROID_INTEGRATION.md`). The JNI boundary **gains new surface**: the bind becomes
   `conn.NewMultiplexBind` (UDP + WebSocket in one bind; the UDP data path is unchanged and
   `wgGetSocketV4/V6` keep protecting the UDP sub-bind), every WebSocket dial is protected via a
   Go→Java **per-dial `VpnService.protect(fd)` callback** (`conn.WithWSProtect`), and a
   socket-bump export wrapping `device.BindUpdate()` is driven from a
   `ConnectivityManager` network callback on network switches.
2. **WebSocket/wstunnel config + UI support**, byte-compatible with the sibling `wireguard-tools`
   fork's config surface: `[Peer] Endpoint` accepts a `ws(s)://host:port/path` URL,
   `WSMode = websocket|wstunnel` selects the transport (`WSTunnelTarget` required for wstunnel),
   plus `WSBearer`, `WSMask`, `WSTLSCA`/`WSTLSCert`/`WSTLSKey`, `WSTLSInsecure`, and
   `WSPingInterval`/`WSBackoffMin`/`WSBackoffMax` — with the same transport-inference and
   validation rules as the tools fork. The app infers `transport=`, resolves the URL host to the
   routable `endpoint=ip:port` (the existing `InetEndpoint` DNS pre-resolution; `InetEndpoint`
   itself keeps requiring `host:port`), and emits `ws_url=` separately. The tunnel editor exposes
   ALL parameters (with a file selector for the TLS material paths). **Backend dispatch becomes
   per-tunnel:** a config containing any websocket/wstunnel peer always runs on `GoBackend`; a
   pure-UDP config keeps the classic kernel-vs-userspace selection; `WgQuickBackend` fails fast
   with a specific `BackendException` on a WS config (the `wireguard-tools` submodule stays on
   upstream — the kernel path never carries WS).
3. **CI + signed release automation** (GitHub Actions): quality gates + a debug-APK artifact on
   push/PR; a **signed release APK + AAB** attached to a GitHub Release on `v*` tags, with the
   keystore supplied via CI secrets (`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
   `KEY_PASSWORD`). A root **Makefile** wrapping `./gradlew` becomes the command surface.

See `docs/ARCHITECTURE.md` → *Roadmap integration points* for where these map onto the existing
boundaries.
