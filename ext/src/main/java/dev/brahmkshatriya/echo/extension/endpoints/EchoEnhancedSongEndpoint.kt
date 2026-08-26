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
 * Optimized to try ytm-kt first, then conditionally fetch legacy if needed.
 */
class EchoEnhancedSongEndpoint(
    override val api: YoutubeiApi,
    private val echoSongEndpoint: EchoSongEndPoint
) : ApiEndpoint() {
    /**
     * Load track data by combining ytm-kt LoadSong and custom EchoSongEndpoint.
     * Optimized to try ytm-kt first, then conditionally fetch legacy only if needed.
     *
     * @param trackId YouTube video/song ID
     * @param fallbackTrack Original track for fallback data
     * @param thumbnailQuality Quality for thumbnail images
     * @return Enhanced Track with merged data from both sources
     */
    suspend fun loadEnhancedTrack(
        trackId: String,
        fallbackTrack: Track,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Track {
        println("EchoEnhancedSongEndpoint: Loading track $trackId, fallback isVideo=${fallbackTrack.extras["isVideo"]}")

        // Try ytm-kt first (faster, better quality data)
        val ytmTrack = runCatching {
            api.LoadSong.loadSong(trackId).getOrThrow()
        }.map { it.toTrack(thumbnailQuality) }.getOrNull()

        if (ytmTrack != null) {
            // Check if we need legacy data for missing extras (lyricsId, relatedId, isLiked)
            val needsLegacyExtras = ytmTrack.extras["lyricsId"] == null ||
                                     ytmTrack.extras["relatedId"] == null ||
                                     ytmTrack.extras["isLiked"] == null

            if (needsLegacyExtras) {
                println("ytm-kt track missing extras, fetching from legacy endpoint")
                val legacyTrack = runCatching {
                    echoSongEndpoint.loadSong(trackId).getOrThrow()
                }.getOrNull()

                val mergedExtras = buildMergedExtras(ytmTrack, legacyTrack, trackId, fallbackTrack)
                val mergedTrack = mergeWithYtmPriority(ytmTrack, legacyTrack, fallbackTrack, mergedExtras)
                return ensureNamedUploader(mergedTrack, trackId, thumbnailQuality)
            } else {
                println("ytm-kt track has all required extras, skipping legacy fetch")
                val mergedExtras = buildMergedExtras(ytmTrack, null, trackId, fallbackTrack)
                val mergedTrack = mergeWithYtmPriority(ytmTrack, null, fallbackTrack, mergedExtras)
                return ensureNamedUploader(mergedTrack, trackId, thumbnailQuality)
            }
        }

        // Fallback to legacy if ytm-kt failed
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

    /**
     * ytm-kt's regular-YouTube fallback can return an artist with only the channel ID.
     * Echo then renders that nameless artist as "Unknown". Keep normal YT Music metadata
     * untouched, but recover the uploader name from the player response when necessary.
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

    private fun Track.hasUsableArtistName(): Boolean = artists.any { artist ->
        val name = artist.name
        name.isNotBlank() &&
            !name.equals("Unknown", ignoreCase = true) &&
            !name.equals("Unknown Artist", ignoreCase = true)
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

        val playerData: PlayerUploaderResponse = response.body()
        val details = playerData.videoDetails ?: return@runCatching null
        val channelId = details.channelId?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val author = details.author?.takeIf { it.isNotBlank() }
            ?: return@runCatching null

        YtmArtist(
            id = channelId,
            name = author
        ).toArtist(thumbnailQuality)
    }.onFailure {
        println("Failed to recover uploader for $trackId: ${it.message}")
    }.getOrNull()

    /**
     * Build merged extras map from all available sources.
     */
    private fun buildMergedExtras(ytmTrack: Track?, legacyTrack: Track?, trackId: String, fallbackTrack: Track? = null): MutableMap<String, String> {
        return mutableMapOf<String, String>().apply {
            // Add ytm extras first (lowest priority)
            ytmTrack?.extras?.let {
                println("  ytm extras: $it")
                putAll(it)
            }

            // Add legacy extras (medium priority - contains lyricsId, relatedId, isLiked)
            legacyTrack?.extras?.let {
                println("  legacy extras: $it")
                putAll(it)
            }

            // Add fallback extras last (HIGHEST priority - preserves isVideo!)
            fallbackTrack?.extras?.let {
                println("  fallback extras: $it")
                putAll(it)
            }

            // Ensure videoId is always present
            if (!containsKey("videoId")) {
                put("videoId", trackId)
            }

            println("  final merged isVideo=${get("isVideo")}")
        }
    }

    /**
     * Merge strategy when ytm-kt track is available (preferred source).
     * Falls back to legacy/original track for missing fields.
     */
    private fun mergeWithYtmPriority(
        ytmTrack: Track,
        legacyTrack: Track?,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>
    ): Track {
        // ytmTrack from ytm-kt NEVER has streamables, so we must create them
        val streamables = if (ytmTrack.streamables.isNotEmpty()) {
            ytmTrack.streamables
        } else {
            createDefaultStreamable(mergedExtras["videoId"]!!)
        }

        return ytmTrack.copy(
            // Prefer ytm cover, fallback to original then legacy
            cover = ytmTrack.cover ?: fallbackTrack.cover ?: legacyTrack?.cover,

            // Prefer ytm album, fallback to legacy
            album = ytmTrack.album ?: legacyTrack?.album,

            // Prefer ytm artists if non-empty, fallback to legacy then original
            artists = if (ytmTrack.artists.isNotEmpty())
                ytmTrack.artists
            else
                legacyTrack?.artists ?: fallbackTrack.artists,

            // Add streamables - THIS WAS MISSING!
            streamables = streamables,

            // Use merged extras with all available metadata
            extras = mergedExtras
        )
    }

    /**
     * Merge strategy when only legacy track is available.
     * Ensures streamables are always present.
     */
    private fun mergeWithLegacyPriority(
        legacyTrack: Track,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>
    ): Track {
        return legacyTrack.copy(
            extras = mergedExtras,
            streamables = legacyTrack.streamables.takeIf { it.isNotEmpty() }
                ?: createDefaultStreamable(mergedExtras["videoId"]!!)
        )
    }

    /**
     * Create fallback track when both API calls fail.
     * Uses original track data with enhanced streamables.
     */
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

    /**
     * Create default streamable configuration.
     */
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
