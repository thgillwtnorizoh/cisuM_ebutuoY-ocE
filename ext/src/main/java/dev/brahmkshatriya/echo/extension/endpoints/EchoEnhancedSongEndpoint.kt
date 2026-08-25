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
    companion object {
        private const val DEBUG_TAG = "FartniteArtistDebug"
    }

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
        println("$DEBUG_TAG TRACK_BEGIN id=$trackId title=${fallbackTrack.title}")
        println("$DEBUG_TAG FALLBACK_ARTISTS id=$trackId ${formatArtists(fallbackTrack)}")

        // Try ytm-kt first (faster, better quality data)
        val ytmResult = runCatching {
            api.LoadSong.loadSong(trackId).getOrThrow()
        }
        ytmResult.exceptionOrNull()?.let {
            println("$DEBUG_TAG YTM_LOAD_ERROR id=$trackId ${it::class.simpleName}:${it.message}")
        }
        val ytmTrack = ytmResult.getOrNull()?.toTrack(thumbnailQuality)
        println("$DEBUG_TAG YTM_ARTISTS id=$trackId ${formatArtists(ytmTrack)}")

        if (ytmTrack != null) {
            // Check if we need legacy data for missing extras (lyricsId, relatedId, isLiked)
            val needsLegacyExtras = ytmTrack.extras["lyricsId"] == null ||
                                     ytmTrack.extras["relatedId"] == null ||
                                     ytmTrack.extras["isLiked"] == null

            if (needsLegacyExtras) {
                println("ytm-kt track missing extras, fetching from legacy endpoint")
                val legacyResult = runCatching {
                    echoSongEndpoint.loadSong(trackId).getOrThrow()
                }
                legacyResult.exceptionOrNull()?.let {
                    println("$DEBUG_TAG LEGACY_LOAD_ERROR id=$trackId ${it::class.simpleName}:${it.message}")
                }
                val legacyTrack = legacyResult.getOrNull()
                println("$DEBUG_TAG LEGACY_ARTISTS id=$trackId ${formatArtists(legacyTrack)}")

                val mergedExtras = buildMergedExtras(ytmTrack, legacyTrack, trackId, fallbackTrack)
                val merged = mergeWithYtmPriority(ytmTrack, legacyTrack, fallbackTrack, mergedExtras)
                println("$DEBUG_TAG FINAL_ARTISTS id=$trackId ${formatArtists(merged)}")
                println("$DEBUG_TAG TRACK_END id=$trackId")
                return merged
            } else {
                println("ytm-kt track has all required extras, skipping legacy fetch")
                println("$DEBUG_TAG LEGACY_SKIPPED id=$trackId")
                val mergedExtras = buildMergedExtras(ytmTrack, null, trackId, fallbackTrack)
                val merged = mergeWithYtmPriority(ytmTrack, null, fallbackTrack, mergedExtras)
                println("$DEBUG_TAG FINAL_ARTISTS id=$trackId ${formatArtists(merged)}")
                println("$DEBUG_TAG TRACK_END id=$trackId")
                return merged
            }
        }

        // Fallback to legacy if ytm-kt failed
        println("ytm-kt failed, trying legacy endpoint")
        val legacyResult = runCatching {
            echoSongEndpoint.loadSong(trackId).getOrThrow()
        }
        legacyResult.exceptionOrNull()?.let {
            println("$DEBUG_TAG LEGACY_LOAD_ERROR id=$trackId ${it::class.simpleName}:${it.message}")
        }
        val legacyTrack = legacyResult.getOrNull()
        println("$DEBUG_TAG LEGACY_ARTISTS id=$trackId ${formatArtists(legacyTrack)}")

        val mergedExtras = buildMergedExtras(null, legacyTrack, trackId, fallbackTrack)

        val merged = when {
            legacyTrack != null -> mergeWithLegacyPriority(legacyTrack, fallbackTrack, mergedExtras)
            else -> createFallbackTrack(fallbackTrack, mergedExtras, trackId)
        }
        println("$DEBUG_TAG FINAL_ARTISTS id=$trackId ${formatArtists(merged)}")
        println("$DEBUG_TAG TRACK_END id=$trackId")
        return merged
    }

    private fun formatArtists(track: Track?): String {
        if (track == null) return "<track-null>"
        if (track.artists.isEmpty()) return "<empty>"
        return track.artists.joinToString(prefix = "[", postfix = "]") {
            "{id=${it.id},name=${it.name}}"
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
