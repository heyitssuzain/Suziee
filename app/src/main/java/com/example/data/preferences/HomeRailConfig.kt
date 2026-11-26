package com.example.data.preferences

/**
 * One configurable discovery shelf on the Home screen (P1-13).
 *
 * @param title Section heading, e.g. "Trending Now".
 * @param subtitle Supporting line shown under the heading.
 * @param query Search query the rail resolves through the same backend as Search.
 */
data class HomeRailConfig(
    val title: String,
    val subtitle: String,
    val query: String
) {
    companion object {
        /** Shown when the user has never customised the shelf list. */
        val defaults: List<HomeRailConfig> = listOf(
            HomeRailConfig("Trending Now", "What people are playing today", "Anime Opening"),
            HomeRailConfig("Popular This Week", "Hand-picked J-Music favourites", "J-Pop Hits"),
            HomeRailConfig("Bollywood & Hindi Hits", "Mainstream trending in India", "Bollywood Top Songs"),
            HomeRailConfig("Global Top Hits", "English & international chart-toppers", "Top English Hits This Week")
        )

        private const val FIELD_SEPARATOR = "\u001F"
        private const val ROW_SEPARATOR = "\n"

        fun encode(rails: List<HomeRailConfig>): String = rails.joinToString(ROW_SEPARATOR) { rail ->
            listOf(rail.title, rail.subtitle, rail.query).joinToString(FIELD_SEPARATOR)
        }

        fun decode(raw: String?): List<HomeRailConfig> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(ROW_SEPARATOR).mapNotNull { row ->
                val parts = row.split(FIELD_SEPARATOR)
                if (parts.size < 3) return@mapNotNull null
                val title = parts[0].trim()
                val query = parts[2].trim()
                if (title.isBlank() || query.isBlank()) return@mapNotNull null
                HomeRailConfig(title, parts[1].trim(), query)
            }
        }
    }
}
