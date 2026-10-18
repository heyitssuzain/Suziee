package com.example.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.SuzieeApplication

@UnstableApi
class MediaPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * The player the MediaSession exposes to system controllers (notification, lock
     * screen, Bluetooth, Android Auto).
     *
     * It is the app's single ExoPlayer wrapped in [QueuePlayer], never a second player.
     */
    private val sessionPlayer: Player by lazy {
        QueuePlayer((application as SuzieeApplication).playerManager)
    }

    override fun onCreate() {
        super.onCreate()
        try {
            // A session activity is what the system's media notification opens when
            // tapped (and what OEM media controls link to). Without it the notification
            // is a dead button on many devices; with it, tapping returns to the app.
            val builder = MediaSession.Builder(this, sessionPlayer)
                .setCallback(sessionCallback)
            packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
                builder.setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            mediaSession = builder.build()
            Log.i(TAG, "MediaSession created - notification/lock-screen controls active")
        } catch (e: Exception) {
            // Never let a session/notification problem take the whole app down.
            Log.e(TAG, "Failed to create MediaSession: ${e.message}", e)
        }
    }

    /**
     * Media3 only offers the "next / previous track" buttons to controllers when the
     * session's player reports those commands as available.
     *
     * [QueuePlayer] does exactly that, and this callback additionally advertises them on
     * the session side so the buttons appear even when the default player command set
     * does not include them. Nothing here creates a second notification or a second
     * player.
     */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val playerCommands = session.player.availableCommands.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }
    }

    /**
     * Extends the underlying player with the queue navigation the app owns.
     *
     * The app deliberately keeps **one** media item loaded at a time - the queue, repeat
     * and auto-advance logic lives in [MusicPlayerManager] - so the raw ExoPlayer reports
     * "no next/previous media item". That is why the media notification and lock screen
     * had no working Next/Previous controls even though Play/Pause worked.
     *
     * Advertising the commands and implementing them here keeps the notification, the
     * lock screen and the in-app UI operating on the same player and the same queue.
     */
    private class QueuePlayer(
        private val manager: MusicPlayerManager
    ) : ForwardingPlayer(manager.exoPlayer) {

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun seekToNext() {
            Log.i(TAG, "Session command: Next")
            manager.playNext()
        }

        override fun seekToNextMediaItem() {
            seekToNext()
        }

        override fun seekToPrevious() {
            Log.i(TAG, "Session command: Previous")
            manager.playPrevious()
        }

        override fun seekToPreviousMediaItem() {
            seekToPrevious()
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
