package com.example.data.remote.extraction

/**
 * Decides whether a stream captured from the headless page really belongs to the video the
 * user asked for.
 *
 * ## Why this exists
 * The signed media capture in [SecureExtractionEngine] is taken from whatever the page's
 * own player is fetching. That is normally the requested video - but a watch page can
 * silently move on to a *different* video (a bot/availability interstitial followed by
 * autoplay, a region block, a redirect, a stale document left by the pooled WebView). The
 * request the page then makes is a perfectly valid, `n`-transformed audio stream - for the
 * **wrong song**. Handing that to ExoPlayer is exactly the "official song plays unrelated
 * audio" defect, so a capture is now validated against the video id the page reports.
 *
 * ## Rule: reject only on positive evidence
 * This is deliberately **not** a proof-of-match check. The page's reported id is often
 * unknown (the hook has not run yet, the player is still booting, the page uses a shape we
 * do not recognise), and treating "unknown" as a mismatch is what previously starved every
 * official song of its stream. So:
 *
 *  - reported id unknown/blank/`null` -> **accept** (no evidence, keep waiting is worse),
 *  - reported id == requested id       -> **accept**,
 *  - reported id != requested id       -> **reject** (positive evidence of a different video).
 *
 * Pure JVM logic so the decision is covered by unit tests rather than only by a device.
 */
object AudioIdentityPolicy {

    /** Literal strings a JavaScript "no value" report can take. */
    private val UNKNOWN_LITERALS = setOf("null", "undefined")

    /**
     * True when a captured stream must be dropped because the page reports that it is
     * playing a different video than [requestedVideoId].
     *
     * @param reportedVideoId the video id the page's own player is currently on, or null/
     * blank when that is not known.
     */
    fun isForeignCapture(requestedVideoId: String, reportedVideoId: String?): Boolean {
        if (requestedVideoId.isBlank()) return false
        val reported = clean(reportedVideoId)
        if (reported.isEmpty()) return false
        return reported != requestedVideoId
    }

    /** Normalises a bridge/JS value: trims, unquotes, and maps "null"/"undefined" to blank. */
    fun clean(reportedVideoId: String?): String {
        val text = reportedVideoId?.trim().orEmpty().trim('"').trim()
        return if (text.lowercase() in UNKNOWN_LITERALS) "" else text
    }
}
