package com.example.couple

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoupleCodecTest {

    @Test
    fun base64UrlRoundTripsEveryRemainderLength() {
        for (size in 0..12) {
            val bytes = ByteArray(size) { (it * 37 + 11).toByte() }
            val encoded = CoupleBase64.encode(bytes)
            assertFalse("padding must not be emitted", encoded.contains('='))
            assertArrayEquals(bytes, CoupleBase64.decode(encoded))
        }
    }

    @Test
    fun base64DecoderIgnoresWhitespaceAndPadding() {
        val bytes = "Suziee".toByteArray()
        val encoded = CoupleBase64.encode(bytes)
        assertArrayEquals(bytes, CoupleBase64.decode(" $encoded\r\n"))
        assertNull(CoupleBase64.decode("not*base64"))
    }

    @Test
    fun linkRoundTripsGzippedJson() {
        val json = """{"hello":"world","items":[1,2,3]}"""
        val link = CoupleCodec.encodeLink(CoupleCodec.KIND_INVITE, json)
        assertTrue(link.startsWith(CoupleCodec.PREFIX))
        val decoded = CoupleCodec.decodeLink(link)
        assertNotNull(decoded)
        assertEquals(CoupleCodec.KIND_INVITE, decoded!!.kind)
        assertEquals(json, decoded.payloadJson)
    }

    @Test
    fun prefixlessKindDataIsAccepted() {
        val json = """{"v":1}"""
        val full = CoupleCodec.encodeLink(CoupleCodec.KIND_OFFER, json)
        val body = full.removePrefix(CoupleCodec.PREFIX)
        val decoded = CoupleCodec.decodeLink(body)
        assertNotNull(decoded)
        assertEquals(CoupleCodec.KIND_OFFER, decoded!!.kind)
        assertEquals(json, decoded.payloadJson)
    }

    @Test
    fun normalizeExtractsALinkFromSurroundingText() {
        val json = """{"v":1}"""
        val link = CoupleCodec.encodeLink(CoupleCodec.KIND_ACCEPT, json)
        val normalized = CoupleCodec.normalizeIncoming("Here is my code:\n$link\nThanks!")
        assertEquals(link, normalized)
    }

    @Test
    fun malformedOrUnknownLinksAreRejected() {
        assertNull(CoupleCodec.decodeLink(""))
        assertNull(CoupleCodec.decodeLink("other:abc"))
        assertNull(CoupleCodec.decodeLink("invite:!!!!"))
        assertNull(CoupleCodec.decodeLink("invite:"))
        assertNull(CoupleCodec.decodeLink(CoupleCodec.PREFIX + "invite/"))
        assertFalse(CoupleCodec.isCoupleLink("hello"))
        assertTrue(CoupleCodec.isCoupleLink("confirm:abc"))
    }

    @Test
    fun envelopeRoundTripsThroughTheEventKind() {
        val envelope = CoupleEnvelope(
            id = "event_12345678",
            sender = "peer0123456789ab",
            type = CoupleProtocol.EVENT_CHAT_MESSAGE,
            seq = 4L,
            ts = 1_700_000_000_000L,
            payload = CoupleJson.encode(ChatPayload(body = "hi", sentAt = 1_700_000_000_000L))
        )
        val encoded = CoupleCodec.encodeEnvelope(envelope)
        val decoded = CoupleCodec.decodeEnvelope(encoded)
        assertEquals(envelope, decoded)
    }

    @Test
    fun eventLinkIsNotOfferedToThePairingRouter() {
        val envelope = CoupleEnvelope(id = "event_12345678", sender = "peer", type = "chat.message")
        val encoded = CoupleCodec.encodeEnvelope(envelope)
        assertEquals(CoupleCodec.KIND_EVENT, CoupleCodec.decodeLink(encoded)!!.kind)
    }
}
