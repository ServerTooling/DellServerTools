package com.lilayam.dellservertools.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lilayam.dellservertools.core.update.AppRelease
import com.lilayam.dellservertools.core.update.AppUpdate
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpdateUiState(
    val installedVersion: String = "",
    val checking: Boolean = false,
    val available: AppRelease? = null,
    /** Download progress 0..1, -1 when the size is unknown, null when not downloading. */
    val progress: Float? = null,
    val message: String? = null,
)

/** Checks GitHub for a newer build and hands the downloaded APK to the Android installer. */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val installedCode: Long
    private val _state: MutableStateFlow<UpdateUiState>
    val state: StateFlow<UpdateUiState>

    init {
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        installedCode = PackageInfoCompat.getLongVersionCode(info)
        _state = MutableStateFlow(UpdateUiState(installedVersion = "${info.versionName} ($installedCode)"))
        state = _state.asStateFlow()
        // Debug builds (local installs, tests) are signed with another key and can't take a release
        // update, and tests must never reach GitHub; they only check when asked.
        val debuggable = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) checkForUpdate(manual = false)
    }

    fun checkForUpdate(manual: Boolean = true) {
        if (_state.value.checking || _state.value.progress != null) return
        _state.update { it.copy(checking = true, message = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AppUpdate.checkForUpdate(installedCode, latestReleaseUrl) } }
            _state.update { s ->
                result.fold(
                    onSuccess = { release ->
                        s.copy(
                            checking = false,
                            available = release,
                            message = if (manual && release == null) "The app is up to date" else null,
                        )
                    },
                    // A failed automatic check (offline, rate limited) isn't worth interrupting anyone for.
                    onFailure = { e -> s.copy(checking = false, message = if (manual) "Update check failed: ${e.message}" else null) },
                )
            }
        }
    }

    fun dismiss() = _state.update { it.copy(available = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun downloadAndInstall() {
        val release = _state.value.available ?: return
        if (_state.value.progress != null) return
        _state.update { it.copy(progress = -1f, message = null) }
        val app = getApplication<Application>()
        val apk = File(File(app.cacheDir, "updates"), "DellServerTools.apk")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdate.download(release, apk) { p -> _state.update { it.copy(progress = p) } } }
            }
            _state.update { it.copy(progress = null) }
            result.onSuccess {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
                // Android asks the user to confirm, and to allow installs from this app the first time.
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { app.startActivity(install) }
                    .onFailure { e -> _state.update { it.copy(message = "Could not open the installer: ${e.message}") } }
            }.onFailure { e ->
                _state.update { it.copy(message = "Update download failed: ${e.message}") }
            }
        }
    }

    companion object {
        @VisibleForTesting
        @Volatile
        var latestReleaseUrl: String = AppUpdate.LATEST_RELEASE_URL
    }
}
