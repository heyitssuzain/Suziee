package com.example.data.preferences

/**
 * Ephemeral state for the private dedication (PR-6).
 *
 * **Nothing here is persisted.** The dedication keeps its meaning by being rediscovered, so
 * leaving Settings or the app forgets it completely — no DataStore key, no database row, no
 * SharedPreferences, and therefore nothing to leak in a backup or on another device.
 *
 * Kept as a plain object rather than a Compose state holder so both the Settings screen and
 * the romantic overlay can read it without threading extra parameters.
 */
object SecretPrefs {

    /** True once the two-finger / triple-tap gesture has unlocked the hidden entry. */
    var isSecretRevealed: Boolean = false
        private set

    fun reveal() {
        isSecretRevealed = true
    }

    /**
     * Hides the entry again. Called when Settings is left, so reopening never shows the
     * secret to whoever is holding the phone at that moment.
     */
    fun reset() {
        isSecretRevealed = false
    }
}