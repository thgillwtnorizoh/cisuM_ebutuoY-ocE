package dev.brahmkshatriya.echo.extension.endpoints

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
    private val echoSongEndpoint: EchoSongEndPoint
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
                return mergeWithYtmPriority(ytmTrack, legacyTrack, fallbackTrack, mergedExtras)
            } else {
                println("ytm-kt track has all required extras, skipping legacy fetch")
                val mergedExtras = buildMergedExtras(ytmTrack, null, trackId, fallbackTrack)
                return mergeWithYtmPriority(ytmTrack, null, fallbackTrack, mergedExtras)
            }
        }

        // Fallback to legacy if ytm-kt failed
        println("ytm-kt failed, trying legacy endpoint")
        val legacyTrack = runCatching {
            echoSongEndpoint.loadSong(trackId).getOrThrow()
        }.getOrNull()

        val mergedExtras = buildMergedExtras(null, legacyTrack, trackId, fallbackTrack)

        return when {
            legacyTrack != null -> mergeWithLegacyPriority(legacyTrack, fallbackTrack, mergedExtras)
            else -> createFallbackTrack(fallbackTrack, mergedExtras, trackId)
        }
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
     * A non-empty artist list is not necessarily useful. ytm-kt can return an
     * artist with a valid browse ID but the placeholder name "Unknown". If we
     * let that win, loading a queued track destroys the good artist name that
     * was already present on the queue item.
     */
    private fun hasUsableArtistName(track: Track?): Boolean =
        track?.artists?.any { artist ->
            val name = artist.name.trim()
            name.isNotEmpty() &&
                !name.equals("Unknown", ignoreCase = true) &&
                !name.equals("Unknown Artist", ignoreCase = true)
        } == true

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

        // Prefer artist metadata only when it contains a real display name.
        // Most importantly, preserve the queue/fallback artist when ytm-kt
        // reloads the same artist ID with the placeholder name "Unknown".
        val artists = when {
            hasUsableArtistName(ytmTrack) -> ytmTrack.artists
            hasUsableArtistName(legacyTrack) -> legacyTrack!!.artists
            hasUsableArtistName(fallbackTrack) -> fallbackTrack.artists
            ytmTrack.artists.isNotEmpty() -> ytmTrack.artists
            !legacyTrack?.artists.isNullOrEmpty() -> legacyTrack!!.artists
            else -> fallbackTrack.artists
        }

        return ytmTrack.copy(
            // Prefer ytm cover, fallback to original then legacy
            cover = ytmTrack.cover ?: fallbackTrack.cover ?: legacyTrack?.cover,

            // Prefer ytm album, fallback to legacy
            album = ytmTrack.album ?: legacyTrack?.album,

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
        val artists = when {
            hasUsableArtistName(legacyTrack) -> legacyTrack.artists
            hasUsableArtistName(fallbackTrack) -> fallbackTrack.artists
            legacyTrack.artists.isNotEmpty() -> legacyTrack.artists
            else -> fallbackTrack.artists
        }

        return legacyTrack.copy(
            artists = artists,
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
