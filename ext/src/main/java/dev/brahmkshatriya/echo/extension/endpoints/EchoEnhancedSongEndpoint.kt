package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.toTrack
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider

/**
 * Enhanced song endpoint that intelligently combines data from multiple sources.
 */
class EchoEnhancedSongEndpoint(
    private val api: YoutubeiApi,
    private val echoSongEndpoint: EchoSongEndPoint,
    private val videoEndpoint: EchoVideoEndpoint
) {
    suspend fun loadEnhancedTrack(
        trackId: String,
        fallbackTrack: Track,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Track {
        println("EchoEnhancedSongEndpoint: Loading track $trackId, fallback isVideo=${fallbackTrack.extras["isVideo"]}")

        val ytmTrack = runCatching {
            api.LoadSong.loadSong(trackId).getOrThrow()
        }.map { it.toTrack(thumbnailQuality) }.getOrNull()

        if (ytmTrack != null) {
            val needsLegacyExtras = ytmTrack.extras["lyricsId"] == null ||
                ytmTrack.extras["relatedId"] == null ||
                ytmTrack.extras["isLiked"] == null

            if (needsLegacyExtras) {
                println("ytm-kt track missing extras, fetching from legacy endpoint")
                val legacyTrack = runCatching {
                    echoSongEndpoint.loadSong(trackId).getOrThrow()
                }.getOrNull()

                val mergedExtras = buildMergedExtras(ytmTrack, legacyTrack, trackId, fallbackTrack)
                val merged = mergeWithYtmPriority(ytmTrack, legacyTrack, fallbackTrack, mergedExtras)
                return ensureUploaderArtist(trackId, merged)
            }

            println("ytm-kt track has all required extras, skipping legacy fetch")
            val mergedExtras = buildMergedExtras(ytmTrack, null, trackId, fallbackTrack)
            val merged = mergeWithYtmPriority(ytmTrack, null, fallbackTrack, mergedExtras)
            return ensureUploaderArtist(trackId, merged)
        }

        println("ytm-kt failed, trying legacy endpoint")
        val legacyTrack = runCatching {
            echoSongEndpoint.loadSong(trackId).getOrThrow()
        }.getOrNull()

        val mergedExtras = buildMergedExtras(null, legacyTrack, trackId, fallbackTrack)
        val merged = when {
            legacyTrack != null -> mergeWithLegacyPriority(legacyTrack, fallbackTrack, mergedExtras)
            else -> createFallbackTrack(fallbackTrack, mergedExtras, trackId)
        }
        return ensureUploaderArtist(trackId, merged)
    }

    /**
     * YouTube/ytm-kt sometimes returns an Artist object with a correct browse ID
     * but a placeholder name such as "Unknown". Such an object is not useful
     * display metadata, even though the list itself is non-empty.
     */
    private fun hasUsefulArtists(artists: List<Artist>): Boolean =
        artists.any { isUsefulArtistName(it.name) }

    private fun isUsefulArtistName(name: String?): Boolean {
        val value = name?.trim().orEmpty()
        if (value.isEmpty()) return false
        return !value.equals("Unknown", ignoreCase = true) &&
            !value.equals("Unknown Artist", ignoreCase = true)
    }

    /**
     * Last-resort artist recovery for ordinary YouTube uploads and broken
     * YouTube Music artist-name metadata. The /player endpoint exposes the
     * uploader in videoDetails.author and channelId.
     */
    private suspend fun ensureUploaderArtist(trackId: String, track: Track): Track {
        if (hasUsefulArtists(track.artists)) return track

        val details = runCatching {
            videoEndpoint.getVideo(resolve = false, id = trackId).first.videoDetails
        }.onFailure {
            println("Failed to recover uploader for $trackId: ${it.message}")
        }.getOrNull() ?: return track

        val author = details.author?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            println("/player returned no author for $trackId; channelId=${details.channelId}")
            return track
        }

        val channelId = details.channelId?.trim()?.takeIf { it.isNotEmpty() }
            ?: track.artists.firstOrNull()?.id
            ?: "youtube_uploader_${author.hashCode()}"

        println("Recovered YouTube uploader for $trackId: $author ($channelId)")
        return track.copy(
            artists = listOf(
                Artist(
                    id = channelId,
                    name = author
                )
            )
        )
    }

    private fun buildMergedExtras(
        ytmTrack: Track?,
        legacyTrack: Track?,
        trackId: String,
        fallbackTrack: Track? = null
    ): MutableMap<String, String> {
        return mutableMapOf<String, String>().apply {
            ytmTrack?.extras?.let {
                println("  ytm extras: $it")
                putAll(it)
            }
            legacyTrack?.extras?.let {
                println("  legacy extras: $it")
                putAll(it)
            }
            fallbackTrack?.extras?.let {
                println("  fallback extras: $it")
                putAll(it)
            }
            if (!containsKey("videoId")) put("videoId", trackId)
            println("  final merged isVideo=${get("isVideo")}")
        }
    }

    private fun mergeWithYtmPriority(
        ytmTrack: Track,
        legacyTrack: Track?,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>
    ): Track {
        val streamables = if (ytmTrack.streamables.isNotEmpty()) {
            ytmTrack.streamables
        } else {
            createDefaultStreamable(mergedExtras["videoId"]!!)
        }

        val artists = when {
            hasUsefulArtists(ytmTrack.artists) -> ytmTrack.artists
            legacyTrack != null && hasUsefulArtists(legacyTrack.artists) -> legacyTrack.artists
            hasUsefulArtists(fallbackTrack.artists) -> fallbackTrack.artists
            else -> emptyList()
        }

        return ytmTrack.copy(
            cover = ytmTrack.cover ?: fallbackTrack.cover ?: legacyTrack?.cover,
            album = ytmTrack.album ?: legacyTrack?.album,
            artists = artists,
            streamables = streamables,
            extras = mergedExtras
        )
    }

    private fun mergeWithLegacyPriority(
        legacyTrack: Track,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>
    ): Track {
        val artists = when {
            hasUsefulArtists(legacyTrack.artists) -> legacyTrack.artists
            hasUsefulArtists(fallbackTrack.artists) -> fallbackTrack.artists
            else -> emptyList()
        }

        return legacyTrack.copy(
            artists = artists,
            extras = mergedExtras,
            streamables = legacyTrack.streamables.takeIf { it.isNotEmpty() }
                ?: createDefaultStreamable(mergedExtras["videoId"]!!)
        )
    }

    private fun createFallbackTrack(
        fallbackTrack: Track,
        mergedExtras: Map<String, String>,
        trackId: String
    ): Track {
        return fallbackTrack.copy(
            extras = mergedExtras,
            streamables = fallbackTrack.streamables.takeIf { it.isNotEmpty() }
                ?: createDefaultStreamable(trackId)
        )
    }

    private fun createDefaultStreamable(videoId: String): List<Streamable> {
        return listOf(
            Streamable.server(
                id = "youtube_music_$videoId",
                quality = 128,
                title = "YouTube Music",
                extras = mapOf("videoId" to videoId)
            )
        )
    }
}
