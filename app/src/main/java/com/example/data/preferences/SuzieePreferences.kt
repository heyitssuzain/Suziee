package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "suziee_settings")

class SuzieePreferences(private val context: Context) {

    private object Keys {
        val FIRST_LAUNCH_COMPLETED = booleanPreferencesKey("first_launch_completed")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
        val AUTOPLAY = booleanPreferencesKey("autoplay")
        val SHUFFLE_DEFAULT = booleanPreferencesKey("shuffle_default")
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
    }

    val isFirstLaunchCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.FIRST_LAUNCH_COMPLETED] ?: false
    }

    suspend fun setFirstLaunchCompleted(completed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FIRST_LAUNCH_COMPLETED] = completed
        }
    }

    val isReducedMotion: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.REDUCED_MOTION] ?: false
    }

    suspend fun setReducedMotion(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REDUCED_MOTION] = enabled
        }
    }

    val voiceLanguage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.VOICE_LANGUAGE] ?: "en-US"
    }

    suspend fun setVoiceLanguage(language: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VOICE_LANGUAGE] = language
        }
    }

    val isAutoplayEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTOPLAY] ?: true
    }

    suspend fun setAutoplayEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTOPLAY] = enabled
        }
    }
}
