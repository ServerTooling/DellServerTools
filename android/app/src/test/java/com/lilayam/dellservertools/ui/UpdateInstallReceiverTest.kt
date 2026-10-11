package com.lilayam.dellservertools.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The installer's callback: confirmation, success and failure. Runs under Robolectric. */
@RunWith(AndroidJUnit4::class)
class UpdateInstallReceiverTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun deliver(status: Int, extras: Intent.() -> Unit = {}) =
        UpdateInstallReceiver().onReceive(app, Intent().putExtra(PackageInstaller.EXTRA_STATUS, status).apply(extras))

    @Test
    fun opensTheSystemConfirmationWhenAndroidAsksTheUser() {
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        deliver(PackageInstaller.STATUS_PENDING_USER_ACTION) { putExtra(Intent.EXTRA_INTENT, confirm) }

        val started = shadowOf(app).nextStartedActivity
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", started.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun startsNothingOnSuccessOrFailure() {
        deliver(PackageInstaller.STATUS_SUCCESS)
        deliver(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
            putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "signatures do not match")
        }
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
