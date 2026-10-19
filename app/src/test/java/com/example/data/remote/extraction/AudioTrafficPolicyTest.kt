package com.example.data.remote.extraction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the traffic diet of the headless extraction engine.
 *
 * The rules are deliberately conservative: ad/telemetry/artwork/cosmetic hosts are blocked,
 * while everything the page's player needs to reach the media request - YouTube itself,
 * Google's static host, the googlevideo CDN and even the video segments - must always be
 * allowed through. A wrong "block" here silently breaks official-song extraction, so it is
 * covered by CI instead of being trusted.
 */
class AudioTrafficPolicyTest {

    @Test
    fun `blocks ad and telemetry hosts`() {
        assertTrue(
            AudioTrafficPolicy.shouldBlock(
                "pagead2.googlesyndication.com",
                "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"
            )
        )
        assertTrue(
            AudioTrafficPolicy.shouldBlock(
                "static.doubleclick.net",
                "https://static.doubleclick.net/instream/ad_status.js"
            )
        )
        assertTrue(
            AudioTrafficPolicy.shouldBlock(
                "play.google-analytics.com",
                "https://play.google-analytics.com/collect"
            )
        )
    }

    @Test
    fun `blocks artwork, thumbnails and fonts`() {
        assertTrue(
            AudioTrafficPolicy.shouldBlock(
                "i.ytimg.com",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
            )
        )
        assertTrue(
            AudioTrafficPolicy.shouldBlock(
                "yt3.ggpht.com",
                "https://yt3.ggpht.com/abc=s88-c-k-c0x00ffffff"
            )
        )
        assertTrue(
            AudioTrafficPolicy.shouldBlock(
                "fonts.gstatic.com",
                "https://fonts.gstatic.com/s/roboto/v30/KFOmCnqEu92Fr1Me5Q.ttf"
            )
        )
    }

    @Test
    fun `blocks cosmetics identified by path`() {
        assertTrue(
            AudioTrafficPolicy.shouldBlock(
                "www.youtube.com",
                "https://www.youtube.com/s/desktop/abcdef/_/cssbin/www-main-desktop-home-page-skeleton.css"
            )
        )
        assertTrue(
            AudioTrafficPolicy.shouldBlock(
                "example.com",
                "https://example.com/assets/pixel.png?cache=1"
            )
        )
    }

    @Test
    fun `never blocks the player, the media cdn or the api`() {
        assertFalse(
            AudioTrafficPolicy.shouldBlock(
                "www.youtube.com",
                "https://www.youtube.com/s/player/1234abcd/player_ias.vflset/en_US/base.js"
            )
        )
        assertFalse(
            AudioTrafficPolicy.shouldBlock(
                "www.youtube.com",
                "https://www.youtube.com/youtubei/v1/player?key=AIza"
            )
        )
        assertFalse(
            AudioTrafficPolicy.shouldBlock(
                "www.gstatic.com",
                "https://www.gstatic.com/youtube/static/anything.js"
            )
        )
        assertFalse(
            AudioTrafficPolicy.shouldBlock(
                "rr3---sn-abc.googlevideo.com",
                "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&mime=audio%2Fmp4&itag=140"
            )
        )
    }

    @Test
    fun `never blocks video segments`() {
        // Answering a media request with an empty body makes the page's player re-negotiate,
        // which previously cost us the audio capture - so video is explicitly tolerated.
        assertFalse(
            AudioTrafficPolicy.shouldBlock(
                "rr3---sn-abc.googlevideo.com",
                "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&mime=video%2Fmp4&itag=137"
            )
        )
    }

    @Test
    fun `does not over-block by subdomain`() {
        // googleadservices.com is blocked, but a host that merely *contains* it is not.
        assertFalse(
            AudioTrafficPolicy.shouldBlock(
                "notgoogleadservices.com",
                "https://notgoogleadservices.com/asset.js"
            )
        )
    }
}
