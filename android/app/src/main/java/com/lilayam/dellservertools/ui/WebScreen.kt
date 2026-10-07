package com.lilayam.dellservertools.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.ServerType
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.WebConsole
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * A web page inside the app: the real Proxmox web interface (and its noVNC /
 * xterm.js consoles), logged in with the API ticket and pinned to the approved
 * certificate; or, for an iDRAC6, the user's graphical console page.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(profile: ServerProfile, screen: Screen.Web, vms: AppViewModels) {
    val context = LocalContext.current
    val proxmox = vms.proxmox
    val isProxmox = profile.type == ServerType.PROXMOX
    val pinned = remember(profile) { if (isProxmox) proxmox.pinnedFingerprint(profile) else null }
    val isConsole = isProxmox && WebConsole.isConsoleUrl(screen.url)
    var progress by remember { mutableIntStateOf(0) }
    var mobile by rememberSaveable { mutableStateOf(false) }
    var pendingFiles by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        pendingFiles?.onReceiveValue(uris.toTypedArray())
        pendingFiles = null
    }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportZoom(true)
            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    // Only the certificate the user approved for this server is accepted.
                    val fingerprint = certificateFingerprint(error.certificate)
                    if (fingerprint != null && fingerprint == pinned) {
                        handler.proceed()
                    } else {
                        handler.cancel()
                        vms.servers.showMessage("Blocked: the server's certificate doesn't match the trusted one.")
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }

                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams,
                ): Boolean {
                    pendingFiles?.onReceiveValue(null)
                    pendingFiles = filePathCallback
                    pickFiles.launch("*/*")
                    return true
                }
            }

            proxmox.webTicket?.takeIf { isProxmox }?.let { ticket ->
                val cookies = CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                cookies.setCookie(proxmox.baseUrl, "PVEAuthCookie=${Uri.encode(ticket)}; Path=/; Secure")
                cookies.flush()
            }
            if (isConsole && WebConsole.isTerminal(screen.url)) {
                // Android ignores xterm.js focusing its input from script, so a tap raises the keyboard here.
                setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        (view as WebView).focusConsoleInput { found -> if (found) view.showKeyboard() }
                    }
                    false
                }
            }
            loadUrl(screen.url)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            pendingFiles?.onReceiveValue(null)
            webView.destroy()
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else vms.servers.back()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(screen.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = { vms.servers.back() }) { Icon(Icons.Filled.Close, "Close") }
            },
            actions = {
                IconButton(onClick = { if (webView.canGoBack()) webView.goBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Page back")
                }
                IconButton(onClick = { webView.reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
                if (isConsole) {
                    IconButton(
                        onClick = {
                            webView.focusConsoleInput { found ->
                                if (found) {
                                    webView.showKeyboard()
                                } else {
                                    vms.servers.showMessage("The console hasn't loaded yet.")
                                }
                            }
                        },
                        modifier = Modifier.testTag("console-keyboard"),
                    ) { Icon(Icons.Filled.Keyboard, "Keyboard") }
                }
                if (screen.isMainUi) {
                    IconButton(onClick = {
                        mobile = !mobile
                        proxmox.webUiUrl(mobile)?.let(webView::loadUrl)
                    }) {
                        Icon(
                            if (mobile) Icons.Filled.Computer else Icons.Filled.PhoneAndroid,
                            if (mobile) "Desktop layout" else "Mobile layout",
                        )
                    }
                }
                IconButton(onClick = {
                    val url = webView.url ?: screen.url
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in browser") }
            },
        )
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
        if (isConsole) {
            ConsoleKeyRow(onKey = { key -> webView.focusConsoleInput { webView.pressKey(key) } })
        }
    }
}

@Composable
private fun ConsoleKeyRow(onKey: (WebConsole.ConsoleKey) -> Unit) {
    HorizontalDivider()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("console-keys"),
    ) {
        items(WebConsole.keys) { key ->
            OutlinedButton(
                onClick = { onKey(key) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp),
            ) { Text(key.label, fontSize = 13.sp) }
        }
    }
}

/** Gives the WebView focus and focuses the console's input inside the page; [then] gets whether there was one. */
private fun WebView.focusConsoleInput(then: (Boolean) -> Unit) {
    requestFocus()
    evaluateJavascript(WebConsole.FOCUS_INPUT_SCRIPT) { result -> then(result == "true") }
}

private fun WebView.showKeyboard() {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return
    // The page focused its input from script, so the IME connection must be refreshed before showing it.
    imm.restartInput(this)
    imm.showSoftInput(this, 0)
}

/** Presses [key] as real key events, so xterm.js / noVNC handle it exactly like a hardware keyboard. */
private fun WebView.pressKey(key: WebConsole.ConsoleKey) {
    val code = key.key.keyCode
    val meta = if (key.ctrl) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0
    val now = SystemClock.uptimeMillis()
    if (key.ctrl) dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, meta))
    dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
    dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    if (key.ctrl) dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0))
}

private val WebConsole.Key.keyCode: Int
    get() = when (this) {
        WebConsole.Key.ENTER -> KeyEvent.KEYCODE_ENTER
        WebConsole.Key.TAB -> KeyEvent.KEYCODE_TAB
        WebConsole.Key.ESCAPE -> KeyEvent.KEYCODE_ESCAPE
        WebConsole.Key.BACKSPACE -> KeyEvent.KEYCODE_DEL
        WebConsole.Key.UP -> KeyEvent.KEYCODE_DPAD_UP
        WebConsole.Key.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        WebConsole.Key.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        WebConsole.Key.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        WebConsole.Key.C -> KeyEvent.KEYCODE_C
        WebConsole.Key.D -> KeyEvent.KEYCODE_D
        WebConsole.Key.Z -> KeyEvent.KEYCODE_Z
        WebConsole.Key.L -> KeyEvent.KEYCODE_L
    }

private fun certificateFingerprint(certificate: SslCertificate?): String? {
    certificate ?: return null
    val x509: X509Certificate? = if (Build.VERSION.SDK_INT >= 29) {
        certificate.x509Certificate
    } else {
        SslCertificate.saveState(certificate).getByteArray("x509-certificate")?.let { der ->
            runCatching {
                CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
            }.getOrNull()
        }
    }
    return x509?.let { PinnedTls.fingerprint(it) }
}
