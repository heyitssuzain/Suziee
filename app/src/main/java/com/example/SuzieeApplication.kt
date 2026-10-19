package com.example

import android.app.Application
import com.example.data.local.SuzieeDatabase
import com.example.data.preferences.SuzieePreferences
import com.example.data.remote.ytdlp.SuzieeAudioExtractor
import com.example.downloads.DownloadManager
import com.example.playback.MusicPlayerManager

class SuzieeApplication : Application() {

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
        DownloadManager(this, database, playerManager.repository, playerManager.streamResolver)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = SuzieeDatabase.getInstance(this)
        preferences = SuzieePreferences(this)
        playerManager = MusicPlayerManager(this, database, preferences)
        // Unpack the bundled Python runtime / yt-dlp binary off the main thread now, so
        // the first song does not pay for it. Safe if it fails: the WebView fallback
        // still resolves playback.
        SuzieeAudioExtractor.warmUp(this)
    }

    companion object {
        lateinit var instance: SuzieeApplication
            private set
    }
}
