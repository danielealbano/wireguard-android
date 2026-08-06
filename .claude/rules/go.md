# Go Rules — ABSOLUTE RULES

These rules apply to ANY Go project where this file is present. They are **VERY STRICT and ABSOLUTELY NON-NEGOTIABLE**!
Project-specific details (structure, dependencies, command targets) live in the project-specific rule file (`project.md`).

## Scope in THIS repository — READ FIRST

The ONLY Go in wireguard-android is **`tunnel/tools/libwg-go`**: a small **cgo `c-shared` JNI shim**
(`package main` with `func main() {}` and `//export`ed functions in `api-android.go`) that wraps the
upstream userspace WireGuard core `golang.zx2c4.com/wireguard`. It is **cross-compiled for Android**
into `libwg-go.so` by `tunnel/tools/libwg-go/Makefile`, which is invoked by CMake through Gradle's
`externalNativeBuild` (`GOOS=android`, `CGO_ENABLED=1`, NDK clang as `CC`, `-buildmode c-shared`).
It is NOT a standalone daemon or service.

Consequences for how these rules apply here:

- **§1 idioms** (small packages, interfaces, composition, error wrapping, concurrency safety) FULLY
  apply to any Go you write or change.
- **Constructor DI, functional options, `context.Context`-first, and env-var config (§1–§2)** are
  rules for full Go programs. The JNI shim's entry points are fixed `//export` functions whose
  inputs arrive as JNI arguments (interface name, TUN fd, UAPI settings string) — NOT `context` or
  env. Do NOT bolt those patterns onto the exported ABI; apply them only if this repo grows genuine
  Go packages.
- **The JNI ABI is a hard contract** (see `project.md`): the `//export` names/signatures in
  `api-android.go` MUST match `jni.c` and the `native` declarations in `GoBackend.java`.
- **The wire protocol and device core live upstream** in `golang.zx2c4.com/wireguard`; do NOT fork
  protocol behavior into the shim.

## 1) Architecture & Idioms — ABSOLUTE RULES

### Go idioms first
- You MUST follow Effective Go, the Go Code Review Comments wiki, and the Go Proverbs.
- You MUST ALWAYS prefer simplicity over cleverness. Clear is better than clever.
- You MUST accept interfaces, return structs.
- You MUST keep packages, functions, and files small and cohesive; you MUST NEVER create "util" or "common" mega-packages.
- You MUST use composition (embedding) rather than deep type hierarchies.
- You MUST export only what consumers need; keep the public API surface minimal.
- You MUST NEVER create APIs that accept nils — it usually means too many things are being done in one place and a refactor / split is required.
- You MUST keep the responsibilities in the code narrow.
- You MUST ALWAYS write code that is testability friendly.

### Interface-first and testability
- You MUST define interfaces at the **consumer** site, not the provider, following Go convention.
- You MUST default to interfaces for components that touch external systems or contain business logic that should be unit tested in isolation.
- You MUST keep interfaces small (1–3 methods). You MUST ALWAYS prefer composing small interfaces over large ones.
- You MUST ALWAYS wrap third-party clients (HTTP, feed, messaging, storage) behind your own interface so you can swap or mock them.

### Constructors and configuration
- You MUST use the functional options pattern for configurable constructors: `func(*T) error` option functions.
- Constructor functions MUST be named `NewXxx(requiredArgs, ...options)`.
- Constructor dependencies MUST be interfaces (or plain values) wherever a boundary exists — accept abstractions, return concretions.

### Dependency injection
- You MUST pass dependencies explicitly via constructor parameters or functional options.
- You MUST NEVER rely on package-level globals or `init()` for wiring dependencies.
- `context.Context` MUST be the first parameter of any function that does I/O or may be cancelled.

### Concurrency and goroutines
You MUST ALWAYS assume the system can run in parallel: multiple requests, multiple goroutines, retries, overlapping operations.

You MUST:
- design for idempotency where appropriate (retries, replays, and duplicate events MUST be safe),
- protect shared mutable state with `sync.Mutex`, `sync.RWMutex`, channels, or `sync/atomic`,
- use `context.Context` for cancellation and timeouts,
- handle retries safely without duplicate side effects,
- give every goroutine a clear shutdown path (via `context.Context` cancellation, channel close, or `sync.WaitGroup`),
- use `errgroup.Group` (from `golang.org/x/sync/errgroup`) for managing groups of goroutines with error propagation,
- NEVER fire-and-forget goroutines in production code,
- NEVER launch goroutines that can leak (you MUST ALWAYS ensure they can be stopped via context or channel close).

## 2) Coding Standards — ABSOLUTE RULES

### Validation
- You MUST ALWAYS validate inputs at the boundary.
- You MUST use struct tags or explicit validation functions.
- You MUST return structured error responses with enough detail for the caller to fix the issue.

### Error handling
- You MUST ALWAYS check and handle errors. You MUST NEVER use `_` to discard errors unless there is a documented justification.
- You MUST wrap errors with context using `fmt.Errorf("operation description: %w", err)`.
- You MUST use sentinel errors (`var ErrNotFound = errors.New(...)`) for errors that callers need to match with `errors.Is`.
- You MUST use custom error types (implementing the `error` interface) when callers need to inspect error details with `errors.As`.
- You MUST NEVER panic in library code. Panics are acceptable only for truly unrecoverable programmer errors in `main` or `init`.
- You MUST return errors, not log-and-continue, unless the error is truly informational.

### Context usage
- `context.Context` MUST be the first parameter of any function that performs I/O, calls external services, or may need cancellation.
- You MUST ALWAYS propagate context through the call chain; you MUST NEVER create a new background context in the middle of a request.
- You MUST use `context.WithTimeout` or `context.WithDeadline` for external calls.

### Logging
- You MUST use the following log levels: `Trace` (fine-grained debug), `Debug` (internal flow), `Info` (business events), `Warn` (recoverable), `Error` (unrecoverable).
- You MUST ALWAYS include identifiers in logs (request ID, entity ID, etc.).
- You MUST NEVER log secrets, tokens, API keys, or PII.
- Errors MUST be actionable: include what failed, which identifiers, and likely next steps.

### Configuration
- You MUST NEVER hardcode secrets or environment-specific values.
- You MUST use environment variables for configuration; parse them with strongly typed structs.
- You MUST validate all required configuration at startup. Fail fast with a clear error message if anything is missing or invalid.
- You MUST use strongly typed config structs, not scattered `os.Getenv` calls throughout the code.

### Modules & dependency management
- You MUST keep `go.mod` clean: run `go mod tidy` after adding or removing dependencies.
- You MUST ALWAYS commit both `go.mod` and `go.sum`.
- You MUST use latest stable versions of dependencies unless an in-use package requires an older release. Before adding something, ALWAYS check if it is the latest version.
- You MUST prefer well-maintained packages with active development.
- You MUST check for known vulnerabilities before adding: `govulncheck ./...`.
- You MUST prefer the Go standard library over third-party packages when feasible.
- The module lives at **`tunnel/tools/libwg-go/go.mod`** (module path `golang.zx2c4.com/wireguard/android`);
  run all `go` commands from that directory. The Go toolchain used for the Android build is **pinned
  in `libwg-go/Makefile`** (`GO_VERSION`), downloaded and patched with
  `goruntime-boottime-over-monotonic.diff`; the `go` directive in `go.mod` is the language floor.
  Both `go.mod` and `go.sum` MUST be committed.
- **DELIVERED (per `project.md`):** the userspace core is the `danielealbano/wireguard-go` fork,
  wired via `replace golang.zx2c4.com/wireguard => github.com/danielealbano/wireguard-go v1.3.0` in
  `go.mod` (module path unchanged; `go` directive `1.26.5`). The shim builds `conn.NewMultiplexBind`
  (UDP + WebSocket) and exports `wgSetFdProtector` (per-dial `VpnService.protect` bridge, via the C
  `wgAndroidProtectFd` upcall) and `wgBumpSockets` (`device.BindUpdate`); the `tunnelHandles` map is
  mutex-guarded. Any bump of the fork pin MUST leave `go mod tidy` with NO diff and `govulncheck`
  clean.

## 3) Testing Rules — ABSOLUTE RULES

All references to "tests" in this document mean automated tests (unit, integration, and e2e) that run during development and in CI/CD pipelines.

> **Project reality (libwg-go):** the JNI shim is a thin cgo boundary with no pure-Go business logic,
> so it currently has **no `*_test.go` files** — its correctness is exercised by the Android build,
> by the app's `:tunnel` JUnit tests (`java.md`), and by upstream `wireguard-go`. The rules below are
> MANDATORY for ANY host-testable Go you add; you MUST NOT add tests that require an Android device,
> the NDK, or a live network. Purely mechanical cgo/`//export` glue that cannot run under host
> `go test` (it needs `GOOS=android`) is exempt from the "tests are mandatory" rule ONLY to the
> extent it genuinely cannot be exercised on the host — extract any testable logic into plain Go and
> test THAT.

### General principles
- Tests are MANDATORY for all changes. There are ZERO exceptions.
- Tests MUST be small, focused, and non-redundant while still covering: happy path, edge cases, failure modes.
- Tests MUST ALWAYS pass.
- Tests MUST NOT depend on execution order.
- Tests MUST clean up after themselves (temp files, in-process servers, test containers).

### Frameworks — ABSOLUTE
- The standard library `testing` package is THE test framework. You MUST prefer the standard library (`t.Errorf`, `t.Fatalf`); use `testify/assert` / `testify/require` ONLY if already present in the module. (`libwg-go` uses stdlib `testing` EXCLUSIVELY and has NO third-party test dependencies — you MUST NOT introduce `testify`.)
- You MUST use **table-driven tests** as the default pattern for functions with multiple input/output cases; each test case MUST have a descriptive `name` field.
- You MUST use `t.Run(tc.name, func(t *testing.T) { ... })` for subtests.
- You MUST follow the **Arrange-Act-Assert** pattern consistently.
- You MUST mark test helpers with `t.Helper()`.
- You MUST name test functions descriptively: `TestServiceName_MethodName_Scenario`.

```go
func TestParseURL_Variants(t *testing.T) {
    tests := []struct {
        name    string
        input   string
        want    string
        wantErr bool
    }{
        {name: "valid https URL", input: "https://example.com", want: "https://example.com", wantErr: false},
        {name: "empty string", input: "", want: "", wantErr: true},
    }

    for _, tc := range tests {
        t.Run(tc.name, func(t *testing.T) {
            got, err := ParseURL(tc.input)
            if (err != nil) != tc.wantErr {
                t.Fatalf("ParseURL(%q) error = %v, wantErr %v", tc.input, err, tc.wantErr)
            }
            if got != tc.want {
                t.Errorf("ParseURL(%q) = %q, want %q", tc.input, got, tc.want)
            }
        })
    }
}
```

### Test organization
- Test files MUST live next to the code they test: `foo.go` → `foo_test.go`.
- You MUST use the `_test` package suffix for black-box tests (e.g., `package foo_test`) to test only the public API.
- You MUST use the same package name only when you need to test unexported internals, and you MUST prefer this sparingly.
- Shared setup MUST be factored into test helpers; copy-pasted setup across test files is FORBIDDEN.

### Unit tests
- Unit tests MUST be fast (no I/O, no network, no external services).
- You MUST use interfaces and dependency injection to mock external dependencies.
- You MUST use `t.Parallel()` for tests that are safe to run concurrently.
- You MUST use `testing/fstest.MapFS` or `os.MkdirTemp` for filesystem-dependent tests.
- You MUST short-circuit with `t.Skip("reason")` or `-short` flag for tests that are too slow for rapid iteration.

### Integration tests
- Integration tests MUST verify that individual components work correctly against real external systems or real protocol surfaces (e.g., `net/http/httptest` servers speaking the real wire format, or in-process protocol harnesses).
- You MUST guard integration tests with the build tag `//go:build integration` at the top of the file.
- **Testcontainers are MANDATORY** when a test needs a real external service (DB, broker, …): it MUST use `testcontainers-go`. You MUST NEVER rely on pre-running Docker Compose services or shared, long-lived test infrastructure. **Documented exception for this repo:** `libwg-go` has NO external-service infrastructure — it is a cgo shim over the local TUN device and UDP sockets, so testcontainers do NOT apply here (see `project.md`).
- You MUST start containers in `TestMain` or in a shared test helper and pass connection details to tests. You MUST use `t.Cleanup` (or `defer container.Terminate(ctx)`) to guarantee teardown.
- Containers MUST be ephemeral and isolated: each test suite gets its own container instance.
- Each integration test MUST set up and tear down its own state (use `t.Cleanup`).
- Integration tests MUST respect `context.Context` timeouts.

### End-to-end (E2E) tests
- E2E tests MUST exercise the full system roundtrip.
- You MUST guard E2E tests with the build tag `//go:build e2e`.
- All required infrastructure MUST be started via `testcontainers-go` (same rules and same documented exception as integration tests). In THIS repo, end-to-end WireGuard behavior is validated by the Android app and by upstream `wireguard-go` — NOT by Go e2e tests inside `libwg-go`.
- E2E tests MUST be idempotent and safe to re-run.
- You MUST use realistic but deterministic test data.

### Race detection
- Tests MUST run with the `-race` flag, locally and in CI: `go test -race ./...`.
- You MUST fix all data races immediately; they are not warnings — they are bugs.

### Mocking
- You MUST use interfaces for all external boundaries so they can be mocked in tests.
- You MUST prefer hand-written mocks (simple struct implementing the interface) for small interfaces.
- You MUST use code generation (`mockgen`, `moq`, or `counterfeiter`) only for interfaces with many methods.
- You MUST NEVER mock what you don't own in unit tests — you MUST wrap third-party clients behind your own interface first.

### Environment variables for tests
- This repo has **NO `.env` for Go tests** and needs none — `libwg-go` has no external-service
  configuration. Any host-testable Go MUST run with a bare `go test` from `tunnel/tools/libwg-go`.
- The Android artifact build's environment (`GOOS=android`, `CGO_ENABLED=1`, the NDK `CC` /
  `SYSROOT` / `TARGET` / `CFLAGS` / `LDFLAGS`, `ANDROID_ARCH_NAME`, `ANDROID_PACKAGE_NAME`,
  `GRADLE_USER_HOME`) is supplied by `libwg-go/Makefile` + CMake — you MUST NOT hardcode, duplicate,
  or second-guess those values.

### Manual testing documentation
- Manual tests are NOT a substitute for automated tests.
- If manual testing steps are necessary, they MUST be clearly labeled as "**Manual Test**" or "**Manual QA Steps**" and documented separately from automated test descriptions.

## 4) Quality Gates — ABSOLUTE RULES

### Definition of Done
A change MUST be considered DONE **ONLY AND ONLY** if ALL are true:

- All relevant automated tests are written AND passing (any host-testable Go you add; see the
  "Project reality" note in §3).
- **ZERO `go vet` / `golangci-lint` findings** when analysis is run in the NDK cross-compile
  environment (see Standard Commands below). For any pure-Go you add, ZERO findings on a plain
  `go vet ./...` / `golangci-lint run`.
- The native library builds without errors or warnings via Gradle (`./gradlew assembleDebug`, which
  drives CMake + `libwg-go/Makefile` for every ABI); the C tools already compile under `-Wall -Werror`.
- `go mod tidy` (run in `tunnel/tools/libwg-go`) produces NO `go.mod`/`go.sum` diff.
- No TODOs, no commented-out dead code, no "temporary hacks".
- Changes are small, readable, and aligned with existing Go patterns.

### Fix broken tests — ABSOLUTE RULE
- You MUST fix ANY broken test, even if unrelated to your changes. Finish your current change first, then fix the broken test immediately.
- You MUST NEVER leave the test suite broken. There are ZERO exceptions.

### Fix broken linting — ABSOLUTE RULE
- You MUST fix ANY linting or formatting error, even if unrelated to your changes. Finish your current change first, then fix the violations immediately.
- You MUST NEVER leave the codebase with linting or formatting violations. There are ZERO exceptions.

### No linting suppression — ABSOLUTE RULE
- You MUST NEVER suppress, silence, or skip linting rules (e.g., `//nolint` directive comments, `exclude`/`exclude-rules` entries in the golangci-lint config, baseline files) to make errors disappear.
- You MUST FIX the root cause of every linting error or warning by adjusting the implementation.
- The ONLY exception is when a linting rule GENUINELY and unavoidably conflicts with the project's documented design decisions. In that case, you MUST explain the conflict to the user and get EXPLICIT approval before adding any suppression. This is NON-NEGOTIABLE.

### Standard build/lint/test commands

Run all Go tooling from **`tunnel/tools/libwg-go/`**. `libwg-go` is a **cgo-for-Android** package —
its cgo includes `<android/log.h>` and links `-llog`, so `go build` / `go vet` / `golangci-lint`
CANNOT compile it on a bare host: they need the **NDK cross-compile environment** (the `GOOS=android`
+ NDK `CC`/`SYSROOT`/`TARGET` that `libwg-go/Makefile` sets). Practically:

- **Native artifact build (authoritative):** `./gradlew assembleDebug` — runs CMake +
  `libwg-go/Makefile` via `externalNativeBuild` for every ABI. This is the primary compile gate.
- **Vet / Lint:** `go vet ./...` and `golangci-lint run` — MUST be run inside the NDK cross-compile
  environment (or accept that a green `assembleDebug` is the compile gate). For any NEW pure-Go
  (no cgo) package you add, they run standalone.
- **Format:** `gofmt -l -w .` / `goimports` (source-only, runs standalone).
- **Host unit tests (any pure-Go you add):** `go test -race ./...`.
- **Tidy:** `go mod tidy` (source-only, runs standalone).
- **Vulnerabilities:** `govulncheck ./...`.

The authoritative command surface for the WHOLE repo is the root Makefile wrapping `./gradlew` and
these Go commands (see `project.md` → Standard Commands; the Makefile itself is ROADMAP).
