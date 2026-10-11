package com.lilayam.dellservertools.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AppUpdateTest {
    @Test
    fun `reads the version code from the APK asset name`() {
        val release = AppUpdate.parseRelease(
            """
            {"tag_name": "build-123", "html_url": "https://example.com/r",
             "assets": [
               {"name": "notes.txt", "browser_download_url": "https://example.com/notes"},
               {"name": "DellServerTools-123.apk", "browser_download_url": "https://example.com/apk"}
             ]}
            """,
        )
        assertEquals(AppRelease(123, "build-123", "https://example.com/apk", "https://example.com/r"), release)
    }

    @Test
    fun `ignores releases without a versioned APK`() {
        // The first tag release named its APK after the tag and was signed with a throwaway key.
        assertNull(
            AppUpdate.parseRelease(
                """{"tag_name": "v1.0.0", "assets": [{"name": "DellServerTools-v1.0.0.apk", "browser_download_url": "x"}]}""",
            ),
        )
        assertNull(AppUpdate.parseRelease("""{"tag_name": "build-1"}"""))
    }
}
