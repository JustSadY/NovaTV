package com.novatv.player.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.novatv.player.R

/** Maps Media3 errors to short, user-friendly Turkish messages. */
@OptIn(UnstableApi::class)
object PlayerErrors {

    fun describe(context: Context, e: PlaybackException): String {
        val http = httpCode(e)
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> context.getString(R.string.err_network)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> context.getString(R.string.err_timeout)
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (http) {
                401, 403 -> context.getString(R.string.err_forbidden, http)
                404, 410 -> context.getString(R.string.err_not_found, http)
                in 500..599 -> context.getString(R.string.err_server, http)
                else -> context.getString(R.string.err_http, http ?: 0)
            }
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> context.getString(R.string.err_not_found, http ?: 404)
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> context.getString(R.string.err_cleartext)
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> context.getString(R.string.err_format)
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> context.getString(R.string.err_decoder)
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> context.getString(R.string.err_audio)
            else -> if (isDrm(e)) context.getString(R.string.err_drm) else context.getString(R.string.err_generic, e.errorCodeName)
        }
    }

    /** Errors where an automatic retry cannot help. */
    fun isPermanent(e: PlaybackException): Boolean = when (e.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> true
        else -> isDrm(e)
    }

    fun isFormatError(e: PlaybackException): Boolean =
        e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

    /** HTTP 4xx except 408 (timeout) and 429 (rate limit). */
    fun isClientHttpError(e: Throwable): Boolean {
        val code = httpCode(e) ?: return false
        return code in 400..499 && code != 408 && code != 429
    }

    private fun isDrm(e: PlaybackException) = e.errorCode in 6000..6999

    private fun httpCode(e: Throwable): Int? {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 6) {
            if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
            t = t.cause
            depth++
        }
        return null
    }
}
