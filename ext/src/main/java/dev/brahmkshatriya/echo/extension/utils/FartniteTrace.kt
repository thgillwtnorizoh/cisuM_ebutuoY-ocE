package dev.brahmkshatriya.echo.extension.utils

import dev.brahmkshatriya.echo.common.models.Track
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist

/**
 * Disposable diagnostic trace carried through Track.extras.
 * Echo Testing already records extras keys, so numbered keys let one playback
 * reconstruct Fartnite's internal decision path without filesystem access.
 */
class FartniteTrace {
    private val events = mutableListOf<String>()

    fun event(function: String, detail: String) {
        events += "$function $detail"
    }

    fun attach(track: Track): Track {
        val extras = track.extras.toMutableMap()
        events.forEachIndexed { index, event ->
            val number = (index + 1).toString().padStart(3, '0')
            extras["__ftrace_${number}_${sanitize(event)}"] = "1"
        }
        return track.copy(extras = extras)
    }

    companion object {
        fun artists(track: Track?): String = track?.artists
            ?.joinToString(prefix = "[", postfix = "]") { artist ->
                "${artist.id}:${artist.name}"
            } ?: "<null-track>"

        fun ytmArtists(artists: List<YtmArtist>?): String = artists
            ?.joinToString(prefix = "[", postfix = "]") { artist ->
                "${artist.id}:${artist.name ?: "<null-name>"}"
            } ?: "<null-list>"

        fun error(throwable: Throwable?): String = if (throwable == null) {
            "none"
        } else {
            "${throwable::class.simpleName}:${throwable.message ?: "<no-message>"}"
        }

        private fun sanitize(value: String): String = value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .replace(Regex("\\s+"), " ")
            .take(260)
    }
}
