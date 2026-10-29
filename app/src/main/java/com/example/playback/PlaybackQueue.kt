package com.example.playback

import com.example.domain.model.Track
import kotlin.random.Random

enum class RepeatMode {
    OFF, ALL, ONE
}

/**
 * Pure queue rules for [MusicPlayerManager].
 *
 * The player deliberately loads one ExoPlayer media item at a time, so the app owns the
 * queue cursor. Keeping the arithmetic here makes the "next track" decisions testable
 * without an Android player: completion, manual Next, repeat and Play Next all go through
 * the same rules instead of each caller inventing its own index.
 */
internal object PlaybackQueue {

    data class Selection(
        val originalQueue: List<Track>,
        val queue: List<Track>,
        val currentIndex: Int
    )

    /**
     * Builds the queue for a newly selected track. A track missing from the requested list
     * is appended (the user still expects it to play); a track already present keeps the
     * list's existing order, so a playlist starts exactly where the user tapped.
     */
    fun select(
        track: Track,
        requested: List<Track>,
        shuffle: Boolean,
        random: Random = Random.Default
    ): Selection {
        val original = if (requested.none { it.id == track.id }) requested + track else requested
        val ordered = if (shuffle) original.shuffled(random) else original
        val index = ordered.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        return Selection(original, ordered, index)
    }

    /**
     * Inserts [track] immediately after the current item and leaves the cursor alone.
     * The return value is the new queue; [currentIndex] is intentionally unchanged.
     */
    fun insertNext(queue: List<Track>, currentIndex: Int, track: Track): List<Track> {
        val insertAt = (currentIndex + 1).coerceIn(0, queue.size)
        return queue.toMutableList().apply { add(insertAt, track) }
    }

    /** Auto-advance after a track ends. `null` means the playlist is finished. */
    fun autoAdvanceIndex(
        currentIndex: Int,
        queueSize: Int,
        repeatMode: RepeatMode
    ): Int? {
        if (queueSize <= 0) return null
        if (currentIndex !in 0 until queueSize) return 0
        if (currentIndex + 1 < queueSize) return currentIndex + 1
        return if (repeatMode == RepeatMode.ALL) 0 else null
    }

    /**
     * Manual Next. Repeat ONE affects automatic completion only; pressing Next on the last
     * track with repeat ONE must not wrap to the start, because that is how a two-song loop
     * is born. Only Repeat ALL wraps.
     */
    fun manualNextIndex(
        currentIndex: Int,
        queueSize: Int,
        repeatMode: RepeatMode
    ): Int? {
        if (queueSize <= 0) return null
        if (currentIndex !in 0 until queueSize) return 0
        if (currentIndex + 1 < queueSize) return currentIndex + 1
        return if (repeatMode == RepeatMode.ALL) 0 else null
    }

    /** Previous-track index, or `null` at the start of the queue. */
    fun previousIndex(currentIndex: Int, queueSize: Int): Int? {
        if (queueSize <= 0) return null
        val previous = currentIndex - 1
        return if (currentIndex in 1 until queueSize && previous >= 0) previous else null
    }

    /**
     * Rebuilds the shuffled order with the current track first. Kept here so toggling
     * shuffle cannot silently duplicate or drop the playing track.
     */
    fun shuffledWithCurrentFirst(
        originalQueue: List<Track>,
        currentTrack: Track?,
        random: Random = Random.Default
    ): List<Track> {
        if (currentTrack == null) return originalQueue.shuffled(random)
        val remaining = originalQueue.toMutableList()
        val currentIndex = remaining.indexOfFirst { it.id == currentTrack.id }
        if (currentIndex >= 0) remaining.removeAt(currentIndex)
        remaining.shuffle(random)
        return listOf(currentTrack) + remaining
    }
}
