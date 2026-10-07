# Install on a phone over the network (wireless adb)

Build the debug APK and push it straight to a phone over Wi-Fi / your LAN —
no cable, no GitHub download. Useful for quickly testing a local build.

## One-time phone setup

1. **Settings → Developer options → Wireless debugging → On.**
2. Tap **Pair device with pairing code** — note the `IP:PORT` and the 6-digit code.
3. The main Wireless debugging screen also shows an **IP address & Port** — that's
   the `DEVICE_ID` used to connect (different port from pairing).

## Configure

Copy the template and fill it in (the file is git-ignored):

```bash
cp scripts/.env.example .env     # from the android/ directory
# edit android/.env: DEVICE_ID, and (first time) PAIR_DEVICE_ID + PAIRING_CODE
```

You can also pass them inline instead of a file:
`DEVICE_ID=192.0.2.45:41565 ./scripts/install-phone.sh`.

## Install

```bash
cd android
./gradlew assembleDebug
./scripts/install-phone.sh
```

The script connects (pairing first if needed) and runs `adb install -r`, so it
upgrades an existing install in place — **as long as it was signed with the same
key.** A debug build won't install over a release-signed build (and vice versa);
uninstall the other one first if adb reports a signature mismatch.

Requires `adb` (set `ANDROID_HOME` to your SDK, or have `adb` on `PATH`).
