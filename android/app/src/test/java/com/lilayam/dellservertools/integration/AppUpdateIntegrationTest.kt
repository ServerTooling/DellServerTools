package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.update.AppUpdate
import com.lilayam.dellservertools.testing.FakeGitHubReleases
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** [AppUpdate] against a fake GitHub releases API over HTTP. */
class AppUpdateIntegrationTest {
    private lateinit var github: FakeGitHubReleases

    @BeforeEach
    fun setUp() {
        github = FakeGitHubReleases()
    }

    @AfterEach
    fun tearDown() = github.close()

    @Test
    fun `finds a newer build and asks GitHub with a User-Agent`() {
        github.versionCode = 105
        val release = AppUpdate.checkForUpdate(104, github.url())
        assertNotNull(release)
        assertEquals(105, release!!.versionCode)
        assertEquals(github.url("/download/apk"), release.apkUrl)

        val request = github.requests.single()
        assertEquals(FakeGitHubReleases.LATEST_PATH, request.path)
        assertEquals("DellServerTools", request.getHeader("User-Agent"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
    }

    @Test
    fun `no update when the installed build is current or newer, or nothing is published`() {
        github.versionCode = 105
        assertNull(AppUpdate.checkForUpdate(105, github.url()))
        assertNull(AppUpdate.checkForUpdate(200, github.url()))
        github.versionCode = null
        assertNull(AppUpdate.checkForUpdate(1, github.url()))
    }

    @Test
    fun `server errors are reported`() {
        assertThrows<IOException> { AppUpdate.checkForUpdate(1, github.url("/error")) }
    }

    @Test
    fun `downloads the APK through the redirect and reports progress`() {
        val release = AppUpdate.checkForUpdate(1, github.url())!!
        val dir = Files.createTempDirectory("update").toFile()
        val apk = File(dir, "updates/DellServerTools.apk")
        val progress = mutableListOf<Float>()
        AppUpdate.download(release, apk) { progress += it }

        assertArrayEquals(github.apkBytes, apk.readBytes())
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertFalse(File(apk.path + ".part").exists())
        dir.deleteRecursively()
    }
}
