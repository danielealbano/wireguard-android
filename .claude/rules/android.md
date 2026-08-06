# Android Rules — ABSOLUTE RULES

These rules govern the **Android build, SDK, platform surfaces, packaging, signing, and CI/release**
that are common to BOTH modules (`:ui` Kotlin app, `:tunnel` Java library). They are **VERY STRICT
and ABSOLUTELY NON-NEGOTIABLE**. Language specifics live in `kotlin.md` / `java.md` / `go.md`;
project context in `project.md`.

## Scope in THIS repository — READ FIRST

- Build: **Gradle 9.3.1** (wrapper, `distributionSha256Sum` pinned — do NOT remove) + **Android
  Gradle Plugin 9.1.0**, all build scripts in **Kotlin DSL** (`*.gradle.kts`).
- **`compileSdk = 36`, `minSdk = 24`, `JavaVersion.VERSION_17`** for BOTH modules — keep them in
  sync. Core library desugaring is enabled in `:ui` (`desugarJdkLibs`).
- **Kotlin is provided by AGP built-in Kotlin (AGP 9.0+)**: there is NO `org.jetbrains.kotlin.android`
  plugin, and you MUST NOT add one — applying it is a hard error in AGP 9.x. KAPT (for DataBinding)
  is applied via the AGP plugin `com.android.legacy-kapt`.
- Versions are centralized in **`gradle/libs.versions.toml`**; app identity in **`gradle.properties`**
  (`wireguardVersionCode`, `wireguardVersionName`, `wireguardPackageName`).

## 1) Build System & Configuration — ABSOLUTE RULES

- You MUST declare ALL dependencies and plugins through the **version catalog**
  (`libs.versions.toml`) — NO hardcoded coordinates in `build.gradle.kts`. Before adding/bumping,
  you MUST check the latest stable version (per `agent.md`); keep AGP, Gradle, and AndroidX aligned.
- You MUST keep `:ui` and `:tunnel` on the SAME `minSdk`/`compileSdk`/`JavaVersion`.
- You MUST invoke everything through the Gradle **wrapper** (`./gradlew`); you MUST NOT rely on a
  system Gradle. `settings.gradle.kts` enforces `FAIL_ON_PROJECT_REPOS` — repositories are declared
  centrally, NOT per-module.
- You MUST NOT weaken `gradle.properties` performance/correctness settings
  (`org.gradle.parallel`, `org.gradle.caching`, `kapt.include.compile.classpath=false`) without a
  reason and user approval.
- The **native build is part of the Gradle build**: `:tunnel`'s `externalNativeBuild` (CMake) builds
  `libwg-go.so`, `libwg.so`, `libwg-quick.so` for every ABI, wired through `tools/CMakeLists.txt`
  (Go via `libwg-go/Makefile`). A change MUST keep this green for all ABIs; the `release`/`debug`
  CMake args pass `ANDROID_PACKAGE_NAME` (`…` vs `….debug`) — keep that split intact. See `go.md`.

### Build types — do NOT break these (there are NO product flavors)
This project defines three **build types** and **no product flavors**; `googleplay` is a build type,
not a flavor.
- **`debug`**: `applicationIdSuffix = ".debug"`, `versionNameSuffix = "-debug"` (installs alongside
  release; native code uses the `.debug` package path).
- **`release`**: `isMinifyEnabled = true`, `isShrinkResources = true`, and
  `proguardFiles("proguard-android-optimize.txt")` — a **project-relative** path that resolves to the
  in-repo `ui/proguard-android-optimize.txt` (NOT the SDK's `getDefaultProguardFile`), plus packaging
  `excludes`. You MUST NOT disable minify/shrink for release, and you MUST keep R8 keep-rules correct
  when you add reflection/DataBinding/serialization surfaces.
- **`googleplay`** (in `:ui`): `initWith(release)` + `matchingFallbacks += "release"`. Its build-type
  source set `ui/src/googleplay/AndroidManifest.xml` removes **only** the `REQUEST_INSTALL_PACKAGES`
  permission (`tools:node="remove"`); the self-`Updater` is disabled **in code** (it early-returns
  when `BuildConfig.BUILD_TYPE == "googleplay"` or the app was installed from Google Play). Keep the
  two distributions consistent: a change to release behavior MUST be considered for both `release`
  and `googleplay`.

## 2) Manifest, Components & Permissions — ABSOLUTE RULES

- **Every `android:exported` component is an attack surface.** You MUST justify `exported="true"`,
  and sensitive components MUST be permission-guarded. Specifically:
  - `TunnelManager$IntentReceiver` (remote up/down/refresh) MUST stay behind the custom
    **`${applicationId}.permission.CONTROL_TUNNELS`** (`protectionLevel="dangerous"`) AND the
    `UserKnobs.allowRemoteControlIntents` runtime gate. NEVER loosen either.
  - Launcher activities (`MainActivity`, `TvMainActivity` leanback), `QuickTileService`
    (`BIND_QUICK_SETTINGS_TILE`), and the boot/update receivers are the only intentionally-exported
    surfaces. `LogViewerActivity` and its `ExportedLogContentProvider` MUST stay `exported="false"`
    (the provider serves logs only via granted content URIs).
  - **Debug only:** `com.wireguard.android.debug.TestReceiver` (the `ui/src/debug/` overlay, the
    on-device e2e driver) is `exported="true"` but guarded by `android:permission="android.permission.DUMP"`
    — a permission the adb shell holds and ordinary apps do not — and it exists ONLY in the `debug`
    build type. You MUST NOT weaken that guard, add it to release/googleplay, or rely on
    `getCallingUid()` (a system-dispatched broadcast does not carry the sender's uid).
- You MUST request the **minimum permissions**. The current set (`CAMERA` for QR, `INTERNET`,
  `ACCESS_NETWORK_STATE` for the WebSocket network-switch bump, `RECEIVE_BOOT_COMPLETED`,
  `REQUEST_INSTALL_PACKAGES` for the non-Play updater, `SYSTEM_ALERT_WINDOW` on SDK 34+ for the tile
  fallback, legacy `WRITE_EXTERNAL_STORAGE` `maxSdkVersion=28`) is deliberate — do NOT add permissions
  without user approval, and remember the `googleplay` build type strips `REQUEST_INSTALL_PACKAGES`
  via its manifest overlay.
- Managed-config (`@xml/app_restrictions` → `AdminKnobs`) and locale config
  (`generateLocaleConfig = true`) are part of the contract; keep them working.

### VpnService — ABSOLUTE
- `GoBackend.VpnService` (`android.net.VpnService`) is the userspace-VPN entry point. You MUST
  preserve its invariants:
  - The **`VpnService.prepare(context)` consent flow** before establishing a tunnel.
  - The `VpnService.Builder` construction (addresses, DNS, search domains, routes, allowed/excluded
    apps, MTU) and the **kill-switch semantics** (allow both families UNLESS a single peer carries a
    `/0` default route).
  - **`protect()`ing the underlying sockets** (`wgGetSocketV4`/`V6`) so tunnel traffic does not loop.
  - The always-on VPN path (`onStartCommand` → `AlwaysOnCallback`) and clean teardown in `onDestroy`.
- You MUST NOT move VPN establishment off `VpnService`, and MUST NOT log the config it builds.

## 3) Static Analysis & Formatting — ABSOLUTE RULES

- **Android Lint is THE static analyzer** for this repo. Run `./gradlew :ui:lintDebug :tunnel:lint`.
  The Definition of Done requires **ZERO Lint errors**.
- The ONLY permitted Lint adjustments are the ones ALREADY in the build files: `:ui` disables
  `LongLogTag` and sets `MissingTranslation`/`ImpliedQuantity` to `warning` (translations are managed
  via Crowdin); `:tunnel` disables `LongLogTag` and `NewApi`. You MUST NOT add new `disable`s,
  `@SuppressLint`, `//noinspection`, or a **lint baseline** to hide findings — FIX the root cause.
  Any genuinely unavoidable suppression REQUIRES user approval first (per `agent.md`/`go.md`).
- There is **NO ktlint, detekt, spotless, checkstyle, PMD, or jacoco** in this project. You MUST NOT
  introduce one without the user's explicit decision — it is a project-wide tooling change.
- Kotlin/Java compiler warnings MUST be taken seriously (`:ui` compiles Java with `-Xlint:unchecked`
  + deprecation); keep the build warning-clean.

## 4) Signing & Release — ABSOLUTE RULES (release path is ROADMAP)

- **A shippable release MUST be a SIGNED release/`googleplay` variant — NEVER a debug build.** Today
  there is **no release `signingConfig`**, so `assembleRelease` produces an UNSIGNED APK; adding
  signing + CI is the planned work (see `project.md` → Roadmap; do NOT implement ad hoc).
- **Secrets are SACRED.** Keystores (`*.jks`) and passwords MUST NEVER be committed (`*.jks` is
  gitignored — keep it so) or logged. The release `signingConfig` MUST read its material from the
  **environment / CI secrets**, never from files in the repo.
- **Intended signing wiring (ROADMAP):** a `release` `signingConfig` in `:ui` reads
  `KEYSTORE_BASE64` (base64-decoded to a temp keystore at build time), `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD` from the environment; when they are absent (local dev), the release
  build stays unsigned rather than failing configuration.
- The `:tunnel` **Maven publication** is signed with **GPG** (`signing { useGpgCmd() }`) and published
  into the **local** `SonatypeUpload` Maven-layout repo (`build/sonatype`), which the
  `zipReleasePublication` task packages as a `*-maven.zip` for upload to Maven Central; its GPG
  key/credentials come from the environment too. Publishing is NOT part of ordinary development.

### CI / GitHub Actions — ROADMAP (do NOT create ad hoc)
Per `project.md`, CI is planned as GitHub Actions and MUST, when built, follow this shape:
- **On push / PR:** run the quality gates — `./gradlew assembleDebug`, `:ui:lintDebug :tunnel:lint`,
  `:tunnel:test` — and upload the **debug APK as a CI artifact**. The native build requires the
  **Android NDK** and the **Go toolchain** (the Makefile downloads/pins Go; the NDK comes from the
  SDK manager) — the workflow MUST provision both and use the pinned Gradle wrapper.
- **On `v*` tag:** build a **signed release APK AND AAB** (signing material from the secrets above)
  and attach them to a **GitHub Release**.
- The workflow MUST NOT print secrets and MUST use the wrapper (respecting `distributionSha256Sum`).

## 5) Testing (Android level)

- Automated tests today are **JVM unit tests in `:tunnel` only** (`./gradlew :tunnel:test`; JUnit 4).
  There is **NO** instrumentation/`androidTest`, Robolectric, or Espresso. See `java.md`/`kotlin.md`.
- Adding an instrumentation or Robolectric harness is a **tooling decision that REQUIRES the user** —
  do NOT introduce one unprompted.
- The JVM unit tests MUST NOT require a device, root, a live network, or the Play Store.
- **On-device e2e (separate gate):** `scripts/e2e-android.sh` drives the debug build over adb (the
  debug-only `TestReceiver`; VPN consent via the `ACTIVATE_VPN` appop) against a live
  WireGuard/wstunnel server. It is NOT part of the JVM suite; it is the MANDATORY final gate of every
  plan (`project.md` → Testing) and MUST fully pass before the flow is complete.

## 6) Quality Gates — ABSOLUTE RULES

A change touching the Android build/app is DONE **ONLY** if ALL are true:

- `./gradlew assembleDebug` builds cleanly for all ABIs (including the native `externalNativeBuild`),
  with NO new warnings.
- `./gradlew :ui:lintDebug :tunnel:lint` has ZERO errors (only the documented `disable`s).
- `./gradlew :tunnel:test` passes.
- No new permissions, exported components, dependencies, lint suppressions, signing config, or CI
  added beyond what was agreed with the user.
- When releasing: the artifact is a **signed** release/`googleplay` build (per §4).
- Mermaid charts (if any docs were touched) validate per `development_pipeline.md` §9.

### Standard commands (see `project.md` → Standard Commands; Makefile wrapping these is ROADMAP)
- Build (debug): `./gradlew assembleDebug`
- Release APK / AAB: `./gradlew assembleRelease` / `./gradlew bundleRelease`
- Lint: `./gradlew :ui:lintDebug :tunnel:lint`
- Unit tests: `./gradlew :tunnel:test`
- Clean: `./gradlew clean`
