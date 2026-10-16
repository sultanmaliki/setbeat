package dev.fitnesstimer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fitnesstimer.timer.AppTimer
import dev.fitnesstimer.ui.MainScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The FULL edition keeps companion mode: the notification listener and audio capture must be present. */
@RunWith(AndroidJUnit4::class)
class EditionFullTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val pm = ctx.packageManager

    @Test fun theNotificationListenerServiceIsDeclaredAndProtected() {
        val info = pm.getServiceInfo(ComponentName(ctx.packageName, "dev.fitnesstimer.nowplaying.NowPlayingListenerService"), 0)
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", info.permission)
        assertTrue(info.exported)
    }

    @Test fun audioCaptureAndNoInternetPermissions() {
        val requested = pm.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.RECORD_AUDIO" in requested)
        assertTrue("android.permission.VIBRATE" in requested) // beat haptics
        assertTrue("android.permission.INTERNET" !in requested)
    }

    @Test fun companionModeIsOnAndTheMenuOffersNowPlaying() {
        assertTrue(ctx.resources.getBoolean(R.bool.companion_enabled))
        AppTimer.init(ctx)
        rule.setContent { MainScreen() }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { down(0, Offset(width - 30f, 30f)); advanceEventTime(50); up(0) }
        rule.waitForIdle()
        rule.onNodeWithText("Now playing (other apps)").assertIsDisplayed()
        rule.onNodeWithText("Choose file").assertIsDisplayed()
    }
}
