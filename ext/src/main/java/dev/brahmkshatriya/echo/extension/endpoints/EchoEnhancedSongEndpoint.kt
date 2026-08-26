package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.toTrack
import dev.brahmkshatriya.echo.extension.utils.FartniteTrace
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
        thumbnailQuality: ThumbnailProvider.Quality,
        trace: FartniteTrace
    ): Track {
        trace.event(
            "EchoEnhancedSongEndpoint.loadEnhancedTrack",
            "ENTER id=$trackId fallbackArtists=${FartniteTrace.artists(fallbackTrack)} fallbackExtrasKeys=${fallbackTrack.extras.keys.sorted()} isVideo=${fallbackTrack.extras["isVideo"]}"
        )
        println("EchoEnhancedSongEndpoint: Loading track $trackId, fallback isVideo=${fallbackTrack.extras["isVideo"]}")

        trace.event("YoutubeiApi.LoadSong.loadSong", "CALL id=$trackId")
        val ytmSongResult = runCatching {
            api.LoadSong.loadSong(trackId).getOrThrow()
        }
        val rawYtmSong = ytmSongResult.getOrNull()
        if (rawYtmSong != null) {
            trace.event(
                "YoutubeiApi.LoadSong.loadSong",
                "RETURN success=true rawName=${rawYtmSong.name ?: "<null-name>"} rawArtists=${FartniteTrace.ytmArtists(rawYtmSong.artists)}"
            )
        } else {
            trace.event(
                "YoutubeiApi.LoadSong.loadSong",
                "THROW success=false error=${FartniteTrace.error(ytmSongResult.exceptionOrNull())}"
            )
        }

        val ytmTrack = rawYtmSong?.let { song ->
            trace.event(
                "YtmSong.toTrack",
                "CALL rawArtists=${FartniteTrace.ytmArtists(song.artists)}"
            )
            song.toTrack(thumbnailQuality).also { converted ->
                trace.event(
                    "YtmSong.toTrack",
                    "RETURN convertedArtists=${FartniteTrace.artists(converted)} convertedExtrasKeys=${converted.extras.keys.sorted()}"
                )
            }
        }

        if (ytmTrack != null) {
            val missingExtras = buildList {
                if (ytmTrack.extras["lyricsId"] == null) add("lyricsId")
                if (ytmTrack.extras["relatedId"] == null) add("relatedId")
                if (ytmTrack.extras["isLiked"] == null) add("isLiked")
            }
            val needsLegacyExtras = missingExtras.isNotEmpty()
            trace.event(
                "EchoEnhancedSongEndpoint.needsLegacyExtras",
                "DECISION needsLegacy=$needsLegacyExtras missing=$missingExtras ytmArtists=${FartniteTrace.artists(ytmTrack)}"
            )

            if (needsLegacyExtras) {
                println("ytm-kt track missing extras, fetching from legacy endpoint")
                trace.event("EchoSongEndPoint.loadSong", "CALL id=$trackId reason=missingExtras")
                val legacyResult = runCatching {
                    echoSongEndpoint.loadSong(trackId).getOrThrow()
                }
                val legacyTrack = legacyResult.getOrNull()
                if (legacyTrack != null) {
                    trace.event(
                        "EchoSongEndPoint.loadSong",
                        "RETURN success=true artists=${FartniteTrace.artists(legacyTrack)} extrasKeys=${legacyTrack.extras.keys.sorted()}"
                    )
                } else {
                    trace.event(
                        "EchoSongEndPoint.loadSong",
                        "THROW success=false error=${FartniteTrace.error(legacyResult.exceptionOrNull())}"
                    )
                }

                val mergedExtras = buildMergedExtras(ytmTrack, legacyTrack, trackId, fallbackTrack)
                trace.event(
                    "EchoEnhancedSongEndpoint.buildMergedExtras",
                    "RETURN keys=${mergedExtras.keys.sorted()}"
                )
                return mergeWithYtmPriority(
                    ytmTrack,
                    legacyTrack,
                    fallbackTrack,
                    mergedExtras,
                    trace
                ).also { result ->
                    trace.event(
                        "EchoEnhancedSongEndpoint.loadEnhancedTrack",
                        "EXIT branch=ytm+legacy outputArtists=${FartniteTrace.artists(result)}"
                    )
                }
            } else {
                println("ytm-kt track has all required extras, skipping legacy fetch")
                trace.event(
                    "EchoSongEndPoint.loadSong",
                    "SKIP reason=ytmHasRequiredExtras"
                )
                val mergedExtras = buildMergedExtras(ytmTrack, null, trackId, fallbackTrack)
                trace.event(
                    "EchoEnhancedSongEndpoint.buildMergedExtras",
                    "RETURN keys=${mergedExtras.keys.sorted()}"
                )
                return mergeWithYtmPriority(
                    ytmTrack,
                    null,
                    fallbackTrack,
                    mergedExtras,
                    trace
                ).also { result ->
                    trace.event(
                        "EchoEnhancedSongEndpoint.loadEnhancedTrack",
                        "EXIT branch=ytmOnly outputArtists=${FartniteTrace.artists(result)}"
                    )
                }
            }
        }

        println("ytm-kt failed, trying legacy endpoint")
        trace.event("EchoSongEndPoint.loadSong", "CALL id=$trackId reason=ytmFailed")
        val legacyResult = runCatching {
            echoSongEndpoint.loadSong(trackId).getOrThrow()
        }
        val legacyTrack = legacyResult.getOrNull()
        if (legacyTrack != null) {
            trace.event(
                "EchoSongEndPoint.loadSong",
                "RETURN success=true artists=${FartniteTrace.artists(legacyTrack)} extrasKeys=${legacyTrack.extras.keys.sorted()}"
            )
        } else {
            trace.event(
                "EchoSongEndPoint.loadSong",
                "THROW success=false error=${FartniteTrace.error(legacyResult.exceptionOrNull())}"
            )
        }

        val mergedExtras = buildMergedExtras(null, legacyTrack, trackId, fallbackTrack)
        trace.event(
            "EchoEnhancedSongEndpoint.buildMergedExtras",
            "RETURN keys=${mergedExtras.keys.sorted()}"
        )

        return when {
            legacyTrack != null -> mergeWithLegacyPriority(
                legacyTrack,
                fallbackTrack,
                mergedExtras,
                trace
            ).also { result ->
                trace.event(
                    "EchoEnhancedSongEndpoint.loadEnhancedTrack",
                    "EXIT branch=legacyOnly outputArtists=${FartniteTrace.artists(result)}"
                )
            }

            else -> createFallbackTrack(
                fallbackTrack,
                mergedExtras,
                trackId,
                trace
            ).also { result ->
                trace.event(
                    "EchoEnhancedSongEndpoint.loadEnhancedTrack",
                    "EXIT branch=fallbackOnly outputArtists=${FartniteTrace.artists(result)}"
                )
            }
        }
    }

    /**
     * Build merged extras map from all available sources.
     */
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
        mergedExtras: Map<String, String>,
        trace: FartniteTrace
    ): Track {
        val streamables = if (ytmTrack.streamables.isNotEmpty()) {
            ytmTrack.streamables
        } else {
            createDefaultStreamable(mergedExtras["videoId"]!!)
        }

        val artistSource = when {
            ytmTrack.artists.isNotEmpty() -> "YTM_NON_EMPTY"
            legacyTrack != null -> "LEGACY_FALLBACK"
            else -> "ORIGINAL_FALLBACK"
        }
        trace.event(
            "EchoEnhancedSongEndpoint.mergeWithYtmPriority",
            "DECISION artistSource=$artistSource ytmCount=${ytmTrack.artists.size} ytmArtists=${FartniteTrace.artists(ytmTrack)} legacyArtists=${FartniteTrace.artists(legacyTrack)} fallbackArtists=${FartniteTrace.artists(fallbackTrack)} rule=ytmListNonEmptyWins"
        )

        return ytmTrack.copy(
            cover = ytmTrack.cover ?: fallbackTrack.cover ?: legacyTrack?.cover,
            album = ytmTrack.album ?: legacyTrack?.album,
            artists = if (ytmTrack.artists.isNotEmpty())
                ytmTrack.artists
            else
                legacyTrack?.artists ?: fallbackTrack.artists,
            streamables = streamables,
            extras = mergedExtras
        ).also { result ->
            trace.event(
                "EchoEnhancedSongEndpoint.mergeWithYtmPriority",
                "RETURN outputArtists=${FartniteTrace.artists(result)} streamableCount=${result.streamables.size}"
            )
        }
    }

    /**
     * Merge strategy when only legacy track is available.
     * Ensures streamables are always present.
     */
    private fun mergeWithLegacyPriority(
        legacyTrack: Track,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>,
        trace: FartniteTrace
    ): Track {
        trace.event(
            "EchoEnhancedSongEndpoint.mergeWithLegacyPriority",
            "DECISION legacyArtists=${FartniteTrace.artists(legacyTrack)} fallbackArtists=${FartniteTrace.artists(fallbackTrack)} artistSource=LEGACY"
        )
        return legacyTrack.copy(
            extras = mergedExtras,
            streamables = legacyTrack.streamables.takeIf { it.isNotEmpty() }
                ?: createDefaultStreamable(mergedExtras["videoId"]!!)
        ).also { result ->
            trace.event(
                "EchoEnhancedSongEndpoint.mergeWithLegacyPriority",
                "RETURN outputArtists=${FartniteTrace.artists(result)}"
            )
        }
    }

    /**
     * Create fallback track when both API calls fail.
     * Uses original track data with enhanced streamables.
     */
    private fun createFallbackTrack(
        fallbackTrack: Track,
        mergedExtras: Map<String, String>,
        trackId: String,
        trace: FartniteTrace
    ): Track {
        trace.event(
            "EchoEnhancedSongEndpoint.createFallbackTrack",
            "DECISION fallbackArtists=${FartniteTrace.artists(fallbackTrack)} artistSource=ORIGINAL"
        )
        return fallbackTrack.copy(
            extras = mergedExtras,
            streamables = fallbackTrack.streamables.takeIf { it.isNotEmpty() }
                ?: createDefaultStreamable(trackId)
        ).also { result ->
            trace.event(
                "EchoEnhancedSongEndpoint.createFallbackTrack",
                "RETURN outputArtists=${FartniteTrace.artists(result)}"
            )
        }
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
