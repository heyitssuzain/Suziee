package com.example.couple

import com.example.data.local.entities.CoupleSharedTrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CouplePlaylistMergeTest {

    private fun track(
        id: String,
        position: Int,
        lamport: Long,
        actor: String = "actor-a",
        deleted: Boolean = false
    ) = CoupleSharedTrackEntity(
        peerId = "peer-1",
        trackId = id,
        title = "Title $id",
        artist = "Artist",
        durationSeconds = 180L,
        artworkUrl = "",
        position = position,
        lamport = lamport,
        updatedBy = actor,
        updatedAt = 1_000L,
        deleted = deleted
    )

    private fun ids(tracks: List<CoupleSharedTrackEntity>): List<String> =
        CouplePlaylistMerge.activeSorted(tracks).map { it.trackId }

    @Test
    fun addAppendsAndRejectsAStaleVersion() {
        val first = CouplePlaylistMerge.applyAdd(emptyList(), track("a", position = 0, lamport = 1L))
        assertTrue(first.accepted)
        assertEquals(listOf("a"), ids(first.tracks))

        val stale = CouplePlaylistMerge.applyAdd(first.tracks, track("a", position = 0, lamport = 0L))
        assertFalse(stale.accepted)
        assertEquals(1L, stale.tracks.first().lamport)
    }

    @Test
    fun concurrentAddsConvergeRegardlessOfArrivalOrder() {
        val a = track("a", position = 0, lamport = 1L, actor = "actor-a")
        val b = track("b", position = 0, lamport = 1L, actor = "actor-b")

        val replicaOne = CouplePlaylistMerge
            .applyAdd(CouplePlaylistMerge.applyAdd(emptyList(), a).tracks, b)
            .tracks
        val replicaTwo = CouplePlaylistMerge
            .applyAdd(CouplePlaylistMerge.applyAdd(emptyList(), b).tracks, a)
            .tracks

        assertEquals(ids(replicaOne), ids(replicaTwo))
        assertEquals(listOf("a", "b"), ids(replicaOne))
    }

    @Test
    fun sequentialAndConcurrentAddsStillConverge() {
        val a = track("a", position = 0, lamport = 1L, actor = "actor-a")
        val b = track("b", position = 1, lamport = 2L, actor = "actor-a")
        val c = track("c", position = 0, lamport = 1L, actor = "actor-c")

        val left = CouplePlaylistMerge.applyAdd(
            CouplePlaylistMerge.applyAdd(
                CouplePlaylistMerge.applyAdd(emptyList(), a).tracks,
                b
            ).tracks,
            c
        ).tracks
        val right = CouplePlaylistMerge.applyAdd(
            CouplePlaylistMerge.applyAdd(
                CouplePlaylistMerge.applyAdd(emptyList(), c).tracks,
                a
            ).tracks,
            b
        ).tracks

        assertEquals(ids(left), ids(right))
    }

    @Test
    fun removeLeavesATombstoneAndRejectsStaleRemoval() {
        val added = CouplePlaylistMerge.applyAdd(emptyList(), track("a", 0, 1L)).tracks
        val removed = CouplePlaylistMerge.applyRemove(
            current = added,
            peerId = "peer-1",
            trackId = "a",
            lamport = 2L,
            actor = "actor-a",
            now = 2_000L
        )
        assertTrue(removed.accepted)
        assertTrue(removed.tracks.first().deleted)
        assertTrue(ids(removed.tracks).isEmpty())

        val stale = CouplePlaylistMerge.applyRemove(
            current = removed.tracks,
            peerId = "peer-1",
            trackId = "a",
            lamport = 1L,
            actor = "actor-b",
            now = 3_000L
        )
        assertFalse(stale.accepted)
    }

    @Test
    fun reAddingARemovedTrackUsesANewerVersion() {
        val added = CouplePlaylistMerge.applyAdd(emptyList(), track("a", 0, 1L)).tracks
        val removed = CouplePlaylistMerge.applyRemove(
            current = added,
            peerId = "peer-1",
            trackId = "a",
            lamport = 2L,
            actor = "actor-a",
            now = 2_000L
        ).tracks
        val reAdded = CouplePlaylistMerge.applyAdd(
            removed,
            track("a", position = 0, lamport = 3L, actor = "actor-b")
        )
        assertTrue(reAdded.accepted)
        assertFalse(reAdded.tracks.first().deleted)
        assertEquals(listOf("a"), ids(reAdded.tracks))
    }

    @Test
    fun wholeOrderIsLastWriterWins() {
        val base = listOf(
            track("a", 0, 1L),
            track("b", 1, 1L),
            track("c", 2, 1L)
        )
        val ordered = CouplePlaylistMerge.applyOrder(
            current = base,
            order = listOf("c", "a", "b"),
            lamport = 5L,
            actor = "actor-a",
            currentOrderLamport = 0L,
            currentOrderActor = ""
        )
        assertTrue(ordered.accepted)
        assertEquals(listOf("c", "a", "b"), ids(ordered.tracks))

        val stale = CouplePlaylistMerge.applyOrder(
            current = ordered.tracks,
            order = listOf("a", "b", "c"),
            lamport = 4L,
            actor = "actor-z",
            currentOrderLamport = 5L,
            currentOrderActor = "actor-a"
        )
        assertFalse(stale.accepted)

        val tieLost = CouplePlaylistMerge.applyOrder(
            current = ordered.tracks,
            order = listOf("a", "b", "c"),
            lamport = 5L,
            actor = "actor-a",
            currentOrderLamport = 5L,
            currentOrderActor = "actor-b"
        )
        assertFalse(tieLost.accepted)

        val tieWon = CouplePlaylistMerge.applyOrder(
            current = ordered.tracks,
            order = listOf("b", "c", "a"),
            lamport = 5L,
            actor = "actor-c",
            currentOrderLamport = 5L,
            currentOrderActor = "actor-b"
        )
        assertTrue(tieWon.accepted)
        assertEquals(listOf("b", "c", "a"), ids(tieWon.tracks))
    }

    @Test
    fun snapshotMergeKeepsTheNewerItemVersion() {
        val local = listOf(
            track("a", 0, 1L),
            track("b", 1, 3L, actor = "actor-a")
        )
        val remote = listOf(
            track("a", 0, 2L, actor = "actor-b"),
            track("b", 1, 2L, actor = "actor-a"),
            track("c", 2, 1L, actor = "actor-c")
        )
        val merged = CouplePlaylistMerge.applySnapshot(
            current = local,
            incoming = remote,
            order = emptyList(),
            lamport = 0L,
            actor = "",
            currentOrderLamport = 0L,
            currentOrderActor = ""
        )
        assertEquals(listOf("a", "b", "c"), ids(merged.tracks))
        assertEquals(2L, merged.tracks.first { it.trackId == "a" }.lamport)
        assertEquals(3L, merged.tracks.first { it.trackId == "b" }.lamport)
    }
}
