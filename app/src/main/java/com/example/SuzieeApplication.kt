package com.example

import android.app.Application
import com.example.data.local.SuzieeDatabase
import com.example.data.preferences.SuzieePreferences
import com.example.playback.MusicPlayerManager

class SuzieeApplication : Application() {

    lateinit var database: SuzieeDatabase
        private set

    lateinit var preferences: SuzieePreferences
        private set

    lateinit var playerManager: MusicPlayerManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = SuzieeDatabase.getInstance(this)
        preferences = SuzieePreferences(this)
        playerManager = MusicPlayerManager(this, database, preferences)
    }

    companion object {
        lateinit var instance: SuzieeApplication
            private set
    }
}
