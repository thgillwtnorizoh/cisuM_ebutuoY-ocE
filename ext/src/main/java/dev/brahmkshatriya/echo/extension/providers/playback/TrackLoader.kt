package dev.brahmkshatriya.echo.extension.providers.playback

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager
import dev.brahmkshatriya.echo.extension.endpoints.EchoEnhancedSongEndpoint
import dev.brahmkshatriya.echo.extension.streaming.YouTubeStreamResolver
import dev.brahmkshatriya.echo.extension.utils.FartniteTrace
import dev.toastbits.ytmkt.model.external.ThumbnailProvider


class TrackLoader(
    private val authManager: YouTubeAuthManager,
    private val enhancedSongEndpoint: EchoEnhancedSongEndpoint,
    private val streamResolver: YouTubeStreamResolver
) {
    suspend fun loadTrackDetails(
        track: Track,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Track {
        val trace = FartniteTrace()
        trace.event(
            "TrackLoader.loadTrackDetails",
            "ENTER id=${track.id} inputArtists=${FartniteTrace.artists(track)} extrasKeys=${track.extras.keys.sorted()}"
        )

        try {
            val visitorResult = authManager.ensureVisitorId()
            trace.event(
                "YouTubeAuthManager.ensureVisitorId",
                "RETURN success=${visitorResult.isSuccess} visitorPresent=${visitorResult.getOrNull() != null} error=${FartniteTrace.error(visitorResult.exceptionOrNull())}"
            )
        } catch (e: Exception) {
            trace.event(
                "YouTubeAuthManager.ensureVisitorId",
                "THROW error=${FartniteTrace.error(e)} continuing=true"
            )
            println("Failed to ensure visitor ID in loadTrack: ${e.message}")
        }

        trace.event(
            "TrackLoader.loadTrackDetails",
            "CALL EchoEnhancedSongEndpoint.loadEnhancedTrack id=${track.id}"
        )

        val loaded = enhancedSongEndpoint.loadEnhancedTrack(
            track.id,
            track,
            thumbnailQuality,
            trace
        )

        trace.event(
            "TrackLoader.loadTrackDetails",
            "EXIT id=${loaded.id} outputArtists=${FartniteTrace.artists(loaded)} extrasKeys=${loaded.extras.keys.sorted()}"
        )

        return trace.attach(loaded)
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
