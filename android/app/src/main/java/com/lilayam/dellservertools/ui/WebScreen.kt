package com.lilayam.dellservertools.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.ServerType
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.VmKeys
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * A web page inside the app: the real Proxmox web interface (and its noVNC /
 * xterm.js consoles), logged in with the API ticket and pinned to the approved
 * certificate; or, for an iDRAC6, the user's graphical console page.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(profile: ServerProfile, screen: Screen.Web, vms: AppViewModels) {
    val context = LocalContext.current
    val proxmox = vms.proxmox
    val isProxmox = profile.type == ServerType.PROXMOX
    val pinned = remember(profile) { if (isProxmox) proxmox.pinnedFingerprint(profile) else null }
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
            // Load once the view is laid out: a WebView loading while detached has a 0x0 window, and noVNC sizes
            // (scales) the console from the window it sees when the framebuffer arrives.
            addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                    if (r - l > 0 && b - t > 0) {
                        removeOnLayoutChangeListener(this)
                        loadUrl(screen.url)
                    }
                }
            })
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
                screen.consoleVm?.let { ConsoleKeysMenu(it, vms) }
                IconButton(onClick = { webView.reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
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
        if (screen.consoleVm != null) {
            val state by proxmox.state.collectAsStateWithLifecycle()
            ForwardMessages(state.message, vms) { proxmox.clearMessage() }
            BusyBar(state.busy)
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

/** Keys pressed through Proxmox's API, which work whatever the console page does with the phone keyboard. */
@Composable
internal fun ConsoleKeysMenu(target: ConsoleVm, vms: AppViewModels) {
    var open by remember { mutableStateOf(false) }
    var confirmBoot by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("console-keys")) { Icon(Icons.Filled.Keyboard, "Send key") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            VmKeys.keys.forEach { key ->
                DropdownMenuItem(
                    text = { Text(key.label) },
                    onClick = {
                        open = false
                        vms.proxmox.sendKey(target.node, target.vmid, key)
                    },
                    modifier = Modifier.testTag("console-key-${key.name}"),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Reset and boot from CD") },
                onClick = {
                    open = false
                    confirmBoot = true
                },
                modifier = Modifier.testTag("console-boot-cd"),
            )
        }
    }
    if (confirmBoot) {
        ConfirmDialog(
            title = "Reset VM ${target.vmid} and boot from CD?",
            text = "Resets the VM (like pressing its reset button) and presses Enter for " +
                "${VmKeys.BOOT_PRESS_WINDOW_MS / 1000} seconds, which answers \"Press any key to boot from CD or DVD\". " +
                "The CD drive must be in the boot order.",
            confirmLabel = "Reset",
            onConfirm = {
                confirmBoot = false
                vms.proxmox.resetAndBootFromCd(target.node, target.vmid)
            },
            onDismiss = { confirmBoot = false },
        )
    }
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
