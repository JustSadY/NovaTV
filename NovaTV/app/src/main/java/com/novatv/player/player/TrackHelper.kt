package com.novatv.player.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.novatv.player.R
import java.util.Locale

/** Audio / subtitle track listing and selection for the quick menu. */
@OptIn(UnstableApi::class)
object TrackHelper {

    class Option(val label: String, val group: Tracks.Group?, val trackIndex: Int, val selected: Boolean)

    fun audioOptions(context: Context, player: Player): List<Option> =
        options(context, player, C.TRACK_TYPE_AUDIO)

    /** First option is always "off". */
    fun textOptions(context: Context, player: Player): List<Option> {
        val tracks = options(context, player, C.TRACK_TYPE_TEXT)
        val textDisabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        val anySelected = tracks.any { it.selected } && !textDisabled
        val off = Option(context.getString(R.string.off), null, -1, !anySelected)
        return listOf(off) + tracks.map { Option(it.label, it.group, it.trackIndex, it.selected && !textDisabled) }
    }

    private fun options(context: Context, player: Player, type: Int): List<Option> {
        val out = ArrayList<Option>()
        var n = 0
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                n++
                out.add(Option(label(context, group.getTrackFormat(i), n), group, i, group.isTrackSelected(i)))
            }
        }
        return out
    }

    fun select(player: Player, type: Int, option: Option) {
        val builder = player.trackSelectionParameters.buildUpon()
        val group = option.group
        if (group == null) {
            builder.clearOverridesOfType(type).setTrackTypeDisabled(type, true)
        } else {
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        player.trackSelectionParameters = builder.build()
    }

    fun currentLabel(context: Context, player: Player, type: Int): String {
        val list = if (type == C.TRACK_TYPE_TEXT) textOptions(context, player) else audioOptions(context, player)
        return list.firstOrNull { it.selected }?.label
            ?: if (type == C.TRACK_TYPE_TEXT) context.getString(R.string.off) else context.getString(R.string.audio_default)
    }

    fun label(context: Context, f: Format, n: Int): String {
        val parts = ArrayList<String>(3)
        val lang = f.language
        if (!lang.isNullOrBlank() && lang != C.LANGUAGE_UNDETERMINED && lang != "und") {
            val display = Locale.forLanguageTag(lang).getDisplayLanguage(Locale.getDefault())
            parts.add(display.replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifBlank { lang })
        }
        f.label?.takeIf { it.isNotBlank() && parts.none { p -> p.equals(it, true) } }?.let { parts.add(it) }
        if (MimeTypes.isAudio(f.sampleMimeType)) {
            when (f.channelCount) {
                1 -> parts.add("Mono")
                2 -> parts.add("Stereo")
                6 -> parts.add("5.1")
                8 -> parts.add("7.1")
            }
            codecName(f.sampleMimeType)?.let { parts.add(it) }
        }
        return if (parts.isEmpty()) context.getString(R.string.track_unknown, n) else parts.joinToString(" • ")
    }

    fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MPEG"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "H.265"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_AV1 -> "AV1"
        else -> mime?.substringAfter('/')?.uppercase(Locale.ROOT)
    }
}
