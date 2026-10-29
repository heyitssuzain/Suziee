package com.example.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFilePolicyTest {

    @Test
    fun `completed download requires an existing non-empty file`() {
        assertTrue(
            DownloadFilePolicy.isUsable(
                "/files/downloads/a.webm",
                exists = { true },
                length = { 1_024L }
            )
        )
    }

    @Test
    fun `missing, blank and zero-byte paths are not complete`() {
        assertFalse(DownloadFilePolicy.isUsable(null, exists = { true }, length = { 10L }))
        assertFalse(DownloadFilePolicy.isUsable("", exists = { true }, length = { 10L }))
        assertFalse(
            DownloadFilePolicy.isUsable(
                "/files/downloads/a.webm",
                exists = { false },
                length = { 10L }
            )
        )
        assertFalse(
            DownloadFilePolicy.isUsable(
                "/files/downloads/a.webm",
                exists = { true },
                length = { 0L }
            )
        )
    }
}
