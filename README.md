# Dell Server Tools

An Android app for managing a home-lab Dell PowerEdge server and the
[Proxmox VE](https://www.proxmox.com/en/proxmox-virtual-environment) host running on it, from your phone.
No Java and no PC required.

## Features

### iDRAC6 (PowerEdge R610 / R710 / R910 and similar)

The iDRAC6 Virtual Console is a Java Web Start applet (`viewer.jnlp`), and modern Java no longer runs it.
This app talks to the iDRAC over **SSH** instead:

- **Server screen**: the iDRAC's "Virtual Console Preview" (the picture on the iDRAC home page), refreshed
  every few seconds, with pinch-to-zoom. Shows BIOS, boot and OS screens. It's a picture: to type, use the
  serial console below or a full graphical console (see [Graphical console](#graphical-console-optional)).
- **Power control**: power status, power on, graceful shutdown, power off, power cycle, hard reset
  (each asks for confirmation).
- **Health**: system info, System Event Log, iDRAC log, Serial Over LAN settings.
- **Server text console (Serial Over LAN)**: `console com2` attaches you to the server's serial console,
  so you can watch it boot, enter the BIOS (F2/F10/F11/F12 buttons included), and log in to the OS
  and run commands. Ctrl+\ goes back to the iDRAC prompt.
- **Any `racadm` command** from a built-in terminal.
- **Import `viewer.jnlp`**: open or share the file with the app and it fills in the iDRAC address and username.

> The interactive graphical Java KVM viewer (Avocent protocol) is **not** reimplemented. To see the screen,
> use the screen preview; to type, use Serial Over LAN, the SSH shell / web UI of the OS itself (for example
> Proxmox, below), or the optional graphical console.

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

**Server console is blank?** "Server console" (`console com2`) connects you to the server's serial port COM2.
It stays blank, and what you type doesn't echo, until something on the server uses that port:

1. **Login prompt, no reboot**: SOL must be on (`racadm getconfig -g cfgIpmiSol` shows `cfgIpmiSolEnable=1`; if not,
   run `racadm config -g cfgIpmiSol -o cfgIpmiSolEnable 1` and `... -o cfgIpmiSolBaudRate 115200`). Then on the
   server's OS (e.g. the Proxmox SSH shell in this app → *Enable iDRAC console login*) run
   `systemctl enable --now serial-getty@ttyS1`. Open *Server console* again and press Enter: `login:` appears.
2. **Linux boot messages**: add `console=tty0 console=ttyS1,115200n8` to the kernel command line
   (`/etc/default/grub` → `GRUB_CMDLINE_LINUX_DEFAULT`, then `update-grub`; on systemd-boot / ZFS installs edit
   `/etc/kernel/cmdline` and run `proxmox-boot-tool refresh`) and reboot.
3. **BIOS / POST screens**: BIOS (F2) → **Serial Communication**: *On with Console Redirection via COM2*,
   *Redirection After Boot*: Enabled, *Failsafe Baud Rate*: 115200. This needs a monitor or the graphical console once.

### Graphical console (optional)

The interactive iDRAC6 console only exists as an old Java program. You can still use it from the phone by
running it in Docker on an always-on x86 computer **other than the server itself** (otherwise it disappears when
the server reboots), for example with the community image `domistyle/idrac6`:

```bash
docker run -d --name idrac6 -p 5800:5800 \
  -e IDRAC_HOST=<idrac ip> -e IDRAC_USER=<user> -e IDRAC_PASSWORD=<password> \
  domistyle/idrac6
```

Then edit the iDRAC in the app and set **Graphical console URL** to `http://<that computer>:5800`. A button on the
screen preview opens it inside the app (full keyboard and mouse, including the BIOS).

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
| Integration | `app/src/test/.../integration` | iDRAC screen preview client against a fake iDRAC web interface (login, ST2 token, session expiry, too many sessions); Proxmox client against a fake HTTPS Proxmox API (self-signed cert pinning, login, CSRF, ticket renewal, API tokens, tasks, snapshots); SSH against an in-process server that only speaks the iDRAC6's legacy algorithms (trust on first use, racadm, `console com2` and Ctrl+\, bad password, changed host key) | `./gradlew testDebugUnitTest` |
| End-to-end | `app/src/sharedTest/.../e2e` | The real app driven through its UI: add/edit/delete servers, `viewer.jnlp` import, password prompt and connection errors, the iDRAC screen preview (certificate approval, image, logout), and a full Proxmox flow (certificate approval, overview, starting a VM and following its task, tasks and node screens) against fake servers running in the test | On the JVM with Robolectric: `./gradlew testDebugUnitTest`. On a device or emulator: `./gradlew connectedDebugAndroidTest` |

The fakes (Proxmox API, iDRAC web interface) and the end-to-end tests live in `app/src/sharedTest`, which is part
of both the JVM tests and the on-device tests. GitHub Actions runs everything on every push, including the
end-to-end tests on an Android emulator ([android.yml](.github/workflows/android.yml)); the debug APK is attached
to each run as an artifact.

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
