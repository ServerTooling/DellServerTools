# PC console launcher

Helpers for running the **iDRAC6 Virtual Console** (the real keyboard-and-mouse
console, including BIOS / F1 / F2 / Ctrl+Alt+Del) from a PC, for the times the
phone app can't help — above all when the server is halted at a BIOS prompt
before any OS or serial console is available.

The phone app shows the iDRAC's screen and can send keys over the serial
console, but the serial console only reaches the BIOS once *Serial Communication
→ Console Redirection via COM2* is enabled — and you can't enable that while
you're stuck at the F1 prompt. So you need the graphical console once, from a PC.

> These tools contain **no Dell software**. They download Dell's viewer from
> your own iDRAC at run time, using the addresses inside your `viewer.jnlp`.

## Windows: run it directly on the PC

[`windows/idrac6-console.ps1`](windows/idrac6-console.ps1) (double-click
[`windows/Run-iDRAC6-Console.bat`](windows/Run-iDRAC6-Console.bat)) runs the
console on the PC itself, so you press F1 on the PC's own keyboard.

1. In the iDRAC web page, open the **Virtual Console** once so your browser
   downloads a fresh `viewer.jnlp`. Its login tokens expire after a few minutes,
   so get a new one right before you run this.
2. Put that `viewer.jnlp` in the `windows` folder.
3. Double-click **Run-iDRAC6-Console.bat**.
4. The console window opens. Press **F1** (or **F2** for BIOS).

The script downloads a private Java 8 runtime (Adoptium Temurin 8) and Dell's
viewer into a `runtime` folder beside it. Your system Java and trust store are
not touched. The old TLS and ciphers the iDRAC6 needs are re-enabled only for
that private runtime (`-Djava.security.properties==`), scoped to this one tool
talking to your iDRAC on your LAN.

### Fix it so you never get stuck again

Once the console is up and you're in the BIOS (**F2**):

- **Serial Communication → On with Console Redirection via COM2** (failsafe baud
  115200, Redirection After Boot: Enabled). The phone app's F1/F2/F12 buttons and
  serial console then work on BIOS and boot screens — no PC needed next time.
- **Miscellaneous Settings → F1/F2 Prompt on Error → Disabled**. The server then
  boots past minor errors instead of halting.

Also check the iDRAC **Event log** (`racadm getsel`, or the app's button) to see
what tripped the prompt — on an R710 a dead CMOS battery (CR2032) is common.

## Any OS: a permanent phone bridge (Docker)

To reach the graphical console from the phone whenever you want, run Dell's
viewer headless on an **always-on x86 computer** (not the server itself — it
disappears when the server reboots) and expose it as a web page. The community
image [`domistyle/idrac6`](https://github.com/DomiStyle/docker-idrac6) does this:

```bash
docker run -d --restart unless-stopped --name idrac6 \
  -p 5800:5800 \
  -e IDRAC_HOST=<your idrac ip> \
  -e IDRAC_USER=<your idrac user> \
  -e IDRAC_PASSWORD=<your idrac password> \
  domistyle/idrac6
```

Then in the phone app, edit the iDRAC server and set **Graphical console URL** to
`http://<that computer>:5800`. A button on the screen view opens it, with full
keyboard and mouse.

> Only do this for a server on a trusted network. The bridge holds iDRAC
> credentials and exposes the console to anyone who can reach that port.
