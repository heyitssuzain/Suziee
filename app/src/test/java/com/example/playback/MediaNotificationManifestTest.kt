package com.example.playback

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the Android-side contract that the media notification/lock-screen surface relies
 * on: the service must be the Media3 session service, exported for system controllers, and
 * carry the foreground-service type plus the notification permission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaNotificationManifestTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `media session service is exported with media playback foreground type`() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, SuzieeMediaService::class.java),
            PackageManager.GET_META_DATA
        )

        assertTrue(MediaSessionService::class.java.isAssignableFrom(SuzieeMediaService::class.java))
        assertTrue(info.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    @Test
    fun `notification and media playback permissions are declared`() {
        val requested = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty().toSet()

        assertTrue("android.permission.POST_NOTIFICATIONS" in requested)
        assertTrue("android.permission.FOREGROUND_SERVICE" in requested)
        assertTrue("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" in requested)
        assertTrue("android.permission.WAKE_LOCK" in requested)
    }
}
