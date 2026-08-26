package dev.brahmkshatriya.echo.extension.providers.playback

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager
import dev.brahmkshatriya.echo.extension.endpoints.EchoEnhancedSongEndpoint
import dev.brahmkshatriya.echo.extension.streaming.YouTubeStreamResolver
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import kotlinx.coroutines.CancellationException


class TrackLoader(
    private val authManager: YouTubeAuthManager,
    private val enhancedSongEndpoint: EchoEnhancedSongEndpoint,
    private val streamResolver: YouTubeStreamResolver
) {
    private val detailsCache = mutableMapOf<String, Track>()

    private fun Track.hasUsableArtistName(): Boolean = artists.any { artist ->
        val name = artist.name?.trim().orEmpty()
        name.isNotEmpty() &&
            !name.equals("Unknown", ignoreCase = true) &&
            !name.equals("Unknown Artist", ignoreCase = true)
    }

    private fun mergeQueueMetadata(original: Track, loaded: Track): Track = loaded.copy(
        extras = original.extras + loaded.extras
    )

    suspend fun loadTrackDetails(
        track: Track,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Track {

        try {
            authManager.ensureVisitorId().getOrNull()
        } catch (e: Exception) {
            println("Failed to ensure visitor ID in loadTrack: ${e.message}")
        }

        val loaded = enhancedSongEndpoint.loadEnhancedTrack(track.id, track, thumbnailQuality)
        detailsCache[track.id] = loaded
        return loaded
    }

    suspend fun hydrateQueueTrack(
        track: Track,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Track {
        if (track.hasUsableArtistName()) {
            return track
        }

        detailsCache[track.id]
            ?.takeIf { it.hasUsableArtistName() }
            ?.let { return mergeQueueMetadata(track, it) }

        return try {
            val loaded = loadTrackDetails(track, thumbnailQuality)
            if (loaded.hasUsableArtistName()) {
                mergeQueueMetadata(track, loaded)
            } else {
                track
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Failed to hydrate queue metadata for ${track.id}: ${e.message}")
            track
        }
    }

    suspend fun loadStreamableMedia(
        streamable: Streamable,
        preferVideos: Boolean
    ): Streamable.Media {
        when (streamable.type) {
            Streamable.MediaType.Server -> {
                val videoId = streamable.extras["videoId"]
                    ?: throw Exception("No video ID found. This track may not be playable.")

                return streamResolver.resolveStreamable(videoId, preferVideos)
            }
            Streamable.MediaType.Background -> {
                throw Exception("Background streamables not supported")
            }
            Streamable.MediaType.Subtitle -> {
                throw Exception("Subtitles not supported")
            }
        }
    }
}
