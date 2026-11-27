package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.CoupleEventDao
import com.example.data.local.dao.CoupleMessageDao
import com.example.data.local.dao.CouplePeerDao
import com.example.data.local.dao.CouplePendingPairingDao
import com.example.data.local.dao.CoupleSharedTrackDao
import com.example.data.local.dao.DownloadDao
import com.example.data.local.dao.FavoriteDao
import com.example.data.local.dao.HistoryDao
import com.example.data.local.dao.PlaylistDao
import com.example.data.local.dao.TrackDao
import com.example.data.local.entities.CoupleEventEntity
import com.example.data.local.entities.CoupleMessageEntity
import com.example.data.local.entities.CouplePeerEntity
import com.example.data.local.entities.CouplePendingPairingEntity
import com.example.data.local.entities.CoupleSharedTrackEntity
import com.example.data.local.entities.DownloadEntity
import com.example.data.local.entities.FavoriteEntity
import com.example.data.local.entities.HistoryEntity
import com.example.data.local.entities.PlaylistEntity
import com.example.data.local.entities.PlaylistTrackCrossRef
import com.example.data.local.entities.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        FavoriteEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        CouplePeerEntity::class,
        CoupleMessageEntity::class,
        CoupleSharedTrackEntity::class,
        CoupleEventEntity::class,
        CouplePendingPairingEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class SuzieeDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao

    abstract fun couplePeerDao(): CouplePeerDao
    abstract fun coupleMessageDao(): CoupleMessageDao
    abstract fun coupleSharedTrackDao(): CoupleSharedTrackDao
    abstract fun coupleEventDao(): CoupleEventDao
    abstract fun couplePendingPairingDao(): CouplePendingPairingDao

    companion object {
        @Volatile
        private var INSTANCE: SuzieeDatabase? = null

        /**
         * Adds the Couple Space tables without touching any existing music, playlist,
         * favourite, history or download row. A destructive upgrade here would wipe a
         * user's library, so the migration is explicit.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `couple_peer` (
                        `peerId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `publicKey` TEXT NOT NULL,
                        `pairingId` TEXT NOT NULL,
                        `pairedAt` INTEGER NOT NULL,
                        `lastConnectedAt` INTEGER,
                        `status` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `inviteSignature` TEXT NOT NULL,
                        `acceptSignature` TEXT NOT NULL,
                        `confirmSignature` TEXT NOT NULL,
                        `lastRemoteChatSeq` INTEGER NOT NULL,
                        `lastRemotePlaylistSeq` INTEGER NOT NULL,
                        `localChatSeq` INTEGER NOT NULL,
                        `localPlaylistSeq` INTEGER NOT NULL,
                        `playlistOrderLamport` INTEGER NOT NULL,
                        `playlistOrderActor` TEXT NOT NULL,
                        `lastSeenAt` INTEGER NOT NULL,
                        PRIMARY KEY(`peerId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `couple_message` (
                        `eventId` TEXT NOT NULL,
                        `peerId` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `sentAt` INTEGER,
                        `deliveredAt` INTEGER,
                        `deliveryState` TEXT NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `error` TEXT,
                        PRIMARY KEY(`eventId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_couple_message_peerId` ON `couple_message` (`peerId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_couple_message_deliveryState` ON `couple_message` (`deliveryState`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `couple_shared_track` (
                        `peerId` TEXT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `durationSeconds` INTEGER NOT NULL,
                        `artworkUrl` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `lamport` INTEGER NOT NULL,
                        `updatedBy` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `deleted` INTEGER NOT NULL,
                        PRIMARY KEY(`peerId`, `trackId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_couple_shared_track_peerId` ON `couple_shared_track` (`peerId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `couple_event_log` (
                        `eventId` TEXT NOT NULL,
                        `peerId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`eventId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_couple_event_log_receivedAt` ON `couple_event_log` (`receivedAt`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `couple_pending_pairing` (
                        `id` INTEGER NOT NULL,
                        `pairingId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `localDisplayName` TEXT NOT NULL,
                        `remoteDeviceId` TEXT NOT NULL,
                        `remotePublicKey` TEXT NOT NULL,
                        `remoteDisplayName` TEXT NOT NULL,
                        `localNonce` TEXT NOT NULL,
                        `remoteNonce` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `stage` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): SuzieeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SuzieeDatabase::class.java,
                    "suziee_music.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // Deliberately no destructive fallback: a future schema mistake must fail
                    // loudly instead of silently deleting the user's music library.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
