# Java Rules — ABSOLUTE RULES

These rules govern the **`:tunnel` module** — the reusable, **published** Java library at
`tunnel/src/main/java` (packages `com.wireguard.config`, `com.wireguard.crypto`,
`com.wireguard.android.backend`, `com.wireguard.android.util`, `com.wireguard.util`). They are
**VERY STRICT and ABSOLUTELY NON-NEGOTIABLE**. Android build/SDK/lint concerns live in `android.md`;
the Kotlin app lives in `kotlin.md`; the Go shim in `go.md`; project context in `project.md`.

## Scope in THIS repository — READ FIRST

- `:tunnel` is a **Java 17** Android library (`com.android.library`), consumed by `:ui` and
  **published to Maven Central** as `com.wireguard.android:tunnel` (with javadoc + sources jars).
  **Its public API is a compatibility contract** (see Quality Gates → API stability).
- It is deliberately **UI-agnostic**: it depends only on `androidx.annotation`,
  `androidx.collection`, and (compile-only) `jsr305` — plus JUnit for tests. You MUST NOT add UI,
  coroutine, or heavyweight dependencies to it, and you MUST NOT pull `:ui` types into it.
- It owns the **WireGuard config model**, the **crypto primitives**, and the **two backends**
  (`GoBackend` over `libwg-go.so` + `VpnService`, `WgQuickBackend` over root `wg-quick`). The JNI
  contract with `libwg-go` is SACRED (see `project.md`).

## 1) Architecture & Idioms — ABSOLUTE RULES

### Idioms first
- You MUST follow standard Java and Android library conventions and keep code consistent with the
  EXISTING WireGuard style in this module (it is the reference).
- You MUST prefer **simplicity over cleverness**. Clear is better than clever.
- You MUST keep classes **immutable** where the existing model is immutable. `Config`, `Interface`,
  `Peer`, `InetEndpoint`, `InetNetwork`, `Key`, `KeyPair` are **externally immutable value types** —
  keep them that way. Mutation happens ONLY through their nested `Builder`s.
- You MUST make value classes `final`, give them `private` constructors, and construct them via
  static factories (`parse`, `fromBase64`, `fromHex`) or `Builder`s — matching the existing pattern.
- You MUST return `java.util.Optional<T>` for genuinely optional getters (as `getEndpoint()`,
  `getMtu()`, `getResolved()` already do); you MUST NOT return `null` from public getters.
- You MUST keep packages small and cohesive (config / crypto / backend / util). You MUST NEVER
  create "util"/"common" mega-classes; `com.wireguard.android.util` is for narrow, well-named helpers.
- You MUST export only what consumers need. Use `@RestrictTo(Scope.LIBRARY_GROUP)` for internal-only
  public types (as `SharedLibraryLoader` does).

### Interfaces and boundaries
- The tunnel abstraction is the **`Backend`** interface; the tunnel handle is the **`Tunnel`**
  interface. All tunnel control MUST go through `Backend`; callers MUST NOT reach into `libwg-go.so`
  or `wg-quick` directly.
- You MUST keep interfaces small and cohesive. New behavior common to both backends MUST be added to
  `Backend` deliberately (it is public API — see API stability) and implemented in BOTH `GoBackend`
  and `WgQuickBackend`.
- Constructor dependencies MUST be passed explicitly (as
  `WgQuickBackend(context, rootShell, toolsInstaller)` does). You MUST NOT introduce package-level
  singletons or static wiring inside
  `:tunnel` — the composition root is the app's `Application` (see `kotlin.md`).

### Nullness — ABSOLUTE
- Every package in `:tunnel` is annotated `@NonNullForAll` (`com.wireguard.util.NonNullForAll`):
  **everything is non-null by default**. You MUST mark the exceptions with
  `androidx.annotation.@Nullable`, and you MUST keep these annotations correct on every change.
- You MUST NEVER accept or return `null` from a non-`@Nullable` API. Where a value is optional,
  use `Optional<T>` (return) or `@Nullable` (parameter/field), never a bare nullable non-annotated
  reference.

### Concurrency
- Assume backends are called from multiple threads (UI, boot receiver, tile, always-on). You MUST
  protect shared mutable state (as `InetEndpoint.getResolved()` guards its cached resolution with a
  lock, and `WgQuickBackend` snapshots `runningConfigs`).
- Methods that perform **network or blocking I/O MUST document that they must not run on the main
  thread** (as `InetEndpoint.getResolved()` does) — the app calls them off the main dispatcher.
- You MUST NOT start unmanaged threads; long-running work is driven by the caller's executor/scope.

## 2) Coding Standards — ABSOLUTE RULES

### Validation
- You MUST validate all external input **at the boundary** — parsing is the boundary. `parse(...)`
  methods MUST reject malformed input with a specific exception carrying the offending value
  (as `InetEndpoint.parse` / `Config.parse` / `Key.fromBase64` do).

### Error handling
- You MUST use the module's **specific exception types**, never bare `RuntimeException`/`Exception`
  for known failure modes:
  - `BackendException` with its `Reason` enum for tunnel-control failures.
  - `BadConfigException` (with `Section`/`Location`/`Reason`) for invalid configuration.
  - `ParseException` for malformed tokens; `KeyFormatException` for bad keys.
- You MUST NOT swallow exceptions silently. The EXISTING best-effort parsing loops that intentionally
  `catch (… ignored)` while scanning statistics/`wg show` output are the ONLY acceptable pattern, and
  ONLY where a single malformed line must not abort the whole read; a comment MUST justify any new one.
- You MUST NOT log-and-continue for real errors — propagate them to the caller.
- You MUST NEVER expose key material in an exception message.

### Logging
- Use `android.util.Log` with a `WireGuard/<Component>` tag (as `GoBackend`/`WgQuickBackend` do).
- You MUST NEVER log private keys, preshared keys, or any key material.

### Config model contract
- `Config`/`Interface`/`Peer` MUST keep serializing to BOTH `toWgQuickString()` (for
  `WgQuickBackend`/export) and `toWgUserspaceString()` (the UAPI form for `GoBackend`), and both MUST
  remain interoperable with standard WireGuard. You MUST NOT change these formats unless the user asks.
- Endpoint/address parsing rules (e.g. `InetEndpoint` forbids `/?#` and requires `host:port`) are
  wire-compatibility constraints — you MUST NOT loosen them casually.

### Dependencies
- You MUST keep `:tunnel`'s dependency set minimal (see Scope). New third-party dependencies require
  a strong justification and user approval, because they ship to every consumer of the library.
- Versions live in `gradle/libs.versions.toml`; bump via the catalog and check for the latest stable
  (see `android.md`).

## 3) Testing Rules — ABSOLUTE RULES

- The test framework is **JUnit 4** (`junit:junit`, `testImplementation`). Tests live in
  `tunnel/src/test/java/...` next to the packages they cover (existing: `ConfigTest`,
  `BadConfigExceptionTest`). Run with **`./gradlew :tunnel:test`** (pure JVM, no device).
- Tests are **MANDATORY** for new or changed `:tunnel` logic. Config parsing, crypto, address/endpoint
  handling, and config serialization are pure JVM logic and MUST be covered: happy path, edge cases,
  and failure modes (assert the specific exception type/`Reason`).
- There is **NO Robolectric, Espresso, or Mockito** in this module, and unit tests run against the
  Android stub `android.jar` (framework calls throw by default). Therefore you MUST keep tested logic
  **free of Android framework calls** (`android.util.Log`, `Context`, …). Test the pure model/parser
  code; do NOT try to unit-test `GoBackend`/`WgQuickBackend` (they need a device/root) unless you
  first extract testable pure-Java helpers. Adding a device/instrumentation harness is a tooling
  decision that REQUIRES the user (see `android.md`).
- You MUST follow **Arrange-Act-Assert**, name tests `Class_method_scenario` (e.g.
  `Config_parse_rejectsMissingPort`), and cover multiple input cases explicitly (JUnit 4 has no
  subtests — use parameterized runners or separate, clearly-named methods).
- Tests MUST be fast, offline, order-independent, and self-cleaning (temp files via
  `TemporaryFolder`/`File.createTempFile`). They MUST NOT hit the network or require root.

## 4) Quality Gates — ABSOLUTE RULES

### Definition of Done
A `:tunnel` change is DONE **ONLY** if ALL are true:

- Relevant JUnit tests are written AND `./gradlew :tunnel:test` passes.
- `./gradlew :tunnel:build` compiles cleanly (Java 17, `-Xlint:unchecked` + deprecation warnings
  addressed).
- **Android Lint is clean**: `./gradlew :tunnel:lint` has ZERO errors (the module's documented
  `disable`s — `LongLogTag`, `NewApi` — are the ONLY suppressions; see `android.md`).
- `@NonNullForAll`/`@Nullable` annotations are correct for every touched member.
- Public API changes are intentional and documented (see below).
- No TODOs, no commented-out dead code, no "temporary hacks".

### API stability — ABSOLUTE
- `:tunnel` is **published**. You MUST treat its public/protected API as a contract: **prefer additive
  changes**; you MUST NOT remove or break existing public signatures unless the user EXPLICITLY asks.
- Every public type/member MUST carry Javadoc (a `withJavadocJar` is published) accurate to behavior.
- Bumping the artifact version happens via `wireguardVersionName` in `gradle.properties`; publishing
  is `maven-publish` + GPG `signing` (see `android.md`). You MUST NOT publish as part of ordinary
  development.

### Fix broken tests / lint — ABSOLUTE
- You MUST fix ANY broken test or lint error, even if unrelated — finish your change first, then fix
  it immediately. You MUST NEVER leave `:tunnel:test` or Android Lint failing.

### No lint suppression — ABSOLUTE
- You MUST NOT add `@SuppressLint`, `//noinspection`, `@SuppressWarnings`, new `lint { disable … }`
  entries, or a lint baseline to make findings disappear. FIX the root cause. The ONLY exception is a
  genuine, unavoidable conflict with a documented design decision — which REQUIRES explaining it to
  the user and getting EXPLICIT approval first.

### Standard commands (from repo root; see `project.md`)
- Build: `./gradlew :tunnel:build` (or `:tunnel:assembleRelease`)
- Unit tests: `./gradlew :tunnel:test`
- Lint: `./gradlew :tunnel:lint`
- Publish (release only, NOT routine): `./gradlew :tunnel:publishReleasePublicationToSonatypeUploadRepository`
