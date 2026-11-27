package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.couple.CoupleSpaceController
import com.example.data.local.SuzieeDatabase
import com.example.data.preferences.SuzieePreferences
import com.example.downloads.DownloadManager
import com.example.playback.MusicPlayerManager
import com.example.ui.ImageLoaderConfig

class SuzieeApplication : Application(), ImageLoaderFactory {

    lateinit var database: SuzieeDatabase
        private set

    lateinit var preferences: SuzieePreferences
        private set

    lateinit var playerManager: MusicPlayerManager
        private set

    /**
     * Shared download manager. One instance for the whole app so the downloads table and
     * the in-flight job map stay consistent no matter which screen starts a download -
     * and so every track row can observe live progress from the same source.
     */
    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            this,
            database,
            playerManager.repository,
            playerManager.streamResolver,
            preferences
        )
    }

    /**
     * The Couple Space orchestrator. It is process-scoped, owns no Activity, and only opens
     * a peer connection while the Couple UI asks it to. Creating it is cheap: it observes a
     * few Room flows and a DataStore preference, so people who never pair pay no network cost.
     */
    val coupleSpace: CoupleSpaceController by lazy {
        CoupleSpaceController(
            context = this,
            database = database,
            playerManager = playerManager,
            preferences = preferences
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = SuzieeDatabase.getInstance(this)
        preferences = SuzieePreferences(this)
        playerManager = MusicPlayerManager(this, database, preferences)
        // Fetch the YouTube visitor session in the background now, so the first song is
        // one player-API POST away instead of two network round trips. Safe if it fails:
        // the resolver fetches a session lazily on the first play.
        playerManager.streamResolver.warmUp()
        // Restore the persisted pairing and start observing chat/queue/playback. This does
        // not connect to anything; it only makes the Couple tab immediately consistent after
        // process death or a reboot.
        coupleSpace.start()
    }

    /** Coil uses this loader for every AsyncImage in the app. */
    override fun newImageLoader(): ImageLoader = ImageLoaderConfig.create(this)

    companion object {
        lateinit var instance: SuzieeApplication
            private set
    }
}
