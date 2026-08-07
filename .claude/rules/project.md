# wireguard-android — Project Rules

This repo is **wireguard-android** (Android application `com.wireguard.android`): the **official
Android GUI for [WireGuard](https://www.wireguard.com/)**. It manages WireGuard tunnels and brings
them up/down through one of two backends — the non-root **userspace backend** (`wireguard-go`
compiled to `libwg-go.so`, driven over JNI + Android `VpnService`) and, when the device is rooted
and the kernel module is present, the **kernel backend** (`wg-quick` + `wg` via a root shell). It is
built with Gradle + the Android Gradle Plugin and ships to the Play Store.

> **STATUS: this is a FORK of upstream `WireGuard/wireguard-android`** (`origin` =
> `github.com/danielealbano/wireguard-android`, `upstream` = `github.com/WireGuard/wireguard-android`).
> The userspace backend runs the sibling **`danielealbano/wireguard-go` fork** (v1.3.0, via a
> `go.mod` `replace`), which adds a **per-peer WebSocket/wstunnel transport**, and the app supports
> that transport in the config model and UI (byte-compatible with the sibling `wireguard-tools`
> fork's config surface) to bypass network paths that block UDP — both DELIVERED (see
> `docs/PROJECT.md` → Delivered). Remaining ROADMAP: CI + signed release. Non-trivial work proceeds
> via the development pipeline per `development_pipeline.md`. The canonical docs MUST be kept current
> as decisions land.

## MANDATORY: Read These First

You MUST ALWAYS read these before ANY work, in this order:

1. **`docs/PROJECT.md`** — what the app is, tech stack, module layout, the two backends, the native
   build (Go + C via NDK/CMake), config model, Android components, build/commands, testing, roadmap.
2. **`docs/ARCHITECTURE.md`** — modules and dependencies, app-layer wiring (Application, backends,
   TunnelManager, ConfigStore), the tunnel up/down flow through `VpnService` + JNI, the native
   toolchain, and the config data model (with Mermaid charts).

You MUST ALSO follow, per the Rule Map below: `agent.md`, `development_pipeline.md`, `go.md`
(the `libwg-go` Go shim), `kotlin.md` (the `:ui` app), `java.md` (the `:tunnel` library),
`android.md` (Android/Gradle/SDK/signing), and `github.md`. It is ABSOLUTELY MANDATORY to pass ALL
quality gates before any work is considered done.

This rule file MUST stay accurate but CONCISE — it references the canonical docs, it does NOT
duplicate them.

---

## Tech Stack (current)

Versions are authoritative in `gradle/libs.versions.toml`, `gradle.properties`, the module
`build.gradle.kts` files, and `tunnel/tools/libwg-go/{go.mod,Makefile}`. Re-verify before bumping.

| Concern | Choice | Notes |
|---|---|---|
| App language | **Kotlin** (`:ui`) | 57 `.kt` files. Kotlin is provided by AGP **built-in Kotlin** (AGP 9.0+); NO `org.jetbrains.kotlin.android` plugin. See `kotlin.md`. |
| Library language | **Java 17** (`:tunnel`) | Config parser, crypto, backends. Published to Maven Central. See `java.md`. |
| Userspace core | **Go** (cgo `c-shared`) | `tunnel/tools/libwg-go` → `libwg-go.so`, wrapping `golang.zx2c4.com/wireguard`. See `go.md`. |
| Native tools | **C** (NDK/CMake) | `libwg.so` (wireguard-tools) + `libwg-quick.so` (wg-quick), `-Wall -Werror`. |
| Build system | **Gradle 9.3.1** + **AGP 9.1.0** | `compileSdk=36`, `minSdk=24`, JVM 17, core library desugaring. See `android.md`. |
| UI stack | AndroidX + Material 3, DataBinding + ViewBinding, Coroutines, DataStore | Legacy KAPT via `com.android.legacy-kapt`. |
| VPN | Android `VpnService` (GoBackend) / root `wg-quick` (WgQuickBackend) | Two backends, selected at runtime. |
| Library publishing | `maven-publish` + GPG `signing` | `:tunnel` artifact `com.wireguard.android:tunnel`. |
| Command surface | **Makefile** wrapping `./gradlew` | ROADMAP — not yet present; see Standard Commands. |
| Release / CI | **GitHub Actions** (`.github/workflows/`) + signed release | DELIVERED — `ci.yml` (build/lint/test + debug APK on push/PR), `release.yml` (signed APK+AAB on `v*` tags). See `android.md`. |

---

## Hard Project Invariants — ABSOLUTE RULES

- **TWO BACKENDS ARE SACRED; DISPATCH IS PER-TUNNEL.** `GoBackend` (userspace, no root) and
  `WgQuickBackend` (kernel + root) MUST BOTH keep working — you MUST NOT remove, disable, or
  degrade either. `Application.determineBackend()` builds a `DispatchingBackend` wrapping both: a
  config with ANY websocket/wstunnel peer (`Config.hasWebSocketPeers()`) MUST run on `GoBackend`; a
  pure-UDP config keeps the classic selection (kernel module enabled AND present → `WgQuickBackend`,
  else `GoBackend`). `WgQuickBackend` MUST fail fast
  (`BackendException.Reason.WS_REQUIRES_USERSPACE_BACKEND`) on a WS bring-up; state/statistics route
  to the owning backend. The `wireguard-tools` submodule stays on upstream.
- **THE JNI CONTRACT MUST STAY IN SYNC.** The native methods on `GoBackend`
  (`wgTurnOn`/`wgTurnOff`/`wgGetSocketV4`/`wgGetSocketV6`/`wgGetConfig`/`wgVersion`/
  `wgSetFdProtector`/`wgBumpSockets`), their C bindings in `tunnel/tools/libwg-go/jni.c`, and the Go
  `//export` functions in `api-android.go` MUST always match by name and signature. Change one →
  change all three. NOTE: `wgSetFdProtector` is a `jni.c`/Java native (no Go export); the Go→C
  protect bridge is the C `wgAndroidProtectFd(int)` upcall the WS bind calls via `conn.WithWSProtect`.
- **THE NATIVE BUILD MUST NOT BREAK.** `libwg-go.so`, `libwg.so`, and `libwg-quick.so` are
  cross-compiled for every Android ABI via CMake + the NDK (Go through `libwg-go/Makefile`). A
  change MUST keep the full `externalNativeBuild` green for all ABIs.
- **THE WIREGUARD CONFIG MODEL IS A CONTRACT.** `com.wireguard.config` (`Config`/`Interface`/`Peer`
  /`InetEndpoint`/…) parses and serializes standard wg-quick configuration; `toWgUserspaceString()`
  (UAPI, GoBackend) and `toWgQuickString()` (WgQuickBackend) MUST stay interoperable with WireGuard.
- **`:tunnel` IS A PUBLISHED LIBRARY.** Its public API (`com.wireguard.android:tunnel` on Maven
  Central, with javadoc + sources jars) is a compatibility contract. Public API changes MUST be
  deliberate and MUST keep the `@NonNullForAll` nullness annotations correct.
- **RELEASE BUILDS MUST BE SIGNED.** A shippable release MUST be a signed release variant — NEVER a
  debug build. Signing material MUST come from CI secrets / the environment, NEVER committed.
- **NO SECRETS IN LOGS.** Private keys and preshared keys MUST NEVER appear in logs, error
  messages, or exported artifacts.
- **KEEP `tunnel` AND `ui` SDK LEVELS IN SYNC** (`minSdk`/`compileSdk`); they are currently 24/36.
- Keep it SIMPLE and consistent with the existing WireGuard code style.

---

## Non-goals (MUST NOT build unless the user EXPLICITLY asks)

- Do NOT re-architect the two-backend model or drop the kernel/root backend. Do NOT add a database
  or new persistent store (configs live as files via `FileConfigStore`). Do NOT change the
  application id / package name. Do NOT implement the WebSocket **transport** here — that belongs in
  the `wireguard-go` fork; this repo only *consumes* it and adds UI. Do NOT add new native libraries
  or a Makefile ad hoc — they are Roadmap items delivered through the pipeline when scheduled. CI
  (`.github/workflows/`) and the release signing config already exist — extend them deliberately, do
  NOT add parallel/competing workflows or signing paths. Do NOT introduce new lint/format tooling
  (ktlint, detekt, spotless) — the project uses Android Lint + compiler warnings only.

---

## Commit Scopes

All commits MUST use one of the scopes below (matching the module/component layout). A commit
spanning multiple scopes uses `app`.

| Scope | Applies to |
|---|---|
| `app` | Cross-cutting changes and anything without its own scope |
| `ui` | `:ui` Kotlin application module |
| `tunnel` | `:tunnel` Java library (config, crypto, backends) |
| `native` | `tunnel/tools` — `libwg-go` (Go), `libwg`/`libwg-quick` (C), `CMakeLists.txt` |
| `gradle` | Build scripts, `libs.versions.toml`, `gradle.properties`, wrapper |
| `ci` | GitHub Actions workflows |
| `docs` | `docs/` (PROJECT, ARCHITECTURE, plans) |
| `deps` | Dependency-only updates (version catalog / `go.mod`) |
| `make` | Makefile |

```
feat(ui): add websocket endpoint field to the tunnel editor
```

---

## Standard Commands

The authoritative command surface is a root **`Makefile` that wraps `./gradlew`** and the `libwg-go`
Go tooling. **The Makefile is ROADMAP — it does NOT exist yet**; until it is added, invoke the
underlying commands directly. Intended targets → underlying commands:

| Target | Underlying command |
|---|---|
| `make build` | `./gradlew assembleDebug` |
| `make assemble-release` | `./gradlew assembleRelease` (release APK; signed when the keystore env is set — see `android.md`, else unsigned) |
| `make bundle-release` | `./gradlew bundleRelease` (release AAB; signed when the keystore env is set — see `android.md`, else unsigned) |
| `make lint` | `./gradlew :ui:lintDebug :tunnel:lint` (Android Lint) |
| `make test` | `./gradlew :tunnel:test` (JUnit unit tests) |
| `make go-vet` | `cd tunnel/tools/libwg-go && go vet ./...` |
| `make go-lint` | `cd tunnel/tools/libwg-go && golangci-lint run` |
| `make go-tidy` | `cd tunnel/tools/libwg-go && go mod tidy` |
| `make go-vulncheck` | `cd tunnel/tools/libwg-go && govulncheck ./...` |
| `make publish` | `./gradlew :tunnel:publishReleasePublicationToSonatypeUploadRepository` |
| `make mermaid-check` | validate all Mermaid blocks under `docs/` per `development_pipeline.md` §9 |
| `make e2e` | `scripts/e2e-android.sh <full-tunnel.conf> <split-tunnel.conf>` (on-device e2e) |
| `make clean` | `./gradlew clean` |

**Quality gates** (per `development_pipeline.md` §2, `android.md`, `go.md`, `kotlin.md`, `java.md`):
a clean **build** (`./gradlew assembleDebug`), **Android Lint** with ZERO errors (the build files'
documented `disable`/`warning` settings aside), the **`:tunnel` unit tests** passing, the **Go shim** clean
(`go vet` / `golangci-lint run` / `go mod tidy` with NO `go.mod`/`go.sum` diff / `govulncheck`), and
**Mermaid validation** (when Mermaid charts were touched) MUST ALL pass before any work is DONE.
For PLAN flows, the **on-device e2e** (see Testing below) is an ADDITIONAL MANDATORY final gate.

---

## Testing — ABSOLUTE (project-specific)

- Automated tests currently live ONLY in **`tunnel/src/test`** (plain **JUnit 4** — `ConfigTest`,
  `BadConfigExceptionTest`), run on the JVM via `./gradlew :tunnel:test`. New `:tunnel` logic MUST
  add JUnit tests here.
- The `:ui` module has **NO** unit or instrumentation tests today, and there is **NO** Robolectric,
  Espresso, or Mockito. You MUST NOT assume a UI test harness exists; adding one is a tooling
  decision that requires the user (see `kotlin.md`).
- Tests MUST NEVER require a rooted device, a live network, or a real Play Store — they run offline
  on the JVM. (The on-device e2e below is a SEPARATE gate, NOT part of the JUnit suite.)
- The native code (`libwg-go`, C tools) has no in-repo tests; its correctness is validated by the
  build and by upstream `wireguard-go`/`wireguard-tools`.
- **On-device e2e — MANDATORY PLAN GATE (ABSOLUTE):** EVERY plan's final ground-up verification
  MUST run `scripts/e2e-android.sh <full-tunnel.conf> <split-tunnel.conf>` against a real device over
  adb + the live WireGuard/wstunnel server, and it MUST FULLY PASS before the flow is considered
  complete. The script: baseline egress IP
  (`ifconfig.me`) → import config (debug-only intent surface; VPN consent pre-granted via the
  `ACTIVATE_VPN` appop) → tunnel up → handshake + rx/tx assertions → egress IP through the tunnel
  → Wi‑Fi off (switch to cellular) → tunnel still works and the egress IP is unchanged →
  `ping 192.168.178.1` through the tunnel → teardown. A failing or skipped e2e means the plan is
  NOT done. ZERO exceptions.

---

## Key Conventions

- **Two-backend abstraction** per `java.md`/`kotlin.md`: everything goes through the `Backend`
  interface (`GoBackend` / `WgQuickBackend`); the app never talks to `libwg-go.so` or `wg-quick`
  directly except through it.
- **`Application` is the composition root** (`:ui`): backends, `TunnelManager`, `RootShell`, and
  `ToolsInstaller` are constructed there and exposed as lazy component providers, while `ConfigStore`
  (`FileConfigStore`) is constructed there and injected into `TunnelManager` — NOT via package-level
  singletons scattered across the code.
- **Configs are files, not a database**: `FileConfigStore` persists each tunnel as a wg-quick
  `.conf`; there is no schema/DB layer.
- **DataBinding + ViewModel proxies** (`ConfigProxy`/`InterfaceProxy`/`PeerProxy`) mediate between
  the immutable `Config` model and editable UI state.
- **`@NonNullForAll`** governs nullness in `:tunnel`; `@Nullable` marks the exceptions.
- **Logging** uses `android.util.Log` with a `WireGuard/<Component>` tag; NEVER log key material.
- **Per-OS/ABI native files** stay consistent across all Android ABIs.

---

## Rule Map

| Concern | Rule file |
|---|---|
| Agnostic agent behavior, git, plans, reviews, subagents | `agent.md` |
| Plan-driven development pipeline (write → review → implement → PR) + Mermaid validation | `development_pipeline.md` |
| Go — the `libwg-go` cgo `c-shared` JNI shim (build via NDK/CMake, tooling, gates) | `go.md` |
| Kotlin — the `:ui` Android application (idioms, coroutines, DataBinding, gates) | `kotlin.md` |
| Java — the `:tunnel` published library (idioms, nullness, JUnit, API stability) | `java.md` |
| Android — Gradle/AGP/SDK, manifest & components, `VpnService`, lint, signing, CI/release | `android.md` |
| GitHub (`gh` CLI, branches, PRs) (tooling) | `github.md` |
| Project context (this file) | `project.md` |
