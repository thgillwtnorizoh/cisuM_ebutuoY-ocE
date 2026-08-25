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
        private const val DEBUG_TAG = "FartniteArtistDebug"
        private val debugJson = Json { ignoreUnknownKeys = true }
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
        // Diagnostic build: consume the response as text so we can inspect the
        // exact live artist-header JSON YouTube returned. Do not guess at a new
        // renderer until we have this payload from a reproducible broken page.
        val raw = response.bodyAsText()
        val root = debugJson.parseToJsonElement(raw)
        val header = (root as? JsonObject)?.get("header")

        println("$DEBUG_TAG BEGIN artistId=$artistId")
        if (header == null) {
            println("$DEBUG_TAG header=<missing>")
        } else {
            debugChunked("HEADER_JSON", header.toString())
            debugStringPaths(header, "$.header")
        }
        println("$DEBUG_TAG END_HEADER artistId=$artistId")

        val parsed: YoutubeiBrowseResponse = debugJson.decodeFromString(raw)
        val builder = YtmArtistBuilder(artistId)

        val headerRenderer: HeaderRenderer? = parsed.header?.getRenderer()
        println(
            "$DEBUG_TAG CLASSIC_TITLE artistId=$artistId value=" +
                (headerRenderer?.title?.first_text ?: "<null>")
        )

        val detailTitle = parsed.header?.musicDetailHeaderRenderer
            ?.title?.runs?.firstOrNull()?.text
        println("$DEBUG_TAG DETAIL_TITLE artistId=$artistId value=${detailTitle ?: "<null>"}")

        val elementData = parsed.header?.musicElementHeaderRenderer
            ?.elementRenderer?.elementRenderer?.newElement?.type?.componentType
            ?.model?.musicBlurredBackgroundHeaderModel?.data
        println("$DEBUG_TAG BLURRED_TITLE artistId=$artistId value=${elementData?.title ?: "<null>"}")
        println(
            "$DEBUG_TAG BLURRED_FORMATTED_TITLE artistId=$artistId value=" +
                (elementData?.formattedTitle?.content ?: "<null>")
        )
        println(
            "$DEBUG_TAG BLURRED_STRAPLINE1 artistId=$artistId value=" +
                (elementData?.straplineData?.textLine1?.content ?: "<null>")
        )
        println(
            "$DEBUG_TAG BLURRED_STRAPLINE2 artistId=$artistId value=" +
                (elementData?.straplineData?.textLine2?.content ?: "<null>")
        )

        if (headerRenderer != null) {
            builder.name = headerRenderer.title!!.first_text
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

        val built = builder.build()
        println("$DEBUG_TAG BUILT artistId=$artistId name=${built.name ?: "<null>"}")
        return@runCatching built
    }

    private fun debugChunked(label: String, value: String) {
        val chunks = value.chunked(2400)
        chunks.forEachIndexed { index, chunk ->
            println("$DEBUG_TAG $label ${index + 1}/${chunks.size} $chunk")
        }
    }

    private fun debugStringPaths(element: JsonElement, path: String) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                debugStringPaths(value, "$path.$key")
            }
            is JsonArray -> element.forEachIndexed { index, value ->
                debugStringPaths(value, "$path[$index]")
            }
            is JsonPrimitive -> {
                val value = element.contentOrNull
                if (element.isString && !value.isNullOrBlank()) {
                    println("$DEBUG_TAG VALUE $path=$value")
                }
            }
        }
    }
}
