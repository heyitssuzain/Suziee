package com.example.playback

import android.content.Intent
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.SuzieeApplication

@UnstableApi
class MediaPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val app = application as SuzieeApplication
            // A session activity is what the system's media notification opens when
            // tapped (and what OEM media controls link to). Without it the notification
            // is a dead button on many devices; with it, tapping returns to the app.
            val builder = MediaSession.Builder(this, app.playerManager.exoPlayer)
            packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
                builder.setSessionActivity(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        launchIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            mediaSession = builder.build()
        } catch (e: Exception) {
            // Never let a session/notification problem take the whole app down.
            Log.e(TAG, "Failed to create MediaSession: ${e.message}", e)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Only tear down the session. The ExoPlayer instance is owned by
        // MusicPlayerManager (Application scope) and must NOT be released here,
        // otherwise the UI would be left holding a released, unusable player.
        try {
            mediaSession?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MediaSession: ${e.message}")
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MediaPlaybackService"
    }
}
