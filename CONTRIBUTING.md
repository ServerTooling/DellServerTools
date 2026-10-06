# Contributing to Dell Server Tools

Thanks for your interest in improving Dell Server Tools. This is a small,
home-lab-focused project: an Android app for managing a Dell iDRAC6 and the
Proxmox VE host on it, plus a PC helper for the legacy iDRAC6 console.

## Ground rules

- Be respectful. Assume good faith.
- This project is **not affiliated with Dell or Proxmox**. "iDRAC" and
  "PowerEdge" are trademarks of Dell Inc.; "Proxmox" is a trademark of Proxmox
  Server Solutions GmbH.
- By contributing you agree your contribution is licensed under the repository's
  [Apache-2.0 license](LICENSE).

## Ways to help

- **Report a bug or request a feature:** open an issue. For a bug, include the
  app version, your iDRAC/Proxmox version, what you did, and the exact error
  text or task log.
- **Pick up planned work:** the native-feature roadmap is tracked in issue #1
  and its sub-issues.
- **Send a pull request:** see below.

## Project layout

```
android/                      the Android app (Kotlin, Jetpack Compose)
  app/src/main/java/com/lilayam/dellservertools/
    core/        platform-independent logic (unit tested): JNLP parsing,
                 the VT100 terminal emulator, SSH, Proxmox API client and
                 the request/param builders
    core/proxmox/ the Proxmox REST client, models and request builders
    core/idrac/   the iDRAC web client (screen preview)
    ui/          Jetpack Compose screens and view models
  app/src/test/           JVM unit + integration tests
  app/src/sharedTest/     fakes + end-to-end tests shared by JVM (Robolectric)
                          and on-device runs
  app/src/androidTest/    on-device entry point for the shared E2E tests
pc-console/                   Windows launcher for the iDRAC6 graphical console
```

## Building and testing

You need JDK 17 and the Android SDK (platform 35).

```bash
cd android
./gradlew testDebugUnitTest     # unit + integration + E2E (via Robolectric)
./gradlew assembleDebug         # build the debug APK
./gradlew connectedDebugAndroidTest   # E2E on a connected device/emulator
```

`./gradlew` is a bootstrap script that downloads Gradle on first use.

GitHub Actions runs the unit/integration suite — which includes the Compose
end-to-end tests on the JVM via Robolectric — on every push and pull request;
please make sure it is green. The on-device emulator E2E pass is a manual job
(Actions → Android → *Run workflow*), since the GitHub-hosted emulator is
unreliable to boot.

## Tests are required

Every behavioral change needs matching tests. Follow the existing pattern:

- **Pure logic** (parsing, param building, the terminal emulator) → a plain
  **unit test** under `app/src/test/.../core`.
- **Anything that talks to a server** → an **integration test** that runs
  against the in-repo fakes (`FakeProxmox`, `FakeIdracWeb`, the in-process SSH
  server) over real sockets — never a live server.
- **A new screen or flow** → an **end-to-end test** in `app/src/sharedTest`
  driving the Compose UI against a fake. Give interactive widgets a `testTag`
  so tests can find them without depending on layout.

New Proxmox calls should assert the exact API parameters sent (see the existing
create/edit/delete tests for the style).

## Coding style

- Kotlin official style (`kotlin.code.style=official`); 4-space indent, ~120 col.
- Keep platform-independent logic in `core/` so it can be unit tested without
  Android. UI holds no business logic beyond wiring.
- Match the surrounding code's naming and comment density. Comments explain
  *why*, not *what*.
- No new dependencies without a clear reason in the PR description.

## Security and privacy

- **Never commit secrets or real infrastructure details** — no real IPs,
  hostnames, iDRAC/Proxmox passwords, API tokens, or `viewer.jnlp` files (they
  contain one-time session tokens and are git-ignored). Use `192.0.2.x`
  (TEST-NET) and placeholders like `<your idrac ip>` in docs and tests.
- Credentials in the app are optional and, when saved, encrypted with the
  Android Keystore. Don't add code that logs them or weakens that.
- The iDRAC6 needs legacy TLS/SSH algorithms; those are enabled **only** for
  iDRAC connections, never globally. Keep that scoping.
- Found a vulnerability? Please open an issue asking for a private contact
  rather than posting details publicly.

## Pull request checklist

- [ ] `./gradlew testDebugUnitTest assembleDebug` passes locally.
- [ ] New/changed behavior has unit, integration, and/or E2E tests.
- [ ] No secrets, real IPs/hostnames, or `.jnlp` files added.
- [ ] Commit messages explain the change; the PR description says what and why.
- [ ] Linked to the relevant issue where one exists.
