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
            mediaSession = MediaSession.Builder(this, app.playerManager.exoPlayer).build()
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
