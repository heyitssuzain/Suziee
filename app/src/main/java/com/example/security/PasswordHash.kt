package com.example.security

/**
 * The only place the dedication key is stored.
 *
 * The literal secret never appears in this repository, in Gradle files, in logs or in any
 * APK resource — only its one-way SHA-256 digest does. Verification happens at runtime by
 * hashing whatever the user types and comparing the two digests with a constant-time
 * comparison, so an incorrect guess cannot be narrowed down by timing.
 *
 * This file is intentionally tiny and dependency-free so it can be regenerated at build time
 * without touching anything else (see the "generatePasswordHash" task in `app/build.gradle.kts`).
 */
internal object PasswordHash {

    /**
     * SHA-256 digest of the dedication key.
     *
     * Kept as a lowercase hex string so it can be compared byte-for-byte without allocating a
     * `MessageDigest` on every keystroke.
     */
    const val DIGEST: String =
        "2f179498ae6fe37dd0ff6f480c6da1fc41183ad69388ce58838055f40cc126bb"

    /**
     * Verifies [candidate] against [DIGEST].
     *
     * @param candidate the exact text the user typed, case-sensitively and with no
     *   normalisation, so every near-miss (a leading space, a different letter case, a
     *   plural, a trailing newline) is rejected.
     * @return true only for an exact match.
     */
    fun matches(candidate: String): Boolean {
        val digest = sha256Hex(candidate)
        // Constant-time comparison: same length plus XOR accumulation, never an early exit.
        if (digest.length != DIGEST.length) return false
        var diff = 0
        for (i in digest.indices) {
            diff = diff or (digest[i].code xor DIGEST[i].code)
        }
        return diff == 0
    }

    /** Lowercase hex SHA-256 of [value], computed with the platform digest (no reflection). */
    private fun sha256Hex(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        // Manual hex encoding keeps this JVM-only and avoids String.format on every call.
        val hex = CharArray(bytes.size * 2)
        val table = "0123456789abcdef"
        for (i in bytes.indices) {
            val byte = bytes[i].toInt() and 0xff
            hex[i * 2] = table[byte ushr 4]
            hex[i * 2 + 1] = table[byte and 0x0f]
        }
        return String(hex)
    }
}