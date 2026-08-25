package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.extension.endpoints.EchoSongFeedEndpoint.Companion.processRows
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiPostBody
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.model.external.ItemLayoutType
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtistBuilder
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtistLayout
import dev.toastbits.ytmkt.model.internal.HeaderRenderer
import dev.toastbits.ytmkt.uistrings.parseYoutubeSubscribersString
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class EchoArtistEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {

    companion object {
        private val diagnosticJson = Json { ignoreUnknownKeys = true }
    }

    suspend fun loadArtist(id: String): YtmArtist {
        val hl: String = api.data_language
        val response: HttpResponse = api.client.request {
            endpointPath("browse")
            addApiHeadersWithAuthenticated()
            postWithBody(YoutubeiPostBody.MOBILE.getPostBody(api)) {
                put("browseId", id)
            }
        }

        return parseArtistResponse(id, response, hl, api).getOrThrow()
    }

    private suspend fun parseArtistResponse(
        artistId: String,
        response: HttpResponse,
        hl: String,
        api: YoutubeiApi
    ): Result<YtmArtist> = runCatching {
        val raw = response.bodyAsText()
        val rawRoot = diagnosticJson.parseToJsonElement(raw)
        val parsed: YoutubeiBrowseResponse = diagnosticJson.decodeFromString(raw)
        val builder = YtmArtistBuilder(artistId)

        val headerRenderer: HeaderRenderer? = parsed.header?.getRenderer()
        val normalName = headerRenderer?.title?.first_text

        if (headerRenderer != null) {
            builder.name = normalName
            builder.description = headerRenderer.description?.first_text
            builder.thumbnail_provider =
                ThumbnailProvider.fromThumbnails(headerRenderer.getThumbnails())

            headerRenderer.subscriptionButton?.subscribeButtonRenderer?.let { subscribeButton ->
                builder.subscribe_channel_id = subscribeButton.channelId
                builder.subscriber_count = parseYoutubeSubscribersString(
                    subscribeButton.subscriberCountText.first_text,
                    hl
                )
                builder.subscribed = subscribeButton.subscribed
            }
            headerRenderer.playButton?.buttonRenderer?.let {
                if (it.icon?.iconType == "MUSIC_SHUFFLE") {
                    builder.shuffle_playlist_id = it.navigationEndpoint.watchEndpoint?.playlistId
                }
            }
        }

        val missingName = normalName.isNullOrBlank() || normalName.equals("Unknown", ignoreCase = true)
        if (missingName) {
            val header = (rawRoot as? JsonObject)?.get("header") ?: rawRoot
            val candidates = collectCandidateStrings(header, "$.header")
                .distinctBy { it.second }
                .take(18)

            builder.name = "DEBUG HEADER"
            val debugText = if (candidates.isEmpty()) {
                "DEBUG: no candidate header strings found"
            } else {
                buildString {
                    append("DEBUG candidate header strings:\n")
                    candidates.forEachIndexed { index, (path, value) ->
                        append(index + 1)
                        append(". ")
                        append(path)
                        append(" = ")
                        append(value)
                        append('\n')
                    }
                }.trimEnd()
            }

            val originalDescription = builder.description?.takeIf { it.isNotBlank() }
            builder.description = if (originalDescription != null) {
                "$debugText\n\n$originalDescription"
            } else {
                debugText
            }
        }

        val shelfList = parsed.getShelves(false)
        builder.layouts = processRows(shelfList, api).map {
            YtmArtistLayout(
                items = it.items,
                title = it.title,
                type = ItemLayoutType.GRID,
                view_more = it.view_more,
                playlist_id = null
            )
        }

        return@runCatching builder.build()
    }

    private fun collectCandidateStrings(
        element: JsonElement,
        path: String
    ): List<Pair<String, String>> {
        val output = mutableListOf<Pair<String, String>>()

        fun walk(node: JsonElement, currentPath: String) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    walk(value, "$currentPath.$key")
                }
                is JsonArray -> node.forEachIndexed { index, value ->
                    walk(value, "$currentPath[$index]")
                }
                is JsonPrimitive -> {
                    if (!node.isString) return
                    val value = node.contentOrNull?.trim().orEmpty()
                    if (value.isEmpty() || value.length > 120) return
                    if (value.startsWith("http://") || value.startsWith("https://")) return

                    val lowerPath = currentPath.lowercase()
                    val interesting = listOf(
                        "title", "name", "text", "content", "label", "strapline", "header"
                    ).any { it in lowerPath }

                    if (interesting) {
                        output += currentPath to value
                    }
                }
            }
        }

        walk(element, path)
        return output
    }
}
