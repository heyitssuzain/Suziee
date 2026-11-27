package com.example.couple

import com.example.data.local.entities.CoupleSharedTrackEntity

data class PlaylistMergeResult(
    val tracks: List<CoupleSharedTrackEntity>,
    val accepted: Boolean
)

/**
 * Deterministic, event-based shared-playlist merge.
 *
 * Every item is a last-writer-wins register keyed by (lamport, actor). Reorders are a
 * separate whole-list register with the same ordering rule. Both phones therefore converge
 * on the same queue even when they edit at the same instant, and a malformed or stale event
 * can never remove a newer local edit.
 */
object CouplePlaylistMerge {

    private val comparator: Comparator<CoupleSharedTrackEntity> =
        compareBy<CoupleSharedTrackEntity> { it.position }
            .thenBy { it.lamport }
            .thenBy { it.updatedBy }
            .thenBy { it.trackId }

    fun applyAdd(
        current: List<CoupleSharedTrackEntity>,
        incoming: CoupleSharedTrackEntity
    ): PlaylistMergeResult {
        val existing = current.firstOrNull { it.trackId == incoming.trackId }
        if (existing != null && !versionIsNewer(
                incoming.lamport,
                incoming.updatedBy,
                existing.lamport,
                existing.updatedBy
            )
        ) {
            return PlaylistMergeResult(current, accepted = false)
        }

        // Position must be commutative across replicas. A local add uses the next free slot;
        // a remote add keeps the position chosen by its sender. Concurrent adds can therefore
        // collide, and the comparator's (lamport, actor, trackId) tie-break resolves the
        // collision identically on both phones. Using nextPosition() for a remote add would
        // make the final order depend on arrival order and the two queues would diverge.
        val position = when {
            existing != null && !existing.deleted -> existing.position
            incoming.position >= 0 -> incoming.position
            else -> nextPosition(current)
        }
        val merged = incoming.copy(
            position = position,
            deleted = false
        )
        val next = current.filterNot { it.trackId == incoming.trackId } + merged
        return PlaylistMergeResult(next.sortedWith(comparator), accepted = true)
    }

    fun applyRemove(
        current: List<CoupleSharedTrackEntity>,
        peerId: String,
        trackId: String,
        lamport: Long,
        actor: String,
        now: Long
    ): PlaylistMergeResult {
        val existing = current.firstOrNull { it.trackId == trackId }
        if (existing != null && !versionIsNewer(
                lamport,
                actor,
                existing.lamport,
                existing.updatedBy
            )
        ) {
            return PlaylistMergeResult(current, accepted = false)
        }

        val tombstone = existing?.copy(
            lamport = lamport,
            updatedBy = actor,
            updatedAt = now,
            deleted = true
        ) ?: CoupleSharedTrackEntity(
            peerId = peerId,
            trackId = trackId,
            title = "",
            artist = "",
            durationSeconds = 0L,
            artworkUrl = "",
            position = nextPosition(current),
            lamport = lamport,
            updatedBy = actor,
            updatedAt = now,
            deleted = true
        )

        val next = current.filterNot { it.trackId == trackId } + tombstone
        return PlaylistMergeResult(next.sortedWith(comparator), accepted = true)
    }

    fun applyOrder(
        current: List<CoupleSharedTrackEntity>,
        order: List<String>,
        lamport: Long,
        actor: String,
        currentOrderLamport: Long,
        currentOrderActor: String
    ): PlaylistMergeResult {
        if (!versionIsNewer(lamport, actor, currentOrderLamport, currentOrderActor)) {
            return PlaylistMergeResult(current, accepted = false)
        }

        val active = current.filterNot { it.deleted }
        val byId = active.associateBy { it.trackId }
        val ordered = LinkedHashMap<String, CoupleSharedTrackEntity>()
        order.forEach { id ->
            byId[id]?.let { ordered[id] = it }
        }
        active.sortedWith(comparator).forEach { item ->
            if (!ordered.containsKey(item.trackId)) ordered[item.trackId] = item
        }

        val positions = ordered.keys.toList().withIndex().associate { (index, id) -> id to index }
        val reordered = current.map { item ->
            val newPosition = positions[item.trackId]
            if (newPosition == null) item else item.copy(position = newPosition)
        }
        return PlaylistMergeResult(reordered.sortedWith(comparator), accepted = true)
    }

    fun applySnapshot(
        current: List<CoupleSharedTrackEntity>,
        incoming: List<CoupleSharedTrackEntity>,
        order: List<String>,
        lamport: Long,
        actor: String,
        currentOrderLamport: Long,
        currentOrderActor: String
    ): PlaylistMergeResult {
        val byId = current.associateBy { it.trackId }.toMutableMap()
        var accepted = false
        incoming.forEach { item ->
            val existing = byId[item.trackId]
            if (existing == null || versionIsNewer(
                    item.lamport,
                    item.updatedBy,
                    existing.lamport,
                    existing.updatedBy
                )
            ) {
                byId[item.trackId] = item
                accepted = true
            }
        }

        val merged = byId.values.sortedWith(comparator)
        if (order.isEmpty()) {
            return PlaylistMergeResult(merged, accepted)
        }
        val ordered = applyOrder(
            current = merged,
            order = order,
            lamport = lamport,
            actor = actor,
            currentOrderLamport = currentOrderLamport,
            currentOrderActor = currentOrderActor
        )
        return PlaylistMergeResult(ordered.tracks, accepted || ordered.accepted)
    }

    fun activeSorted(current: List<CoupleSharedTrackEntity>): List<CoupleSharedTrackEntity> =
        current.filterNot { it.deleted }.sortedWith(comparator)

    fun nextPosition(current: List<CoupleSharedTrackEntity>): Int =
        (current.maxOfOrNull { it.position } ?: -1) + 1
}
