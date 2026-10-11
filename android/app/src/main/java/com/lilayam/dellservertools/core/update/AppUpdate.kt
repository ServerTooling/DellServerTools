package com.lilayam.dellservertools.core.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** A published build of the app: the release's APK and the version code it carries. */
data class AppRelease(val versionCode: Long, val tag: String, val apkUrl: String, val pageUrl: String)

/**
 * Self-update from GitHub releases. CI publishes every merge to main as a release with one
 * `DellServerTools-<versionCode>.apk` asset, signed with the same key as the installed app. Android
 * refuses an update signed with another key, so that signature check is what protects the download.
 *
 * Every network call blocks; run it off the main thread.
 */
object AppUpdate {
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/ServerTooling/DellServerTools/releases/latest"

    private val apkName = Regex("""^DellServerTools-(\d+)\.apk$""")

    /** Reads a GitHub "latest release" response; null when it has no APK in the expected form. */
    fun parseRelease(json: String): AppRelease? {
        val release = JSONObject(json)
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val code = apkName.find(asset.optString("name"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            return AppRelease(
                versionCode = code,
                tag = release.optString("tag_name"),
                apkUrl = asset.getString("browser_download_url"),
                pageUrl = release.optString("html_url"),
            )
        }
        return null
    }

    /** The latest release if it is newer than [installedVersionCode], else null. */
    fun checkForUpdate(installedVersionCode: Long, url: String = LATEST_RELEASE_URL): AppRelease? {
        val conn = open(url)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            // No release published yet.
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) throw IOException("GitHub answered HTTP ${conn.responseCode}")
            val release = parseRelease(conn.inputStream.bufferedReader().use { it.readText() }) ?: return null
            return release.takeIf { it.versionCode > installedVersionCode }
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK to [dest], reporting progress as a fraction (or -1 when the size is unknown). */
    fun download(release: AppRelease, dest: File, onProgress: (Float) -> Unit = {}) {
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != 200) throw IOException("Download failed: HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            val partial = File(dest.path + ".part")
            conn.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        onProgress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
            if (total > 0 && partial.length() != total) throw IOException("Download incomplete")
            dest.delete()
            if (!partial.renameTo(dest)) throw IOException("Could not save the update")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            // GitHub's API rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "DellServerTools")
        }
}
