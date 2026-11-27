package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.CoupleEventEntity
import com.example.data.local.entities.CoupleMessageEntity
import com.example.data.local.entities.CouplePeerEntity
import com.example.data.local.entities.CouplePendingPairingEntity
import com.example.data.local.entities.CoupleSharedTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CouplePeerDao {

    @Query("SELECT * FROM couple_peer WHERE status = 'PAIRED' ORDER BY pairedAt DESC LIMIT 1")
    fun observeActivePeer(): Flow<CouplePeerEntity?>

    @Query("SELECT * FROM couple_peer WHERE status = 'PAIRED' ORDER BY pairedAt DESC LIMIT 1")
    suspend fun getActivePeer(): CouplePeerEntity?

    @Query("SELECT * FROM couple_peer WHERE peerId = :peerId LIMIT 1")
    suspend fun getPeer(peerId: String): CouplePeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: CouplePeerEntity)

    @Query("UPDATE couple_peer SET lastConnectedAt = :ts, lastSeenAt = :ts WHERE peerId = :peerId")
    suspend fun markConnected(peerId: String, ts: Long)

    @Query("UPDATE couple_peer SET lastSeenAt = :ts WHERE peerId = :peerId")
    suspend fun markSeen(peerId: String, ts: Long)

    @Query("UPDATE couple_peer SET lastRemoteChatSeq = :seq WHERE peerId = :peerId")
    suspend fun setLastRemoteChatSeq(peerId: String, seq: Long)

    @Query("UPDATE couple_peer SET lastRemotePlaylistSeq = :seq WHERE peerId = :peerId")
    suspend fun setLastRemotePlaylistSeq(peerId: String, seq: Long)

    @Query("UPDATE couple_peer SET localChatSeq = :seq WHERE peerId = :peerId")
    suspend fun setLocalChatSeq(peerId: String, seq: Long)

    @Query("UPDATE couple_peer SET localPlaylistSeq = :seq WHERE peerId = :peerId")
    suspend fun setLocalPlaylistSeq(peerId: String, seq: Long)

    @Query("UPDATE couple_peer SET playlistOrderLamport = :lamport, playlistOrderActor = :actor WHERE peerId = :peerId")
    suspend fun setPlaylistOrderVersion(peerId: String, lamport: Long, actor: String)

    @Query("DELETE FROM couple_peer WHERE peerId = :peerId")
    suspend fun deletePeer(peerId: String)

    @Query("DELETE FROM couple_peer")
    suspend fun deleteAll()
}

@Dao
interface CoupleMessageDao {

    @Query("SELECT * FROM couple_message ORDER BY createdAt ASC, sequence ASC")
    fun observeAll(): Flow<List<CoupleMessageEntity>>

    @Query("SELECT * FROM couple_message WHERE deliveryState IN ('PENDING','SENT') ORDER BY sequence ASC")
    suspend fun pendingMessages(): List<CoupleMessageEntity>

    @Query("SELECT * FROM couple_message WHERE eventId = :eventId LIMIT 1")
    suspend fun getMessage(eventId: String): CoupleMessageEntity?

    @Query("SELECT MAX(sequence) FROM couple_message WHERE direction = 'OUT'")
    suspend fun maxOutgoingSequence(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: CoupleMessageEntity): Long

    @Query("UPDATE couple_message SET deliveryState = 'SENT', sentAt = :ts WHERE eventId = :eventId AND deliveryState = 'PENDING'")
    suspend fun markSent(eventId: String, ts: Long)

    @Query("UPDATE couple_message SET deliveryState = 'DELIVERED', deliveredAt = :ts, sentAt = COALESCE(sentAt, :ts) WHERE eventId = :eventId")
    suspend fun markDelivered(eventId: String, ts: Long)

    @Query("UPDATE couple_message SET deliveryState = 'FAILED', error = :reason WHERE eventId = :eventId")
    suspend fun markFailed(eventId: String, reason: String)

    @Query("UPDATE couple_message SET deliveryState = 'PENDING', error = NULL WHERE eventId = :eventId")
    suspend fun markPending(eventId: String)

    @Query("UPDATE couple_message SET deliveryState = 'FAILED', error = :reason WHERE deliveryState IN ('PENDING','SENT')")
    suspend fun failAllUndelivered(reason: String)

    @Query("DELETE FROM couple_message")
    suspend fun deleteAll()
}

@Dao
interface CoupleSharedTrackDao {

    @Query(
        "SELECT * FROM couple_shared_track WHERE peerId = :peerId AND deleted = 0 " +
            "ORDER BY position ASC, lamport ASC, updatedBy ASC, trackId ASC"
    )
    fun observeActive(peerId: String): Flow<List<CoupleSharedTrackEntity>>

    @Query(
        "SELECT * FROM couple_shared_track WHERE peerId = :peerId " +
            "ORDER BY position ASC, lamport ASC, updatedBy ASC, trackId ASC"
    )
    suspend fun getAll(peerId: String): List<CoupleSharedTrackEntity>

    @Query("SELECT * FROM couple_shared_track WHERE peerId = :peerId AND trackId = :trackId LIMIT 1")
    suspend fun get(peerId: String, trackId: String): CoupleSharedTrackEntity?

    @Query("SELECT MAX(lamport) FROM couple_shared_track WHERE peerId = :peerId")
    suspend fun maxLamport(peerId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: CoupleSharedTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<CoupleSharedTrackEntity>)

    @Query("DELETE FROM couple_shared_track WHERE peerId = :peerId")
    suspend fun deleteAll(peerId: String)
}

@Dao
interface CoupleEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: CoupleEventEntity): Long

    @Query("DELETE FROM couple_event_log WHERE receivedAt < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM couple_event_log")
    suspend fun deleteAll()
}

@Dao
interface CouplePendingPairingDao {

    @Query("SELECT * FROM couple_pending_pairing WHERE id = 1 LIMIT 1")
    suspend fun get(): CouplePendingPairingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pending: CouplePendingPairingEntity)

    @Query("DELETE FROM couple_pending_pairing")
    suspend fun clear()
}
