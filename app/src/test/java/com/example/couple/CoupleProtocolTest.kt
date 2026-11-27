package com.example.couple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoupleProtocolTest {

    private val peer = "peer0123456789ab"
    private val now = 1_700_000_000_000L

    private fun envelope(
        type: String = CoupleProtocol.EVENT_CHAT_MESSAGE,
        payload: String = CoupleJson.encode(ChatPayload(body = "hello", sentAt = now)),
        id: String = "evt_12345678",
        sender: String = peer,
        seq: Long = 1L,
        ts: Long = now,
        session: String? = null,
        version: Int = CoupleProtocol.VERSION
    ): String = CoupleJson.encode(
        CoupleEnvelope(
            v = version,
            id = id,
            sender = sender,
            type = type,
            seq = seq,
            ts = ts,
            session = session,
            payload = payload
        )
    )

    @Test
    fun validChatEnvelopeIsAccepted() {
        val result = CoupleValidator.validate(envelope(), peer, now)
        assertTrue(result is CoupleValidation.Valid)
    }

    @Test
    fun senderMustMatchThePairedPeer() {
        val result = CoupleValidator.validate(envelope(sender = "someone_else"), peer, now)
        assertTrue(result is CoupleValidation.Invalid)
        assertEquals("unexpected sender", (result as CoupleValidation.Invalid).reason)
    }

    @Test
    fun unsupportedVersionIsRejected() {
        val result = CoupleValidator.validate(envelope(version = 2), peer, now)
        assertTrue(result is CoupleValidation.Invalid)
    }

    @Test
    fun invalidEventIdIsRejected() {
        val result = CoupleValidator.validate(envelope(id = "bad id"), peer, now)
        assertTrue(result is CoupleValidation.Invalid)
        assertEquals("invalid event id", (result as CoupleValidation.Invalid).reason)
    }

    @Test
    fun oversizedFrameIsRejectedBeforeJsonParsing() {
        val raw = envelope(payload = "x".repeat(CoupleProtocol.MAX_ENVELOPE_BYTES + 1))
        val result = CoupleValidator.validate(raw, peer, now)
        assertTrue(result is CoupleValidation.Invalid)
        assertEquals("frame too large", (result as CoupleValidation.Invalid).reason)
    }

    @Test
    fun unknownEventTypeIsRejected() {
        val result = CoupleValidator.validate(envelope(type = "chat.delete"), peer, now)
        assertTrue(result is CoupleValidation.Invalid)
        assertEquals("unknown event type", (result as CoupleValidation.Invalid).reason)
    }

    @Test
    fun blankChatBodyIsRejected() {
        val payload = CoupleJson.encode(ChatPayload(body = " \r\n\u0000 ", sentAt = now))
        val result = CoupleValidator.validate(envelope(payload = payload), peer, now)
        assertTrue(result is CoupleValidation.Invalid)
        assertEquals("empty chat body", (result as CoupleValidation.Invalid).reason)
    }

    @Test
    fun chatOlderThanTheSessionWindowIsStillAccepted() {
        val tenDaysAgo = now - 10L * 24 * 60 * 60 * 1000
        val payload = CoupleJson.encode(ChatPayload(body = "queued", sentAt = tenDaysAgo))
        val result = CoupleValidator.validate(envelope(payload = payload, ts = tenDaysAgo), peer, now)
        assertTrue(result is CoupleValidation.Valid)
    }

    @Test
    fun chatTooFarInTheFutureIsRejected() {
        val future = now + CoupleProtocol.MAX_CLOCK_SKEW_MS + 1
        val payload = CoupleJson.encode(ChatPayload(body = "future", sentAt = future))
        val result = CoupleValidator.validate(envelope(payload = payload, ts = future), peer, now)
        assertTrue(result is CoupleValidation.Invalid)
    }

    @Test
    fun sessionBoundEventRequiresASession() {
        val payload = CoupleJson.encode(
            PresencePayload(online = true, activity = "listening", displayName = "A")
        )
        val missing = CoupleValidator.validate(
            envelope(type = CoupleProtocol.EVENT_PRESENCE, payload = payload),
            peer,
            now
        )
        assertTrue(missing is CoupleValidation.Invalid)
        assertEquals("missing session", (missing as CoupleValidation.Invalid).reason)

        val present = CoupleValidator.validate(
            envelope(
                type = CoupleProtocol.EVENT_PRESENCE,
                payload = payload,
                session = "session_123"
            ),
            peer,
            now
        )
        assertTrue(present is CoupleValidation.Valid)
    }

    @Test
    fun staleSessionBoundEventIsRejected() {
        val payload = CoupleJson.encode(ClockPayload(kind = ClockPayload.KIND_PING, t0 = now))
        val stale = now - CoupleProtocol.MAX_CLOCK_SKEW_MS - 1
        val result = CoupleValidator.validate(
            envelope(
                type = CoupleProtocol.EVENT_CLOCK_PING,
                payload = payload,
                ts = stale,
                session = "session_123"
            ),
            peer,
            now
        )
        assertTrue(result is CoupleValidation.Invalid)
    }

    @Test
    fun playlistActorAndClockAreValidated() {
        val track = PlaylistItemPayload(
            trackId = "track-1",
            title = "Song",
            artist = "Artist",
            position = 0,
            lamport = 1L,
            updatedBy = "actor-a"
        )
        val valid = CoupleJson.encode(
            PlaylistPayload(
                op = PlaylistPayload.OP_ADD,
                track = track,
                lamport = 1L,
                actor = "actor-a"
            )
        )
        assertTrue(
            CoupleValidator.validate(
                envelope(type = CoupleProtocol.EVENT_PLAYLIST, payload = valid),
                peer,
                now
            ) is CoupleValidation.Valid
        )

        val badActor = CoupleJson.encode(
            PlaylistPayload(op = PlaylistPayload.OP_ADD, track = track, lamport = 1L, actor = "")
        )
        assertTrue(
            CoupleValidator.validate(
                envelope(type = CoupleProtocol.EVENT_PLAYLIST, payload = badActor),
                peer,
                now
            ) is CoupleValidation.Invalid
        )

        val badClock = CoupleJson.encode(
            PlaylistPayload(op = PlaylistPayload.OP_ADD, track = track, lamport = -1L, actor = "actor-a")
        )
        assertTrue(
            CoupleValidator.validate(
                envelope(type = CoupleProtocol.EVENT_PLAYLIST, payload = badClock),
                peer,
                now
            ) is CoupleValidation.Invalid
        )
    }

    @Test
    fun displayNameIsBounded() {
        val payload = CoupleJson.encode(
            PresencePayload(online = true, activity = "idle", displayName = "n".repeat(41))
        )
        val result = CoupleValidator.validate(
            envelope(
                type = CoupleProtocol.EVENT_PRESENCE,
                payload = payload,
                session = "session_123"
            ),
            peer,
            now
        )
        assertTrue(result is CoupleValidation.Invalid)
    }

    @Test
    fun chatSanitizerStripsControlCharactersAndTrims() {
        val cleaned = CoupleValidator.sanitizeChatBody("  hello\r\nworld\u0000  ")
        assertEquals("hello\nworld", cleaned)
    }

    @Test
    fun sequenceTrackerRejectsDuplicatesAndReplays() {
        val tracker = CoupleSequenceTracker()
        assertTrue(tracker.accept(CoupleChannel.CHAT.wireName, 1L, "event-1"))
        assertFalse(tracker.accept(CoupleChannel.CHAT.wireName, 1L, "event-2"))
        assertFalse(tracker.accept(CoupleChannel.CHAT.wireName, 1L, "event-1"))
        assertTrue(tracker.accept(CoupleChannel.CHAT.wireName, 2L, "event-3"))

        // A different channel has its own counter (event ids are globally unique).
        assertTrue(tracker.accept(CoupleChannel.PLAYLIST.wireName, 1L, "event-p1"))

        // Seeding from the persisted peer record prevents a reconnect replay.
        tracker.seed(CoupleChannel.CHAT.wireName, 10L)
        assertFalse(tracker.accept(CoupleChannel.CHAT.wireName, 5L, "event-4"))
        assertTrue(tracker.accept(CoupleChannel.CHAT.wireName, 11L, "event-5"))
    }

    @Test
    fun sessionResetDoesNotForgetChatOrPlaylistCounters() {
        val tracker = CoupleSequenceTracker()
        tracker.accept(CoupleChannel.CHAT.wireName, 4L, "chat-1")
        tracker.accept(CoupleChannel.SYNC.wireName, 9L, "sync-1")
        tracker.resetSessionChannels()
        assertEquals(4L, tracker.lastAccepted(CoupleChannel.CHAT.wireName))
        assertEquals(0L, tracker.lastAccepted(CoupleChannel.SYNC.wireName))
    }
}
