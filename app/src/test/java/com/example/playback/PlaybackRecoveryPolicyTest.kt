package com.example.playback

import androidx.media3.common.PlaybackException
import com.example.data.remote.streaming.StreamSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryPolicyTest {

    @Test
    fun `transient network and expired-url errors are recoverable`() {
        assertTrue(
            PlaybackFailurePolicy.isRecoverable(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                StreamSource.INNERTUBE
            )
        )
        assertTrue(
            PlaybackFailurePolicy.isRecoverable(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                StreamSource.INNERTUBE
            )
        )
        assertTrue(
            PlaybackFailurePolicy.isRecoverable(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                StreamSource.INNERTUBE
            )
        )
    }

    @Test
    fun `decoding and malformed-source errors are not retried`() {
        assertFalse(
            PlaybackFailurePolicy.isRecoverable(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                StreamSource.INNERTUBE
            )
        )
        assertFalse(
            PlaybackFailurePolicy.isRecoverable(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                StreamSource.INNERTUBE
            )
        )
    }

    @Test
    fun `remote local-source errors are not retried, but a vanished file may go online`() {
        assertFalse(
            PlaybackFailurePolicy.isRecoverable(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                StreamSource.LOCAL
            )
        )
        assertTrue(
            PlaybackFailurePolicy.isRecoverable(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                StreamSource.LOCAL
            )
        )
    }

    @Test
    fun `recovery count is bounded`() {
        assertTrue(PlaybackFailurePolicy.canRecover(0))
        assertTrue(PlaybackFailurePolicy.canRecover(1))
        assertFalse(PlaybackFailurePolicy.canRecover(2))
        assertFalse(PlaybackFailurePolicy.canRecover(99))
    }

    @Test
    fun `stale media callbacks are ignored`() {
        assertTrue(PlaybackEventPolicy.isForCurrentTrack("B", "B"))
        assertFalse(PlaybackEventPolicy.isForCurrentTrack("A", "B"))
        assertTrue(PlaybackEventPolicy.shouldHandleEnded("B", "B", "A"))
        assertFalse(PlaybackEventPolicy.shouldHandleEnded("B", "B", "B"))
        assertFalse(PlaybackEventPolicy.shouldHandleEnded("A", "B", null))
    }
}
