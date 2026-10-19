package com.example.data.remote.extraction

/**
 * Decides which sub-resources the headless extraction engine must **not** download.
 *
 * The engine only ever needs one thing from the watch page: the signed `googlevideo`
 * audio request the page's own player makes. Presentations, recommendations, artwork,
 * fonts, ad creatives and telemetry contribute nothing to that and are pure wasted
 * traffic and CPU, so they are answered with an empty local response.
 *
 * ## What is deliberately *not* blocked
 *  * `www.youtube.com`, `www.gstatic.com`, `googlevideo.com` - the player bootstrap and
 *    the media itself; blocking any of them breaks extraction completely.
 *  * **Video** segments (`videoplayback?...mime=video`). They are by far the largest
 *    requests, but answering a media request with an empty body makes the page's player
 *    error out and re-negotiate - which is exactly what previously stopped official
 *    songs from being captured. Video segments are therefore tolerated for the short
 *    window between navigation and capture, and the capture itself stops the page load.
 */
object AudioTrafficPolicy {

    /** Ad / tracker / telemetry hosts. Nothing here is needed to obtain an audio URL. */
    private val BLOCKED_HOST_SUFFIXES = listOf(
        "doubleclick.net",
        "googleads.g.doubleclick.net",
        "static.doubleclick.net",
        "googlesyndication.com",
        "pagead2.googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "ads.youtube.com",
        "imasdk.googleapis.com",
        "s0.2mdn.net",
        "2mdn.net",
        "moatads.com",
        "googletagservices.com",
        "googletagmanager.com",
        "google-analytics.com",
        "scorecardresearch.com",
        // Artwork / avatars / thumbnails: images only, never part of the media URL.
        "ytimg.com",
        "ggpht.com",
        "lh3.googleusercontent.com"
    )

    /**
     * Exact hosts blocked for a different reason than the list above.
     *
     * `fonts.gstatic.com` is *only* fonts, so blocking the exact host is safe and saves a
     * download on every navigation. `gstatic.com` as a whole is deliberately **not**
     * blocked: Google serves real player/static assets from `www.gstatic.com`.
     */
    private val BLOCKED_EXACT_HOSTS = setOf(
        "fonts.gstatic.com",
        "fonts.googleapis.com"
    )

    /** Cosmetic resources identified by path: stylesheets, images, fonts. */
    private val BLOCKED_PATH_SUFFIXES = listOf(
        ".css",
        ".png",
        ".jpg",
        ".jpeg",
        ".webp",
        ".gif",
        ".ico",
        ".svg",
        ".woff",
        ".woff2",
        ".ttf",
        ".otf"
    )

    fun shouldBlock(host: String, url: String): Boolean {
        val lowerHost = host.lowercase()
        if (lowerHost in BLOCKED_EXACT_HOSTS) return true
        if (BLOCKED_HOST_SUFFIXES.any { lowerHost == it || lowerHost.endsWith(".$it") }) return true

        val path = url.substringBefore('?').lowercase()
        return BLOCKED_PATH_SUFFIXES.any { path.endsWith(it) }
    }
}
