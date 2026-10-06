package com.lilayam.dellservertools

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.lilayam.dellservertools.ui.AppViewModels
import com.lilayam.dellservertools.ui.DellServerToolsApp
import com.lilayam.dellservertools.ui.IdracScreenViewModel
import com.lilayam.dellservertools.ui.ProxmoxViewModel
import com.lilayam.dellservertools.ui.ServersViewModel
import com.lilayam.dellservertools.ui.TerminalViewModel

class MainActivity : ComponentActivity() {
    private val servers: ServersViewModel by viewModels()
    private val terminal: TerminalViewModel by viewModels()
    private val proxmox: ProxmoxViewModel by viewModels()
    private val idracScreen: IdracScreenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            DellServerToolsApp(AppViewModels(servers, terminal, proxmox, idracScreen), onExit = ::finish)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** A viewer.jnlp opened with / shared to the app creates an iDRAC6 server entry. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let(servers::importJnlp)
            Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> servers.importJnlp(stream)
                    !text.isNullOrBlank() -> servers.importJnlpText(text)
                }
            }
        }
    }
}
