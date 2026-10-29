package com.example.downloads

import java.io.File

/**
 * A download is only "complete" when the recorded path resolves to a real, non-empty file.
 * Unit-testable through the injected probes so no Android storage is needed.
 */
internal object DownloadFilePolicy {

    fun isUsable(
        path: String?,
        exists: (String) -> Boolean = { File(it).exists() },
        length: (String) -> Long = { File(it).length() }
    ): Boolean {
        if (path.isNullOrBlank()) return false
        return exists(path) && length(path) > 0L
    }
}
