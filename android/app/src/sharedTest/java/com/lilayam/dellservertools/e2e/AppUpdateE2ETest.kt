package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilayam.dellservertools.core.update.AppUpdate
import com.lilayam.dellservertools.testing.FakeGitHubReleases
import com.lilayam.dellservertools.ui.UpdateViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateE2ETest : E2ETest() {
    private lateinit var github: FakeGitHubReleases

    @Before
    fun startGitHub() {
        github = FakeGitHubReleases()
        UpdateViewModel.latestReleaseUrl = github.url()
    }

    @After
    fun stopGitHub() {
        UpdateViewModel.latestReleaseUrl = AppUpdate.LATEST_RELEASE_URL
        github.close()
    }

    @Test
    fun checkingFromAboutShowsAnAvailableUpdate() {
        github.versionCode = 1000
        launch()
        waitForText("Add your iDRAC6", substring = true)
        // Debug builds never check on their own.
        compose.onNodeWithTag("update-card").assertDoesNotExist()

        compose.onNodeWithContentDescription("Help").performClick()
        compose.onNodeWithTag("check-update").performClick()
        compose.onNodeWithText("OK").performClick()

        waitForText("Update available: build 1000")
        compose.onNodeWithTag("update-install").assertExists()
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithTag("update-card").assertDoesNotExist()
    }

    @Test
    fun checkingWhenCurrentSaysUpToDate() {
        github.versionCode = 1
        launch()
        waitForText("Add your iDRAC6", substring = true)

        compose.onNodeWithContentDescription("Help").performClick()
        compose.onNodeWithTag("check-update").performClick()
        compose.onNodeWithText("OK").performClick()

        waitForText("The app is up to date")
        compose.onNodeWithTag("update-card").assertDoesNotExist()
    }
}
