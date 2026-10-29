package com.example.playback

/**
 * Guards player callbacks against the two races that interrupt playlists:
 *
 *  * an error/ENDED callback from the previous media item arriving after the user selected
 *    another track, and
 *  * the same ENDED callback being delivered twice for one item.
 *
 * Only the current MediaItem may drive queue advancement or recovery.
 */
internal object PlaybackEventPolicy {

    fun isForCurrentTrack(eventMediaId: String?, currentTrackId: String?): Boolean {
        if (eventMediaId.isNullOrBlank() || currentTrackId.isNullOrBlank()) return true
        return eventMediaId == currentTrackId
    }

    fun shouldHandleEnded(
        eventMediaId: String?,
        currentTrackId: String?,
        lastHandledMediaId: String?
    ): Boolean {
        if (!isForCurrentTrack(eventMediaId, currentTrackId)) return false
        return eventMediaId.isNullOrBlank() || eventMediaId != lastHandledMediaId
    }
}
