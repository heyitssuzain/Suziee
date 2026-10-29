package com.example.playback

import com.example.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaybackQueueTest {

    private fun track(id: String) = Track(id = id, title = "Song $id", artist = "Artist")

    private val a = track("A")
    private val b = track("B")
    private val c = track("C")
    private val d = track("D")
    private val e = track("E")

    @Test
    fun `play all keeps the playlist order and starts at the tapped track`() {
        val selection = PlaybackQueue.select(c, listOf(a, b, c, d), shuffle = false)

        assertEquals(listOf(a, b, c, d), selection.queue)
        assertEquals(2, selection.currentIndex)
        assertEquals(2, selection.queue.indexOf(c))
    }

    @Test
    fun `completion advances exactly one track at a time`() {
        val queue = listOf(a, b, c, d)

        assertEquals(1, PlaybackQueue.autoAdvanceIndex(0, queue.size, RepeatMode.OFF))
        assertEquals(2, PlaybackQueue.autoAdvanceIndex(1, queue.size, RepeatMode.OFF))
        assertEquals(3, PlaybackQueue.autoAdvanceIndex(2, queue.size, RepeatMode.OFF))
        assertNull(PlaybackQueue.autoAdvanceIndex(3, queue.size, RepeatMode.OFF))
    }

    @Test
    fun `repeat all wraps only after the final track`() {
        assertEquals(1, PlaybackQueue.autoAdvanceIndex(0, 4, RepeatMode.ALL))
        assertEquals(0, PlaybackQueue.autoAdvanceIndex(3, 4, RepeatMode.ALL))
    }

    @Test
    fun `repeat one does not turn manual next into a two-song loop`() {
        assertNull(PlaybackQueue.manualNextIndex(3, 4, RepeatMode.ONE))
        assertEquals(0, PlaybackQueue.manualNextIndex(3, 4, RepeatMode.ALL))
        assertEquals(1, PlaybackQueue.manualNextIndex(0, 4, RepeatMode.ONE))
    }

    @Test
    fun `previous never wraps past the start`() {
        assertNull(PlaybackQueue.previousIndex(0, 4))
        assertEquals(0, PlaybackQueue.previousIndex(1, 4))
        assertEquals(2, PlaybackQueue.previousIndex(3, 4))
    }

    @Test
    fun `play next inserts after the current track without moving the cursor`() {
        val updated = PlaybackQueue.insertNext(listOf(a, b, c, d), currentIndex = 0, track = e)

        assertEquals(listOf(a, e, b, c, d), updated)
        assertEquals(1, updated.indexOf(e))
        assertEquals(0, updated.indexOf(a))
    }

    @Test
    fun `play next at the end appends rather than replacing the queue`() {
        val updated = PlaybackQueue.insertNext(listOf(a, b, c, d), currentIndex = 3, track = e)

        assertEquals(listOf(a, b, c, d, e), updated)
    }

    @Test
    fun `select appends a track missing from the requested queue`() {
        val selection = PlaybackQueue.select(e, listOf(a, b, c, d), shuffle = false)

        assertEquals(listOf(a, b, c, d, e), selection.queue)
        assertEquals(4, selection.currentIndex)
    }

    @Test
    fun `shuffle preserves every item and keeps the selected track current`() {
        val selection = PlaybackQueue.select(
            track = c,
            requested = listOf(a, b, c, d),
            shuffle = true,
            random = Random(7)
        )

        assertEquals(4, selection.queue.size)
        assertEquals(setOf("A", "B", "C", "D"), selection.queue.map { it.id }.toSet())
        assertEquals("C", selection.queue[selection.currentIndex].id)
    }

    @Test
    fun `turning shuffle on keeps the current track first`() {
        val shuffled = PlaybackQueue.shuffledWithCurrentFirst(
            originalQueue = listOf(a, b, c, d),
            currentTrack = b,
            random = Random(3)
        )

        assertEquals(b, shuffled.first())
        assertEquals(listOf("A", "B", "C", "D"), shuffled.map { it.id }.sorted())
        assertTrue(shuffled.drop(1).none { it.id == b.id })
    }
}
