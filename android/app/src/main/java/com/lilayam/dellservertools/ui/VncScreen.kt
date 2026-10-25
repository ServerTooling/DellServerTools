package com.lilayam.dellservertools.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.vnc.Keysyms

/** Keys a phone keyboard can't type, sent as VNC key events. */
private val KEY_ROW: List<Triple<String, String, () -> Pair<Int, IntArray>>> = listOf(
    Triple("Enter", "enter") { Keysyms.ENTER to intArrayOf() },
    Triple("Esc", "esc") { Keysyms.ESCAPE to intArrayOf() },
    Triple("Tab", "tab") { Keysyms.TAB to intArrayOf() },
    Triple("⌫", "backspace") { Keysyms.BACKSPACE to intArrayOf() },
    Triple("←", "left") { Keysyms.LEFT to intArrayOf() },
    Triple("↑", "up") { Keysyms.UP to intArrayOf() },
    Triple("↓", "down") { Keysyms.DOWN to intArrayOf() },
    Triple("→", "right") { Keysyms.RIGHT to intArrayOf() },
    Triple("Del", "delete") { Keysyms.DELETE to intArrayOf() },
    Triple("F2", "f2") { Keysyms.f(2) to intArrayOf() },
    Triple("F8", "f8") { Keysyms.f(8) to intArrayOf() },
    Triple("F12", "f12") { Keysyms.f(12) to intArrayOf() },
    Triple("Shift+F10", "shift-f10") { Keysyms.f(10) to intArrayOf(Keysyms.SHIFT) },
    Triple("Win", "super") { Keysyms.SUPER to intArrayOf() },
    Triple("Ctrl+Alt+Del", "ctrl-alt-del") { Keysyms.DELETE to intArrayOf(Keysyms.CTRL, Keysyms.ALT) },
)

/**
 * A QEMU VM's screen drawn by the app itself (VNC over Proxmox's WebSocket), so it doesn't depend on the
 * noVNC web page. Tap = left click, long press = right click.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VncScreen(profile: ServerProfile, screen: Screen.VmConsole, vms: AppViewModels) {
    val context = LocalContext.current
    val proxmox = vms.proxmox
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableStateOf(0) }
    val session = remember(attempt) {
        VncSession(scope, { proxmox.openVnc(screen.node, screen.vmid) }, proxmox::describeError).also { it.start() }
    }
    DisposableEffect(session) { onDispose { session.close() } }
    val state by session.state.collectAsStateWithLifecycle()
    ReconnectWhenBack(state.dropped) { attempt++ }
    val apiState by proxmox.state.collectAsStateWithLifecycle()
    ForwardMessages(apiState.message, vms) { proxmox.clearMessage() }
    var text by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("Console ${screen.vmid}") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.Filled.Close, "Close") } },
            actions = {
                ConsoleKeysMenu(ConsoleVm(screen.node, screen.vmid), vms)
                IconButton(onClick = { attempt++ }, modifier = Modifier.testTag("vnc-reconnect")) { Icon(Icons.Filled.Refresh, "Reconnect") }
                IconButton(onClick = {
                    proxmox.consoleUrl(screen.node, GuestType.QEMU, screen.vmid, screen.name)?.let { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }
                }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open the web console in the browser") }
            },
        )
        BusyBar(apiState.busy)
        Text(
            if (state.connected) "${state.status} · ${state.width}×${state.height}" else state.status,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("vnc-status"),
        )
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val frame = state.frame
            Canvas(
                Modifier.fillMaxSize().testTag("vnc-screen").pointerInput(state.width, state.height) {
                    fun toRemote(p: Offset): Pair<Int, Int>? {
                        val (w, h) = state.width to state.height
                        if (w <= 0 || h <= 0) return null
                        val scale = minOf(size.width / w.toFloat(), size.height / h.toFloat())
                        val left = (size.width - w * scale) / 2
                        val top = (size.height - h * scale) / 2
                        val x = ((p.x - left) / scale).toInt()
                        val y = ((p.y - top) / scale).toInt()
                        return if (x in 0 until w && y in 0 until h) x to y else null
                    }
                    detectTapGestures(
                        onTap = { p -> toRemote(p)?.let { (x, y) -> session.click(x, y) } },
                        onLongPress = { p -> toRemote(p)?.let { (x, y) -> session.click(x, y, button = 4) } },
                    )
                },
            ) {
                frame // redraw on every update
                val bitmap = session.bitmap ?: return@Canvas
                val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                val w = (bitmap.width * scale).toInt()
                val h = (bitmap.height * scale).toInt()
                drawImage(
                    bitmap.asImageBitmap(),
                    dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                    dstSize = IntSize(w, h),
                )
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KEY_ROW.forEach { (label, tag, key) ->
                OutlinedButton(
                    onClick = { key().let { (keysym, mods) -> session.tap(keysym, *mods) } },
                    enabled = state.connected,
                    modifier = Modifier.testTag("vnc-key-$tag"),
                ) { Text(label) }
            }
        }
        Row(Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            fun sendText() {
                if (text.isNotEmpty()) session.type(text)
                text = ""
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Type into the VM") },
                singleLine = true,
                enabled = state.connected,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { sendText() }),
                modifier = Modifier.weight(1f).testTag("vnc-type"),
            )
            IconButton(onClick = { sendText() }, enabled = state.connected, modifier = Modifier.testTag("vnc-send")) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send text")
            }
        }
    }
}
