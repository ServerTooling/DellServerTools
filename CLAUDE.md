# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app (Kotlin, Jetpack Compose, package `com.lilayam.dellservertools`) for managing a Dell iDRAC6 and the Proxmox VE host on it. Everything runs on the phone; there is no backend. `pc-console/` is a separate Windows PowerShell launcher for Dell's legacy Java iDRAC6 console and is unrelated to the Gradle build.

## Commands

Run from `android/` (needs JDK 17 and Android SDK platform 35; `./gradlew` bootstraps Gradle 8.10.2):

```bash
./gradlew testDebugUnitTest                 # unit + integration + E2E (Robolectric) — what CI gates on
./gradlew assembleDebug                     # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest         # E2E on a device/emulator (manual CI job only)
./gradlew testDebugUnitTest --tests 'com.lilayam.dellservertools.core.proxmox.LxcRequestTest'   # single test class
```

There is no lint task configured. Every push to `main` (and every `v*` tag) is published as a signed GitHub release by `.github/workflows/release.yml`, which the in-app updater (`core/update/AppUpdate`, `ui/UpdateViewModel`) installs from; signing comes from `RELEASE_*` env vars/secrets, never a committed keystore.

## Architecture

- `core/` is platform-independent logic that must stay unit-testable without Android: `JnlpParser`, `TerminalEmulator` (VT100), `SshShellSession` (JSch, host-key pinning), `QuickCommands`, `ServerProfile`. UI holds only wiring, no business logic.
- `core/proxmox/`: `ProxmoxClient` (REST API; login/CSRF/ticket renewal/API tokens), `PinnedTls` (trust-on-first-use pinning of self-signed certs), `ProxmoxModels`, and one request/param builder per operation (`LxcRequest`, `VmRequest`, `GuestEdit`, `CloneRequest`). New Proxmox calls get a builder plus tests asserting the exact API params sent.
- `core/idrac/IdracWebClient`: fetches the iDRAC web console preview image. Other iDRAC functions (racadm, power, SOL `console com2`) go over SSH.
- `ui/`: Compose screens, with `ViewModel`s (`ServersViewModel`, `ProxmoxViewModel`, `TerminalViewModel`, `IdracScreenViewModel`); `Stores.kt` holds persistence (credentials encrypted with Android Keystore); `App.kt` is the navigation root.
- The iDRAC6 needs legacy SSH/TLS algorithms (SHA-1 KEX, `ssh-rsa`/`ssh-dss`, CBC). These are enabled **only** for iDRAC connections, never globally; keep that scoping.

## Testing layout (non-obvious)

- `src/test` — JVM unit tests (`core/`) and integration tests (`integration/`) that run against in-repo fakes over real sockets: `FakeProxmox` (HTTPS via MockWebServer), `FakeIdracWeb`, and an in-process Apache SSHD that speaks only iDRAC6-legacy algorithms. Never test against a live server.
- `src/sharedTest` — fakes (`testing/`) and Compose E2E tests (`e2e/`). This dir is added as a source root to **both** `test` (run under Robolectric, JUnit 4 via vintage engine) and `androidTest` (on device), so code there must work in both. `FakeAndroidKeyStore` stands in for the Keystore on the JVM.
- Unit tests use JUnit 5 (`useJUnitPlatform`); `forkEvery = 1` gives each test class its own JVM because Robolectric UI tests leak state.
- Give interactive widgets a `testTag` so E2E tests don't depend on layout.
- Every behavioral change needs matching tests (unit for pure logic, integration for anything talking to a server, E2E for new screens/flows).

## Conventions

- Kotlin official style, 4-space indent, ~120 cols; comments explain why, not what. No new dependencies without justification.
- Never commit real IPs/hostnames/passwords/tokens or `viewer.jnlp` files (git-ignored; they hold one-time tokens). Use `192.0.2.x` and placeholders in docs and tests.
- Don't log credentials or weaken Keystore storage.
