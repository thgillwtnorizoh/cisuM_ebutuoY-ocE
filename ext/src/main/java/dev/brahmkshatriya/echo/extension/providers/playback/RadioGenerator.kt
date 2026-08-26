package dev.brahmkshatriya.echo.extension.providers.playback

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.ModelTypeHelper
import dev.brahmkshatriya.echo.extension.endpoints.PatchedSongRadioEndpoint
import dev.brahmkshatriya.echo.extension.toTrack
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.brahmkshatriya.echo.common.helpers.PagedData


class RadioGenerator(
    private val api: YoutubeiApi,
    private val json: Json,
    private val thumbnailQuality: ThumbnailProvider.Quality,
    private val trackCache: MutableMap<String, PagedData<Track>>,
    private val trackLoader: TrackLoader
) {
    // ytm-kt 0.4.3's built-in SongRadio parser assumes every watch-next tab
    // contains musicQueueRenderer. YouTube no longer guarantees that, so use
    // our tolerant raw-JSON endpoint for song radio only.
    private val songRadioEndpoint = PatchedSongRadioEndpoint(api)

    suspend fun generateRadio(item: EchoMediaItem, context: EchoMediaItem? = null): Radio {
        return when (item) {
            is Track -> generateFromTrack(item, context)
            is Album -> generateFromAlbum(item)
            is Artist -> generateFromArtist(item)
            is Playlist -> generateFromPlaylist(item)
            else -> throw Exception("Radio not supported for ${item::class.simpleName}")
        }
    }

    private suspend fun generateFromTrack(track: Track, context: EchoMediaItem?): Radio {
        val id = "radio_${track.id}"
        val cont = context?.extras?.get("cont")

        return try {
            val result = songRadioEndpoint.getSongRadio(track.id, cont).getOrThrow()
            val tracks = buildList {
                for (song in result.items) {
                    val queueTrack = song.toTrack(thumbnailQuality)
                    add(trackLoader.hydrateQueueTrack(queueTrack, thumbnailQuality))
                }
            }

            Radio(
                id = id,
                title = "${track.title} Radio",
                extras = mutableMapOf<String, String>().apply {
                    put("tracks", json.encodeToString(tracks))
                    result.continuation?.let { put("cont", it) }
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Last-resort parachute. If YouTube changes /next yet again, keep
            // ordinary playback usable instead of crashing the extension.
            val hydratedFallback = trackLoader.hydrateQueueTrack(track, thumbnailQuality)
            Radio(
                id = id,
                title = "${track.title} Radio",
                extras = mutableMapOf(
                    "tracks" to json.encodeToString(listOf(hydratedFallback))
                )
            )
        }
    }

    private suspend fun generateFromAlbum(album: Album): Radio {
        val track = trackCache[album.id]?.toFeed()?.loadAll()?.lastOrNull()
            ?: throw Exception("No tracks found")
        return generateFromTrack(track, null)
    }

    private suspend fun generateFromArtist(artist: Artist): Radio {
        val id = "radio_${artist.id}"
        val result = api.ArtistRadio.getArtistRadio(artist.id, null).getOrThrow()
        val tracks = buildList {
            for (song in result.items) {
                val queueTrack = song.toTrack(thumbnailQuality)
                add(trackLoader.hydrateQueueTrack(queueTrack, thumbnailQuality))
            }
        }

        return Radio(
            id = id,
            title = "${artist.name} Radio",
            extras = mutableMapOf<String, String>().apply {
                put("tracks", json.encodeToString(tracks))
            }
        )
    }

    private suspend fun generateFromPlaylist(playlist: Playlist): Radio {
        val track = trackCache[playlist.id]?.toFeed()?.loadAll()?.lastOrNull()
            ?: throw Exception("No tracks found")
        return generateFromTrack(track, null)
    }

    private suspend fun generateFromUser(user: User): Radio {
        val artist = ModelTypeHelper.userToArtist(user)
        return generateFromArtist(artist)
    }

    fun loadRadioTracks(radio: Radio): Feed<Track> {
        return PagedData.Single {
            val tracksJson = radio.extras["tracks"]
                ?: throw Exception("No tracks found in radio")
            json.decodeFromString<List<Track>>(tracksJson)
        }.toFeed()
    }
}
