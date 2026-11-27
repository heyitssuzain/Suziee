package com.example.playback

import com.example.domain.model.Track

/**
 * The small, stable seam between Suziee's existing player and Couple Space.
 *
 * The player never imports the networking layer. It emits intent-level events after a local
 * user action and accepts a remote command through [MusicPlayerManager.applyRemotePlayback].
 * Remote application is guarded inside the player, so a remote command can never be echoed
 * back as a brand-new local command.
 */
enum class PlaybackSyncEventType {
    TRACK,
    PLAY,
    PAUSE,
    SEEK,
    NEXT,
    PREVIOUS,
    QUEUE
}

data class PlaybackSyncEvent(
    val type: PlaybackSyncEventType,
    val track: Track?,
    val queue: List<Track>,
    val positionMs: Long,
    val isPlaying: Boolean,
    val speed: Float = 1f,
    val issuedAt: Long = System.currentTimeMillis()
)

interface PlaybackSyncSink {
    fun onLocalPlaybackEvent(event: PlaybackSyncEvent)
}

enum class RemotePlaybackAction {
    TRACK,
    PLAY,
    PAUSE,
    SEEK,
    NEXT,
    PREVIOUS,
    RATE,
    QUEUE
}

data class RemotePlaybackCommand(
    val action: RemotePlaybackAction,
    val track: Track? = null,
    val queue: List<Track> = emptyList(),
    val positionMs: Long = 0L,
    val playing: Boolean = true,
    val speed: Float = 1f
)
