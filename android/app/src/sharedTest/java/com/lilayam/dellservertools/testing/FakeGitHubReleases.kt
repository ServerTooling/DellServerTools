package com.lilayam.dellservertools.testing

import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/** Fake of GitHub's "latest release" API and its APK download, for the app's self-update. */
class FakeGitHubReleases : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Version code of the published APK; null means the repository has no release (404). */
    @Volatile
    var versionCode: Long? = 1000

    val apkBytes = ByteArray(200_000) { (it % 251).toByte() }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    LATEST_PATH -> versionCode?.let { MockResponse().setBody(releaseJson(it)) }
                        ?: MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}""")
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
    }
}
