package dev.brahmkshatriya.echo.extension.endpoints

import dev.toastbits.ytmkt.endpoint.RadioBuilderModifier
import dev.toastbits.ytmkt.endpoint.SongRadioEndpoint
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.itemcache.MediaItemCache
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import io.ktor.client.call.body
import io.ktor.client.request.request
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Local replacement for ytm-kt's song-radio endpoint.
 *
 * YouTube's /next response now contains watch-next tabs whose `content` objects
 * do not necessarily contain `musicQueueRenderer`. ytm-kt 0.4.3 models that
 * field as mandatory, so deserialising the whole response can fail before it
 * reaches the actual queue.
 *
 * This endpoint deliberately parses the outer response as raw JSON, searches
 * every watch-next tab for the queue, and only extracts the fields Echo needs.
 * Keeping it here lets the rest of the extension continue using stock ytm-kt.
 */
class PatchedSongRadioEndpoint(
    override val api: YoutubeiApi
) : SongRadioEndpoint() {

    override suspend fun getSongRadio(
        song_id: String,
        continuation: String?,
        filters: List<RadioBuilderModifier>
    ): Result<RadioData> = runCatching {
        // Preserve ytm-kt's special "artist radio" modifier behaviour.
        if (filters.any { it == RadioBuilderModifier.Internal.ARTIST }) {
            val song = api.item_cache.loadSong(
                api,
                song_id,
                setOf(MediaItemCache.SongKey.ARTIST_ID)
            )
            val artist = song.artists?.firstOrNull()
                ?: throw IllegalStateException("Song $song_id has no artist")
            val radio = api.ArtistRadio.getArtistRadio(artist.id, null).getOrThrow()
            return@runCatching RadioData(radio.items, radio.continuation, null)
        }

        val response = api.client.request {
            endpointPath("next")
            addApiHeadersWithAuthenticated()
            postWithBody {
                put("enablePersistentPlaylistPanel", true)
                put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
                put("playlistId", videoIdToRadio(song_id, filters))
                put("isAudioOnly", true)
                putJsonObject("watchEndpointMusicSupportedConfigs") {
                    putJsonObject("watchEndpointMusicConfig") {
                        put("hasPersistentPlaylistPanel", true)
                        put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")
                    }
                }
                continuation?.let { put("continuation", it) }
            }
        }

        val root: JsonObject = response.body()
        val panel = if (continuation == null) {
            findInitialPlaylistPanel(root)
        } else {
            root["continuationContents"].obj()
                ?.get("playlistPanelContinuation").obj()
        } ?: throw IllegalStateException("YouTube /next response did not contain a playlist panel")

        val items = panel["contents"].arr()
            .orEmpty()
            .mapNotNull { item -> parseSong(item.obj() ?: return@mapNotNull null) }

        if (items.isEmpty()) {
            throw IllegalStateException("YouTube radio returned an empty playlist")
        }

        RadioData(
            items = items,
            continuation = extractContinuation(panel),
            filters = null
        )
    }

    private fun findInitialPlaylistPanel(root: JsonObject): JsonObject? {
        val tabs = root["contents"].obj()
            ?.get("singleColumnMusicWatchNextResultsRenderer").obj()
            ?.get("tabbedRenderer").obj()
            ?.get("watchNextTabbedResultsRenderer").obj()
            ?.get("tabs").arr()
            ?: return null

        // Do NOT assume the first tab is the queue. YouTube changes the order,
        // and some tabs contain other kinds of content entirely.
        return tabs.asSequence()
            .mapNotNull { tab ->
                tab.obj()
                    ?.get("tabRenderer").obj()
                    ?.get("content").obj()
                    ?.get("musicQueueRenderer").obj()
            }
            .mapNotNull { queue ->
                queue["content"].obj()
                    ?.get("playlistPanelRenderer").obj()
            }
            .firstOrNull()
    }

    private fun parseSong(item: JsonObject): YtmSong? {
        val renderer = unwrapRenderer(item) ?: return null
        val videoId = renderer["videoId"].str() ?: return null
        val title = renderer["title"].obj()
            ?.get("runs").arr()
            ?.firstOrNull()
            ?.obj()
            ?.get("text").str()

        return YtmSong(
            id = YtmSong.cleanId(videoId),
            name = title,
            artists = parseArtists(renderer)
        )
    }

    private fun unwrapRenderer(item: JsonObject): JsonObject? {
        item["playlistPanelVideoRenderer"].obj()?.let { return it }

        val primary = item["playlistPanelVideoWrapperRenderer"].obj()
            ?.get("primaryRenderer").obj()
            ?: return null
        return unwrapRenderer(primary)
    }

    /**
     * Recover the artist/uploader without assuming one exact YouTube byline shape.
     *
     * Music tracks usually expose an artist browse endpoint. Ordinary YouTube
     * uploads may expose only a channel/uploader name, or put the artist target
     * in the item's menu instead. Echo only needs a useful display artist, so a
     * name-only YtmArtist is preferable to dropping the metadata as "Unknown".
     */
    private fun parseArtists(renderer: JsonObject): List<YtmArtist>? {
        val runs = listOf("ownerText", "shortBylineText", "longBylineText")
            .flatMap { key ->
                renderer[key].obj()
                    ?.get("runs").arr()
                    .orEmpty()
            }

        // First choice: proper linked artist/channel runs.
        val linkedArtists = runs.mapNotNull { element ->
            val run = element.obj() ?: return@mapNotNull null
            val name = run["text"].str()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val browse = run["navigationEndpoint"].obj()
                ?.get("browseEndpoint").obj()
                ?: return@mapNotNull null
            val id = browse["browseId"].str() ?: return@mapNotNull null
            val pageType = browse["browseEndpointContextSupportedConfigs"].obj()
                ?.get("browseEndpointContextMusicConfig").obj()
                ?.get("pageType").str()

            // YTM artists normally declare MUSIC_PAGE_TYPE_ARTIST. YouTube
            // uploader/channel links frequently omit pageType but still use a
            // UC... channel ID, which is equally useful to Echo.
            if (pageType != "MUSIC_PAGE_TYPE_ARTIST" && !id.startsWith("UC")) {
                return@mapNotNull null
            }

            YtmArtist(id = id, name = name)
        }.distinctBy { it.id.ifEmpty { it.name.orEmpty() } }

        if (linkedArtists.isNotEmpty()) {
            return linkedArtists
        }

        // Keep a sensible visible byline in reserve. This catches uploads where
        // YouTube supplies the channel/uploader only as plain text.
        val fallbackName = runs.asSequence()
            .mapNotNull { it.obj()?.get("text").str()?.trim() }
            .firstOrNull(::isPlausibleArtistName)

        // ytm-kt's original parser also checks the item's menu for an ARTIST
        // navigation target. Reproduce that fallback, but pair it with the
        // visible byline instead of displaying a generic "Go to artist" label.
        val menuItems = renderer["menu"].obj()
            ?.get("menuRenderer").obj()
            ?.get("items").arr()
            .orEmpty()

        for (item in menuItems) {
            val navigationItem = item.obj()
                ?.get("menuNavigationItemRenderer").obj()
                ?: continue
            val iconType = navigationItem["icon"].obj()
                ?.get("iconType").str()
            if (iconType != "ARTIST") {
                continue
            }

            val id = navigationItem["navigationEndpoint"].obj()
                ?.get("browseEndpoint").obj()
                ?.get("browseId").str()
                ?: continue

            val name = fallbackName ?: continue
            return listOf(YtmArtist(id = id, name = name))
        }

        // Last resort: a name-only artist is still better metadata than null.
        // ytm-kt itself uses an empty artist ID for title-only artist fallbacks.
        return fallbackName?.let { listOf(YtmArtist(id = "", name = it)) }
    }

    private fun isPlausibleArtistName(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.all { it.isWhitespace() || it in "•·|-/" }) return false
        if (text.matches(Regex("\\d{4}"))) return false
        if (text.matches(Regex("\\d{1,2}:\\d{2}(:\\d{2})?"))) return false
        return true
    }

    private fun extractContinuation(panel: JsonObject): String? {
        val entries = panel["continuations"].arr() ?: return null
        for (entry in entries) {
            val obj = entry.obj() ?: continue
            val next = obj["nextContinuationData"].obj()
                ?: obj["nextRadioContinuationData"].obj()
                ?: continue
            next["continuation"].str()?.let { return it }
        }
        return null
    }

    private fun videoIdToRadio(
        videoId: String,
        filters: List<RadioBuilderModifier>
    ): String {
        val publicFilters = filters.filterNot { it is RadioBuilderModifier.Internal }
        if (publicFilters.isEmpty()) {
            return "RDAMVM$videoId"
        }

        return buildString {
            append("RDAT")
            publicFilters.forEach { filter -> filter.string?.let(::append) }
            append('v')
            append(videoId)
        }
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
}
