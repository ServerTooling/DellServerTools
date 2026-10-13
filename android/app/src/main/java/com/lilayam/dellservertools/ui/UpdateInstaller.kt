package com.lilayam.dellservertools.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded update to the system installer, which asks the user to confirm. The app
 * doesn't install it through its own PackageInstaller session: a sideloaded app that installed
 * itself is then an "unknown source" installer, and Android (seen on Samsung One UI) refuses its
 * next self-update with INSTALL_FAILED_ABORTED even after the user confirms. Android only accepts
 * the APK when it is signed with the installed app's key.
 */
object UpdateInstaller {
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
