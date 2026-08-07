# Kotlin Rules — ABSOLUTE RULES

These rules govern the **`:ui` module** — the Kotlin Android application at `ui/src/main/java`
(`com.wireguard.android.*`: `activity`, `fragment`, `model`, `viewmodel`, `configStore`,
`databinding`, `preference`, `updater`, `util`, `widget`). They are **VERY STRICT and ABSOLUTELY
NON-NEGOTIABLE**. Android build/SDK/lint/signing concerns live in `android.md`; the `:tunnel` Java
library in `java.md`; the Go shim in `go.md`; project context in `project.md`.

## Scope in THIS repository — READ FIRST

- `:ui` is a **Kotlin** Android application (`com.android.application`) that depends on `:tunnel`.
  Kotlin comes from **AGP built-in Kotlin (AGP 9.0+)** — there is NO Kotlin Gradle plugin (see
  `android.md`).
- It uses **DataBinding + ViewBinding**, **Kotlin coroutines**, and **Jetpack Preferences
  DataStore**. KAPT (`com.android.legacy-kapt`) generates the DataBinding `BR`/`*Binding` classes.
- The app has **NO DI framework** (no Hilt/Koin) and **NO unit/UI test harness** today — see
  Testability and Testing below. You MUST work within these existing patterns, not replace them.

## 1) Architecture & Idioms — ABSOLUTE RULES

### Idioms first
- You MUST follow the official Kotlin coding conventions and the Android Kotlin style guide, and keep
  code consistent with the EXISTING `:ui` style (it is the reference).
- You MUST prefer **simplicity over cleverness**; clear is better than clever.
- You MUST prefer **immutability**: `val` over `var`, read-only collections, and immutable data
  holders. Use `data class` for value holders.
- You MUST respect Kotlin **null-safety**: NO `!!` except where non-nullity is provable and
  commented; prefer `?.`, `?:`, `requireNotNull(x) { "…" }`, and early returns.
- You MUST keep files and functions small and cohesive. `util/` is for narrow, well-named helpers
  (e.g. `Extensions.kt`); you MUST NOT turn it into a grab-bag.
- Stateless singletons are Kotlin `object`s (as `UserKnobs`, `AdminKnobs`, `TunnelImporter`); use
  that idiom rather than companion-object statics for genuinely stateless utilities.

### Composition root & dependency wiring
- **`Application` is the composition root** and the app uses a **service-locator** pattern: singletons
  (`Backend`, `TunnelManager`, `RootShell`, `ToolsInstaller`, the Preferences `DataStore`, the app
  `CoroutineScope`) are built in `Application.onCreate()` and exposed via the `Application` companion
  accessors; `ConfigStore` (`FileConfigStore`) is built there too but **injected into
  `TunnelManager`**, not surfaced through an accessor. New app-wide dependencies MUST be constructed
  there and exposed the same way — you MUST NOT scatter new global singletons or `object`-held
  mutable state, and you MUST NOT add a DI framework without user approval.
- The **backend is dispatched per tunnel config** by `DispatchingBackend` (built in
  `Application.determineBackend()`): a config with ANY websocket/wstunnel peer MUST use `GoBackend`;
  a pure-UDP config uses the classic selection (`WgQuickBackend` when the kernel module is enabled
  AND present, else `GoBackend`). All tunnel control goes through `TunnelManager` → `Backend` — you
  MUST NOT bypass it.
- Async-ready singletons use **`CompletableDeferred`** (`getBackend()`/`getTunnels()` suspend until
  ready). You MUST keep this non-blocking-init idiom; do NOT force these onto the main thread.

### Coroutines & concurrency — ABSOLUTE
- You MUST use **structured concurrency**. Launch only from a real scope: the app-wide
  `applicationScope` (`Application`'s `CoroutineScope(Job() + Dispatchers.Main.immediate)`, available
  via the `Any.applicationScope` extension), a `lifecycleScope`, or a `viewModelScope`. You MUST
  NEVER use `GlobalScope`, and you MUST NEVER fire-and-forget a coroutine that can leak.
- You MUST dispatch correctly: **`Dispatchers.IO`** for disk / `ConfigStore` / `Backend` calls,
  **`Dispatchers.Main.immediate`** for UI and observable-state mutation (the existing `TunnelManager`
  pattern hops explicitly between them). UI/observable state MUST be confined to the main dispatcher.
- Use **`SupervisorJob`** for independent parallel work (as `restoreState`/import do) so one failure
  does not cancel siblings. Expose streams as **`Flow`/`StateFlow`** (as `UserKnobs` and
  `Updater.state` do); expose one-shot async work as `suspend` functions.
- You MUST AVOID `runBlocking` on the main thread. It exists today ONLY in two deliberate spots —
  `PreferencesPreferenceDataStore`'s synchronous `PreferenceDataStore` bridge, and the pre-Android-Q
  night-mode init in `Application.onCreate` — do NOT add new main-thread blocking.

### DataBinding & UI state
- Observable UI models extend `BaseObservable`, expose `@get:Bindable`/`@Bindable` properties, and
  call `notifyPropertyChanged(BR.x)` on change (as `ObservableTunnel`, `InterfaceProxy`, `PeerProxy`,
  `TunnelManager` do). Editable UI state goes through the **`ConfigProxy`/`InterfaceProxy`/`PeerProxy`**
  layer, which wraps the **immutable** `:tunnel` `Config`/`Interface`/`Peer` and rebuilds them via
  `resolve()`. You MUST keep this proxy boundary — the UI edits proxies, never the immutable model.
- Custom binding logic lives in `databinding/BindingAdapters.kt` (`@BindingAdapter`); list UIs use
  `ObservableKeyedArrayList`/`ObservableSortedKeyedArrayList` +
  `ObservableKeyedRecyclerViewAdapter`. Prefer these over ad-hoc `findViewById`/adapter code.
- You MUST use generated ViewBinding/DataBinding types; you MUST NOT reintroduce `findViewById`.

## 2) Coding Standards — ABSOLUTE RULES

### Error handling
- You MUST catch **specific** exceptions and surface them to the user through the existing channels
  (`ErrorMessages`, snackbars, dialogs) — NOT bare `catch (e: Exception)` that hides failures. The
  existing best-effort `catch`es (e.g. tolerating a single bad import entry while continuing) are the
  ONLY acceptable "ignore" pattern, and MUST remain scoped and intentional.
- You MUST NOT swallow cancellation: never catch `CancellationException` without rethrowing.

### Logging & secrets
- Use `android.util.Log` with a `WireGuard/<Component>` tag. You MUST NEVER log private keys,
  preshared keys, or any key material — and MUST keep the biometric/`FLAG_SECURE` gating around
  private-key reveal and config export intact.

### Resources, i18n & platform
- User-facing strings MUST be resources (never hardcoded); translations are Crowdin-managed
  (`MissingTranslation` is a warning — do not "fix" it by inlining English). Respect
  `AdminKnobs`/`UserKnobs` gates (managed restrictions, kernel-module toggle, remote-control intents,
  restore-on-boot) — do NOT bypass a knob.
- Settings are persisted through **Preferences DataStore** via `UserKnobs` (typed `Preferences.Key`
  + `Flow` read + `suspend set`). New settings MUST follow that pattern; you MUST NOT introduce
  `SharedPreferences` directly.

### Dependencies
- All dependencies come from the version catalog (`libs.versions.toml`); check for the latest stable
  before adding/bumping (see `android.md`). New libraries in `:ui` need justification.

## 3) Testing Rules — ABSOLUTE RULES

- **Current reality:** the `:ui` module has **NO** unit or instrumentation tests, and there is **NO**
  Robolectric, Espresso, or Mockito. The repo's test framework is **JUnit 4**, and the only tests
  live in `:tunnel` (`./gradlew :tunnel:test`). You MUST NOT pretend a `:ui` test harness exists.
- **Testability is still MANDATORY.** You MUST write `:ui` logic to be testable: keep decision logic
  in pure functions / proxies / plain classes free of Android framework calls, and push reusable,
  device-independent logic down into `:tunnel` (where JUnit tests are required — see `java.md`).
- **Adding a `:ui` test harness** (Robolectric, instrumentation/Espresso, coroutine-test, a mocking
  library) is a **tooling decision that REQUIRES the user's explicit approval** — it changes the
  project's dependency and CI surface. Until then, do NOT add `:ui` tests that need such a harness.
- IF/when a harness is approved: use **JUnit 4** (the existing framework) with Arrange-Act-Assert and
  descriptive `method_scenario` names; use `kotlinx-coroutines-test` for coroutine logic; tests MUST
  be fast, offline, and MUST NOT require a device, root, or the network.

## 4) Quality Gates — ABSOLUTE RULES

### Definition of Done
A `:ui` change is DONE **ONLY** if ALL are true:

- `./gradlew :ui:assembleDebug` builds cleanly (no new Kotlin/Java compiler warnings).
- `./gradlew :ui:lintDebug` has ZERO Lint errors (only the documented `disable`/`warning` settings;
  see `android.md`). R8 keep-rules stay correct for any new reflection/DataBinding surface.
- Any device-independent logic you added is covered by tests in `:tunnel` (or by an approved `:ui`
  harness); the existing `:tunnel:test` suite still passes.
- Coroutines are structured (no `GlobalScope`, no leaks, correct dispatchers); UI state stays on the
  main dispatcher.
- No TODOs, no commented-out dead code, no "temporary hacks", no new globals/DI framework/test
  harness/dependencies beyond what was agreed.

### Fix broken tests / lint — ABSOLUTE
- You MUST fix ANY broken test or Lint error, even if unrelated — finish your change first, then fix
  it immediately. You MUST NEVER leave the build, Lint, or `:tunnel:test` failing.

### No lint suppression — ABSOLUTE
- You MUST NOT add `@SuppressLint`, `//noinspection`, new `lint { disable … }`, or a lint baseline to
  make findings disappear. FIX the root cause. Any genuinely unavoidable suppression REQUIRES user
  approval first (per `agent.md`).

### Standard commands (from repo root; see `project.md`)
- Build: `./gradlew :ui:assembleDebug`
- Lint: `./gradlew :ui:lintDebug`
- Release (signed — see `android.md`): `./gradlew assembleRelease` / `./gradlew bundleRelease`
