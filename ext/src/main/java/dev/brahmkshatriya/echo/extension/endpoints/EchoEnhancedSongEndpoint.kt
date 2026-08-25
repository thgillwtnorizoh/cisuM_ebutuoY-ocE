package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.toTrack
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider

/**
 * Enhanced song endpoint that intelligently combines data from multiple sources.
 * Optimized to try ytm-kt first, then conditionally fetch legacy if needed.
 */
class EchoEnhancedSongEndpoint(
    private val api: YoutubeiApi,
    private val echoSongEndpoint: EchoSongEndPoint,
    private val videoEndpoint: EchoVideoEndpoint
) {
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
                val merged = mergeWithYtmPriority(ytmTrack, legacyTrack, fallbackTrack, mergedExtras)
                return ensureUploaderArtist(trackId, merged)
            } else {
                println("ytm-kt track has all required extras, skipping legacy fetch")
                val mergedExtras = buildMergedExtras(ytmTrack, null, trackId, fallbackTrack)
                val merged = mergeWithYtmPriority(ytmTrack, null, fallbackTrack, mergedExtras)
                return ensureUploaderArtist(trackId, merged)
            }
        }

        // Fallback to legacy if ytm-kt failed
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
     * Last-resort artist recovery for ordinary YouTube uploads.
     *
     * This diagnostic build deliberately puts a DEBUG marker into the artist field
     * when /player recovery fails. If Echo still displays plain "Unknown", then
     * loadTrackDetails() is not the metadata path used by the player UI and the
     * bug must be fixed earlier when the original Track is constructed.
     */
    private suspend fun ensureUploaderArtist(trackId: String, track: Track): Track {
        if (track.artists.isNotEmpty()) return track

        val result = runCatching {
            videoEndpoint.getVideo(resolve = false, id = trackId).first.videoDetails
        }

        val details = result.getOrElse { error ->
            println("Failed to recover uploader for $trackId: ${error.message}")
            return track.copy(
                artists = listOf(
                    Artist(
                        id = "debug_player_error_$trackId",
                        name = "DEBUG: /player request failed (${error::class.simpleName ?: "error"})"
                    )
                )
            )
        }

        val author = details.author?.trim()?.takeIf { it.isNotEmpty() }
        if (author == null) {
            println("/player returned no author for $trackId; channelId=${details.channelId}")
            return track.copy(
                artists = listOf(
                    Artist(
                        id = details.channelId ?: "debug_no_author_$trackId",
                        name = "DEBUG: /player author missing"
                    )
                )
            )
        }

        val channelId = details.channelId?.trim()?.takeIf { it.isNotEmpty() }
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

        // Empty lists are not useful metadata. Prefer the first source that
        // actually contains an artist instead of allowing an empty legacy list
        // to overwrite a correct artist already present on the queue item.
        val artists = when {
            ytmTrack.artists.isNotEmpty() -> ytmTrack.artists
            !legacyTrack?.artists.isNullOrEmpty() -> legacyTrack!!.artists
            fallbackTrack.artists.isNotEmpty() -> fallbackTrack.artists
            else -> emptyList()
        }

        return ytmTrack.copy(
            // Prefer ytm cover, fallback to original then legacy
            cover = ytmTrack.cover ?: fallbackTrack.cover ?: legacyTrack?.cover,

            // Prefer ytm album, fallback to legacy
            album = ytmTrack.album ?: legacyTrack?.album,

            // Preserve whichever source actually has non-empty artist metadata.
            artists = artists,

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
            // A legacy response can exist while still containing no artists.
            // In that case keep the artist metadata from the original queue item.
            artists = legacyTrack.artists.takeIf { it.isNotEmpty() } ?: fallbackTrack.artists,
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