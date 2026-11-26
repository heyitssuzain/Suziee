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
        val THEME_ID = stringPreferencesKey("theme_id")
        val LYRICS_OFFSET_MS = intPreferencesKey("lyrics_offset_ms")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        val HOME_RAILS = stringPreferencesKey("home_rails")
        val DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
        val SMART_DOWNLOAD = booleanPreferencesKey("smart_download")
        val LASTFM_ENABLED = booleanPreferencesKey("lastfm_enabled")
        val LASTFM_API_KEY = stringPreferencesKey("lastfm_api_key")
        val LASTFM_USERNAME = stringPreferencesKey("lastfm_username")
        val LASTFM_SESSION_KEY = stringPreferencesKey("lastfm_session_key")
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

    /**
     * Persisted design identity. The default keeps the existing dark neon look (Aurora Glass)
     * so upgrading users do not get a surprise visual change on first launch.
     */
    val themeId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_ID] ?: "aurora_glass"
    }

    suspend fun setThemeId(themeId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_ID] = themeId
        }
    }

    /**
     * Manual sync offset for lyrics, in milliseconds. Positive values show lines later,
     * negative values earlier. Bounded to ±5s so a mis-tap can never desync an entire song.
     */
    val lyricsOffsetMs: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[Keys.LYRICS_OFFSET_MS] ?: 0).coerceIn(-5000, 5000)
    }

    suspend fun setLyricsOffsetMs(offsetMs: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LYRICS_OFFSET_MS] = offsetMs.coerceIn(-5000, 5000)
        }
    }

    // ------------------------------------------------------------------------------------
    // Search history (P1-12)
    // ------------------------------------------------------------------------------------

    /** Newest first, capped at 10. Stored as newline-separated text (no JSON dependency). */
    val recentSearches: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.RECENT_SEARCHES]
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    suspend fun addRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        context.dataStore.edit { prefs ->
            val existing = prefs[Keys.RECENT_SEARCHES]
                ?.split("\n")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val updated = (listOf(trimmed) + existing.filterNot { it.equals(trimmed, ignoreCase = true) })
                .take(10)
            prefs[Keys.RECENT_SEARCHES] = updated.joinToString("\n")
        }
    }

    suspend fun clearRecentSearches() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.RECENT_SEARCHES)
        }
    }

    // ------------------------------------------------------------------------------------
    // Configurable home rails (P1-13)
    // ------------------------------------------------------------------------------------

    /** Empty means "user removed every rail"; absent means "use the defaults". */
    val homeRails: Flow<List<HomeRailConfig>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.HOME_RAILS]
        if (raw == null) HomeRailConfig.defaults else HomeRailConfig.decode(raw)
    }

    suspend fun setHomeRails(rails: List<HomeRailConfig>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HOME_RAILS] = HomeRailConfig.encode(rails)
        }
    }

    suspend fun resetHomeRails() {
        context.dataStore.edit { prefs -> prefs.remove(Keys.HOME_RAILS) }
    }

    suspend fun addHomeRail(title: String, query: String) {
        val cleanTitle = title.trim()
        val cleanQuery = query.trim()
        if (cleanTitle.isBlank() || cleanQuery.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.HOME_RAILS]?.let { HomeRailConfig.decode(it) }
                ?: HomeRailConfig.defaults
            val next = current + HomeRailConfig(cleanTitle, "Custom discovery rail", cleanQuery)
            prefs[Keys.HOME_RAILS] = HomeRailConfig.encode(next)
        }
    }

    suspend fun removeHomeRail(query: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.HOME_RAILS]?.let { HomeRailConfig.decode(it) }
                ?: HomeRailConfig.defaults
            prefs[Keys.HOME_RAILS] = HomeRailConfig.encode(current.filterNot { it.query == query })
        }
    }

    // ------------------------------------------------------------------------------------
    // Downloads (P2-10) and Smart Download (P1-15)
    // ------------------------------------------------------------------------------------

    /** Preferred download bitrate tier: "high" (160k), "medium" (128k) or "low" (70k). */
    val downloadQuality: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DOWNLOAD_QUALITY] ?: "high"
    }

    suspend fun setDownloadQuality(quality: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DOWNLOAD_QUALITY] = quality
        }
    }

    val isSmartDownloadEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SMART_DOWNLOAD] ?: false
    }

    suspend fun setSmartDownloadEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SMART_DOWNLOAD] = enabled
        }
    }

    // ------------------------------------------------------------------------------------
    // Last.fm (P3-4) — opt-in and fully user-supplied credentials.
    // ------------------------------------------------------------------------------------

    val isLastFmEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.LASTFM_ENABLED] ?: false
    }

    val lastFmApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LASTFM_API_KEY] ?: ""
    }

    val lastFmUsername: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LASTFM_USERNAME] ?: ""
    }

    val lastFmSessionKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LASTFM_SESSION_KEY] ?: ""
    }

    suspend fun setLastFmEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.LASTFM_ENABLED] = enabled }
    }

    suspend fun setLastFmApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[Keys.LASTFM_API_KEY] = key.trim() }
    }

    suspend fun setLastFmUsername(username: String) {
        context.dataStore.edit { prefs -> prefs[Keys.LASTFM_USERNAME] = username.trim() }
    }

    suspend fun setLastFmSessionKey(key: String) {
        context.dataStore.edit { prefs -> prefs[Keys.LASTFM_SESSION_KEY] = key.trim() }
    }
}
