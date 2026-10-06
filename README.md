# Dell Server Tools

An Android app for managing a home-lab Dell PowerEdge server and the
[Proxmox VE](https://www.proxmox.com/en/proxmox-virtual-environment) host running on it, from your phone.
No Java and no PC required.

## Features

### iDRAC6 (PowerEdge R610 / R710 / R910 and similar)

The iDRAC6 Virtual Console is a Java Web Start applet (`viewer.jnlp`), and modern Java no longer runs it.
This app talks to the iDRAC over **SSH** instead:

- **Power control**: power status, power on, graceful shutdown, power off, power cycle, hard reset
  (each asks for confirmation).
- **Health**: system info, System Event Log, iDRAC log, sensors (temperatures, fans, PSUs).
- **Server text console (Serial Over LAN)**: `console com2` attaches you to the server's serial console,
  so you can watch it boot, enter the BIOS (F2/F10/F11/F12 buttons included), and log in to the OS
  and run commands. Ctrl+\ goes back to the iDRAC prompt.
- **Any `racadm` command** from a built-in terminal.
- **Import `viewer.jnlp`**: open or share the file with the app and it fills in the iDRAC address and username.

> The graphical Java KVM viewer (Avocent protocol) is **not** reimplemented. For OS-level access use
> Serial Over LAN, or the SSH shell / web UI of the OS itself (for example Proxmox, below).

### Proxmox VE

- **Overview** of nodes, VMs, containers and storage with live CPU / RAM / disk usage (auto-refresh).
- **VMs and containers**: start, shut down, reboot, stop, pause/resume, reset; current status and
  configuration.
- **Snapshots**: take, roll back, delete.
- **Backups**: start a snapshot-mode backup to any backup storage.
- **Consoles**: noVNC (VMs) and xterm.js (containers, node shell) open inside the app.
- **Tasks**: recent cluster tasks with live logs; action results are tracked until the task finishes.
- **Node**: status, storage, reboot / shut down.
- **SSH shell** to the Proxmox host, with shortcuts (`qm list`, `pct list`, `pvesm status`, `zpool status`, journal…).
- **Full Proxmox web interface built in**, already logged in, for everything else (creating VMs,
  uploading ISOs, firewall, users, …). Toggle between the desktop and mobile layout.

## Install

Download the latest APK from the [Releases](../../releases) page and open it on your phone
(allow "Install unknown apps" for your browser or file manager when Android asks). Requires Android 8.0+.

## Setup

### iDRAC6

1. Make sure SSH is enabled on the iDRAC: web UI → **Remote Access → Network/Security → Services → SSH**.
2. In the app: **Add iDRAC6**, enter the iDRAC IP, username and password (Dell default: `root` / `calvin`).
   Or **Import iDRAC viewer.jnlp**. The `user=` / `passwd=` numbers in that file are one-time tokens for
   the Java viewer, not your login, so you still enter your real password.
3. The first connection shows the iDRAC's SSH key fingerprint; accept it once.

**Serial Over LAN (one-time setup)** so that "Server console" shows something:

1. BIOS (F2) → **Serial Communication**: *On with Console Redirection via COM2*,
   *Redirection After Boot*: Enabled, *Failsafe Baud Rate*: 115200.
2. From the app's iDRAC terminal:
   ```
   racadm config -g cfgIpmiSol -o cfgIpmiSolEnable 1
   racadm config -g cfgIpmiSol -o cfgIpmiSolBaudRate 115200
   racadm config -g cfgSerial -o cfgSerialSshEnable 1
   ```
3. On Linux / Proxmox, add `console=tty0 console=ttyS1,115200n8` to the kernel command line
   (`/etc/default/grub` → `GRUB_CMDLINE_LINUX_DEFAULT`, then `update-grub`; on systemd-boot / ZFS installs edit
   `/etc/kernel/cmdline` and run `proxmox-boot-tool refresh`) and enable a login prompt:
   `systemctl enable --now serial-getty@ttyS1`.

### Proxmox VE

1. **Add Proxmox VE server**: IP address, web port (8006), username (`root`), realm (*Linux PAM* for `root`),
   password, SSH port (22).
2. The first connection shows the fingerprint of Proxmox's self-signed certificate. Compare it with
   **Datacenter → node → System → Certificates** and accept it once.
3. If your account uses two-factor login, create an API token (**Datacenter → Permissions → API Tokens**,
   untick *Privilege Separation* for full access) and enter its ID (`root@pam!phone`) and secret.
   With a token, consoles and the built-in web UI ask you to log in once in the page.

## Security

- Everything stays on the phone. There is no server, account or analytics.
- Passwords are only stored if you tick *Remember*, encrypted with a key held in the Android Keystore.
  API token secrets are stored the same way. App data is excluded from backups.
- SSH host keys and self-signed TLS certificates are pinned on first use. If one changes, the app warns
  you before connecting.
- The iDRAC6 only supports old SSH algorithms (SHA-1 key exchange, `ssh-rsa`/`ssh-dss`, CBC ciphers).
  They are enabled for iDRAC6 connections only. Keep the iDRAC on a trusted management network.

## Build from source

Needs JDK 17 and the Android SDK (platform 35).

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew` is a small bootstrap script that downloads Gradle 8.10.2 on first use.

### Tests

| Kind | Where | What it covers | Run |
| --- | --- | --- | --- |
| Unit | `app/src/test/.../core` | JNLP parsing, terminal emulator, profiles, Proxmox JSON, commands | `./gradlew testDebugUnitTest` |
| Integration | `app/src/test/.../integration` | Proxmox client against a fake HTTPS Proxmox API (self-signed cert pinning, login, CSRF, ticket renewal, API tokens, tasks, snapshots); SSH against an in-process server that only speaks the iDRAC6's legacy algorithms (trust on first use, racadm, `console com2` and Ctrl+\, bad password, changed host key) | `./gradlew testDebugUnitTest` |
| End-to-end | `app/src/androidTest/.../e2e` | The real app on an emulator: add/edit/delete servers, `viewer.jnlp` import, password prompt and connection errors, and a full Proxmox flow (certificate approval, overview, starting a VM and following its task, tasks and node screens) against a fake Proxmox API on the device | `./gradlew connectedDebugAndroidTest` (needs a device or emulator) |

The fake Proxmox API used by both integration and end-to-end tests lives in `app/src/testShared`.
GitHub Actions runs all three on every push ([android.yml](.github/workflows/android.yml)); the debug APK is
attached to each run as an artifact.

### Releases

Push a tag such as `v1.0.0`; the [Release workflow](.github/workflows/release.yml) builds the APK and attaches
it to a GitHub release. To sign releases with a stable key (so updates install over the previous version),
add these repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Create a key with `keytool -genkeypair -v -keystore release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000`.
Never commit the keystore.

## Project layout

```
android/app/src/main/java/com/lilayam/dellservertools/
  core/            platform-independent logic (unit tested)
    JnlpParser.kt          reads iDRAC viewer.jnlp files
    SshShellSession.kt     SSH shell (JSch) with host key pinning
    TerminalEmulator.kt    small VT100 screen emulator
    QuickCommands.kt       racadm / Proxmox shell shortcuts and special keys
    ServerProfile.kt       saved server model
    proxmox/               Proxmox VE API client, models, TLS pinning
  ui/              Jetpack Compose screens and view models
```

## License

[Apache License 2.0](LICENSE). Uses [JSch](https://github.com/mwiede/jsch) (BSD).

Not affiliated with Dell Inc. or Proxmox Server Solutions GmbH. "iDRAC" and "PowerEdge" are trademarks of
Dell Inc.; "Proxmox" is a trademark of Proxmox Server Solutions GmbH.
