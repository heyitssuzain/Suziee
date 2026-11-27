package com.example.couple

import com.example.data.local.entities.CoupleMessageEntity
import com.example.data.local.entities.DeliveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The storage-side half of the chat delivery contract.
 *
 * A row is persisted as PENDING before any network attempt. Nothing in this test (or in the
 * entity defaults) marks a message SENT or DELIVERED just because it was saved locally; the
 * controller changes those fields only after a DataChannel write and a peer acknowledgement.
 */
class CoupleChatPolicyTest {

    private val now = 1_700_000_000_000L

    private fun outbound(body: String = "hello") = CoupleMessageEntity(
        eventId = "event_12345678",
        peerId = "peer0123456789ab",
        direction = CoupleMessageEntity.DIRECTION_OUT,
        body = body,
        createdAt = now,
        sequence = 1L
    )

    @Test
    fun aSavedOutboundMessageIsPendingNotDelivered() {
        val message = outbound()
        assertEquals(DeliveryState.PENDING, message.deliveryState)
        assertNull(message.sentAt)
        assertNull(message.deliveredAt)
        assertNull(message.error)
        assertNotEquals(DeliveryState.SENT, message.deliveryState)
        assertNotEquals(DeliveryState.DELIVERED, message.deliveryState)
    }

    @Test
    fun sentAndDeliveredAreExplicitTransitions() {
        val sent = outbound().copy(sentAt = now + 1, deliveryState = DeliveryState.SENT)
        assertEquals(DeliveryState.SENT, sent.deliveryState)
        assertNull(sent.deliveredAt)

        val delivered = sent.copy(deliveredAt = now + 2, deliveryState = DeliveryState.DELIVERED)
        assertEquals(DeliveryState.DELIVERED, delivered.deliveryState)
        assertEquals(now + 2, delivered.deliveredAt)
    }

    @Test
    fun failedKeepsTheReasonAndCanBeRetried() {
        val failed = outbound().copy(
            deliveryState = DeliveryState.FAILED,
            error = "Device unpaired"
        )
        assertEquals(DeliveryState.FAILED, failed.deliveryState)
        assertEquals("Device unpaired", failed.error)

        val retried = failed.copy(deliveryState = DeliveryState.PENDING, error = null)
        assertEquals(DeliveryState.PENDING, retried.deliveryState)
        assertNull(retried.error)
    }

    @Test
    fun chatBodiesAreBoundedAndSanitizedBeforeStorage() {
        val oversized = CoupleValidator.sanitizeChatBody("a".repeat(CoupleProtocol.MAX_CHAT_BODY + 500))
        assertEquals(CoupleProtocol.MAX_CHAT_BODY, oversized.length)

        val cleaned = CoupleValidator.sanitizeChatBody("line one\r\nline two\u0000")
        assertEquals("line one\nline two", cleaned)
    }

    @Test
    fun directionConstantsAreDistinct() {
        assertNotEquals(
            CoupleMessageEntity.DIRECTION_IN,
            CoupleMessageEntity.DIRECTION_OUT
        )
    }
}
