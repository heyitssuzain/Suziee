package com.example.playback

import androidx.media3.common.PlaybackException
import com.example.data.remote.streaming.StreamSource

/**
 * Bounded recovery policy for ExoPlayer failures.
 *
 * A stale googlevideo URL, a dropped CDN connection or a temporary timeout is worth one or
 * two fresh resolutions. A decode/parse/unsupported-format failure is not: retrying those
 * only delays the honest error. The limit is shared by every error path so a broken URL
 * cannot spin forever.
 */
internal object PlaybackFailurePolicy {

    const val MAX_AUTOMATIC_RECOVERIES = 2

    fun canRecover(recoveriesUsed: Int): Boolean =
        recoveriesUsed in 0 until MAX_AUTOMATIC_RECOVERIES

    /**
     * @param errorCode one of [PlaybackException]'s `ERROR_CODE_*` values.
     * @param source the source that produced the failed item.
     */
    fun isRecoverable(errorCode: Int, source: StreamSource): Boolean {
        // A downloaded file that vanished can still be played online. Re-resolve once;
        // the player otherwise dead-ends even though the track itself is available.
        val localFileMissing = source == StreamSource.LOCAL &&
            errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        if (source == StreamSource.LOCAL && !localFileMissing) return false

        return localFileMissing || when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT -> true

            else -> false
        }
    }
}
