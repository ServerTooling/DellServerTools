package com.lilayam.dellservertools.testing

import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * Fake of GitHub's "latest release" API, the same release on the website (the fallback when the API is
 * rate limited) and its APK download, for the app's self-update.
 */
class FakeGitHubReleases : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Version code of the published APK; null means the repository has no release (404). */
    @Volatile
    var versionCode: Long? = 1000

    /** Answer API calls with GitHub's rate-limit 403, as when the IP's anonymous quota is spent. */
    @Volatile
    var apiRateLimited = false

    val apkBytes = ByteArray(200_000) { (it % 251).toByte() }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val code = versionCode
                return when (request.path) {
                    LATEST_PATH -> when {
                        apiRateLimited -> MockResponse().setResponseCode(403)
                            .setBody("""{"message":"API rate limit exceeded for 192.0.2.1."}""")
                        code != null -> MockResponse().setBody(releaseJson(code))
                        else -> MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}""")
                    }
                    "$WEB_PATH/latest" -> if (code != null) {
                        MockResponse().setResponseCode(302).setHeader("Location", url("$WEB_PATH/tag/build-$code"))
                    } else {
                        MockResponse().setBody("<html>No releases</html>")
                    }
                    "$WEB_PATH/expanded_assets/build-$code" -> MockResponse().setBody(
                        """<a href="$WEB_PATH/download/build-$code/DellServerTools-$code.apk">APK</a>""" +
                            """<a href="$WEB_PATH/archive/refs/tags/build-$code.zip">Source</a>""",
                    )
                    "$WEB_PATH/download/build-$code/DellServerTools-$code.apk" ->
                        MockResponse().setResponseCode(302).setHeader("Location", url("/storage/apk"))
                    // Like GitHub, the asset link redirects to the storage host.
                    "/download/apk" -> MockResponse().setResponseCode(302).setHeader("Location", url("/storage/apk"))
                    "/storage/apk" -> MockResponse().setBody(Buffer().write(apkBytes))
                    "/error" -> MockResponse().setResponseCode(500)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    fun url(path: String = LATEST_PATH): String = server.url(path).toString()

    fun webUrl(): String = url(WEB_PATH)

    private fun releaseJson(code: Long) = """
        {
          "tag_name": "build-$code",
          "html_url": "https://github.com/ServerTooling/DellServerTools/releases/tag/build-$code",
          "assets": [
            {"name": "SHA256SUMS", "browser_download_url": "${url("/download/sums")}"},
            {"name": "DellServerTools-$code.apk", "browser_download_url": "${url("/download/apk")}"}
          ]
        }
    """.trimIndent()

    override fun close() = server.shutdown()

    companion object {
        const val LATEST_PATH = "/repos/ServerTooling/DellServerTools/releases/latest"
        const val WEB_PATH = "/ServerTooling/DellServerTools/releases"
    }
}
