package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.toArtist
import dev.brahmkshatriya.echo.extension.toTrack
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

/**
 * Enhanced song endpoint that intelligently combines data from multiple sources.
 */
class EchoEnhancedSongEndpoint(
    override val api: YoutubeiApi,
    private val echoSongEndpoint: EchoSongEndPoint
) : ApiEndpoint() {

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
                ytmTrack.extras["isLiked"] == null ||
                !ytmTrack.hasUsableArtistName()

            val legacyTrack = if (needsLegacyExtras) {
                println("ytm-kt track incomplete, fetching from legacy endpoint")
                runCatching {
                    echoSongEndpoint.loadSong(trackId).getOrThrow()
                }.getOrNull()
            } else {
                null
            }

            val mergedExtras = buildMergedExtras(ytmTrack, legacyTrack, trackId, fallbackTrack)
            val mergedTrack = mergeWithYtmPriority(
                ytmTrack,
                legacyTrack,
                fallbackTrack,
                mergedExtras
            )
            return ensureNamedUploader(mergedTrack, trackId, thumbnailQuality)
        }

        println("ytm-kt failed, trying legacy endpoint")
        val legacyTrack = runCatching {
            echoSongEndpoint.loadSong(trackId).getOrThrow()
        }.getOrNull()

        val mergedExtras = buildMergedExtras(null, legacyTrack, trackId, fallbackTrack)
        val mergedTrack = when {
            legacyTrack != null -> mergeWithLegacyPriority(legacyTrack, fallbackTrack, mergedExtras)
            else -> createFallbackTrack(fallbackTrack, mergedExtras, trackId)
        }
        return ensureNamedUploader(mergedTrack, trackId, thumbnailQuality)
    }

    private fun Track.hasUsableArtistName(): Boolean = artists.any { artist ->
        val name = artist.name?.trim().orEmpty()
        name.isNotEmpty() &&
            !name.equals("Unknown", ignoreCase = true) &&
            !name.equals("Unknown Artist", ignoreCase = true)
    }

    /**
     * If all normal metadata sources still lack a display name, recover the uploader
     * directly from YouTube's player videoDetails. This runs only for incomplete tracks.
     */
    private suspend fun ensureNamedUploader(
        track: Track,
        trackId: String,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Track {
        if (track.hasUsableArtistName()) return track

        val uploader = loadPlayerUploader(trackId, thumbnailQuality) ?: return track
        println("Recovered uploader for $trackId: ${uploader.name} (${uploader.id})")
        return track.copy(artists = listOf(uploader))
    }

    private suspend fun loadPlayerUploader(
        trackId: String,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Artist? = runCatching {
        val response: HttpResponse = api.client.request {
            endpointPath("player")
            addApiHeadersWithAuthenticated()
            postWithBody {
                put("videoId", trackId)
            }
        }

        val details = response.body<PlayerUploaderResponse>().videoDetails
            ?: return@runCatching null
        val channelId = details.channelId?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val author = details.author?.takeIf { it.isNotBlank() }
            ?: return@runCatching null

        YtmArtist(id = channelId, name = author).toArtist(thumbnailQuality)
    }.onFailure {
        println("Failed to recover uploader for $trackId: ${it.message}")
    }.getOrNull()

    private fun buildMergedExtras(
        ytmTrack: Track?,
        legacyTrack: Track?,
        trackId: String,
        fallbackTrack: Track? = null
    ): MutableMap<String, String> {
        return mutableMapOf<String, String>().apply {
            ytmTrack?.extras?.let { putAll(it) }
            legacyTrack?.extras?.let { putAll(it) }
            fallbackTrack?.extras?.let { putAll(it) }
            if (!containsKey("videoId")) put("videoId", trackId)
        }
    }

    private fun mergeWithYtmPriority(
        ytmTrack: Track,
        legacyTrack: Track?,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>
    ): Track {
        val streamables = ytmTrack.streamables.takeIf { it.isNotEmpty() }
            ?: createDefaultStreamable(mergedExtras["videoId"]!!)

        val artists = when {
            ytmTrack.hasUsableArtistName() -> ytmTrack.artists
            legacyTrack?.hasUsableArtistName() == true -> legacyTrack.artists
            fallbackTrack.hasUsableArtistName() -> fallbackTrack.artists
            else -> ytmTrack.artists.ifEmpty { legacyTrack?.artists ?: fallbackTrack.artists }
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
            legacyTrack.hasUsableArtistName() -> legacyTrack.artists
            fallbackTrack.hasUsableArtistName() -> fallbackTrack.artists
            else -> legacyTrack.artists.ifEmpty { fallbackTrack.artists }
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

@Serializable
private data class PlayerUploaderResponse(
    val videoDetails: VideoDetails? = null
) {
    @Serializable
    data class VideoDetails(
        val channelId: String? = null,
        val author: String? = null
    )
}
