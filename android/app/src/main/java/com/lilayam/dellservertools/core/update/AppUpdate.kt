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

    /** The same releases on the website, which isn't subject to the API's anonymous rate limit. */
    const val WEB_RELEASES_URL = "https://github.com/ServerTooling/DellServerTools/releases"

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

    /**
     * The latest release if it is newer than [installedVersionCode], else null. Anonymous API calls are
     * limited to 60 an hour per IP address, shared by everything on the same home network (a pve-runners
     * host checking for its own updates used it all up), so when the API refuses, the release is read
     * from the website at [webUrl] instead.
     */
    fun checkForUpdate(
        installedVersionCode: Long,
        url: String = LATEST_RELEASE_URL,
        webUrl: String = WEB_RELEASES_URL,
    ): AppRelease? {
        val conn = open(url)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val release = try {
            when (conn.responseCode) {
                200 -> parseRelease(conn.inputStream.bufferedReader().use { it.readText() })
                // No release published yet.
                404 -> null
                403, 429 -> latestFromWeb(webUrl)
                else -> throw IOException("GitHub answered HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
        return release?.takeIf { it.versionCode > installedVersionCode }
    }

    /**
     * `releases/latest` redirects to the latest release's tag page, and `releases/expanded_assets/<tag>`
     * is the asset list that page loads; the APK's file name carries the version code.
     */
    private fun latestFromWeb(webUrl: String): AppRelease? {
        val latest = open("$webUrl/latest").apply { instanceFollowRedirects = false }
        val location = try {
            when (latest.responseCode) {
                in 300..399 -> latest.getHeaderField("Location")
                // No release published yet: GitHub shows the (empty) release list.
                200, 404 -> return null
                else -> throw IOException("GitHub answered HTTP ${latest.responseCode}")
            }
        } finally {
            latest.disconnect()
        }
        val tag = location?.substringAfter("/releases/tag/", "")?.takeIf { it.isNotEmpty() && "/" !in it }
            ?: throw IOException("Unexpected answer from GitHub")
        val assets = open("$webUrl/expanded_assets/$tag")
        val html = try {
            if (assets.responseCode != 200) throw IOException("GitHub answered HTTP ${assets.responseCode}")
            assets.inputStream.bufferedReader().use { it.readText() }
        } finally {
            assets.disconnect()
        }
        val code = Regex("""/releases/download/${Regex.escape(tag)}/DellServerTools-(\d+)\.apk""")
            .find(html)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return AppRelease(
            versionCode = code,
            tag = tag,
            apkUrl = "$webUrl/download/$tag/DellServerTools-$code.apk",
            pageUrl = "$webUrl/tag/$tag",
        )
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
