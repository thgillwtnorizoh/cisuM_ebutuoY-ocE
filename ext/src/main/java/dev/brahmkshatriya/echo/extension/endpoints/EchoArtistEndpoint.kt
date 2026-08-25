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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class EchoArtistEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {

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
        // Keep the raw JSON around because YouTube has introduced artist header
        // models that are newer than the typed response model bundled here.
        val responseText = response.bodyAsText()
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(responseText).obj()
            ?: throw IllegalStateException("Artist browse response was not a JSON object")
        val parsed = json.decodeFromString<YoutubeiBrowseResponse>(responseText)

        val builder = YtmArtistBuilder(artistId)
        val header = parsed.header

        // Current artist pages may put the visible artist name here:
        // musicElementHeaderRenderer -> ... -> model -> youtubeModel
        // -> musicPageHeaderModel -> title.
        // This shape is not represented by YoutubeiBrowseResponse yet, so read
        // it directly instead of making the entire response model chase Google.
        findRawModernArtistName(root)?.let { builder.name = it }

        // Another newer YouTube Music header shape used by albums and some
        // artist-page variants.
        val elementData = header
            ?.musicElementHeaderRenderer
            ?.elementRenderer
            ?.elementRenderer
            ?.newElement
            ?.type
            ?.componentType
            ?.model
            ?.musicBlurredBackgroundHeaderModel
            ?.data

        if (elementData != null) {
            if (builder.name.isNullOrBlank()) {
                (elementData.title
                    ?: elementData.formattedTitle?.content
                    ?: elementData.straplineData?.textLine1?.content)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { builder.name = it }
            }

            elementData.formattedDescription?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { builder.description = it }

            elementData.primaryImage?.sources
                ?.let { ThumbnailProvider.fromThumbnails(it) }
                ?.let { builder.thumbnail_provider = it }
        }

        // Some pages use musicDetailHeaderRenderer instead of the classic
        // immersive/visual HeaderRenderer shape.
        val detailHeader = header?.musicDetailHeaderRenderer
        if (builder.name.isNullOrBlank() && detailHeader != null) {
            detailHeader.title?.runs?.firstOrNull()?.text
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { builder.name = it }

            detailHeader.byline
                ?.musicDetailHeaderButtonsBylineRenderer
                ?.description
                ?.runs
                ?.joinToString("") { it.text }
                ?.takeIf { it.isNotBlank() }
                ?.let { builder.description = it }

            detailHeader.thumbnail
                ?.croppedSquareThumbnailRenderer
                ?.thumbnail
                ?.thumbnails
                ?.let { ThumbnailProvider.fromThumbnails(it) }
                ?.let { builder.thumbnail_provider = it }
        }

        // Legacy artist header shapes.
        val headerRenderer: HeaderRenderer? = header?.getRenderer()
        if (headerRenderer != null) {
            if (builder.name.isNullOrBlank()) {
                headerRenderer.title?.first_text
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { builder.name = it }
            }

            if (builder.description.isNullOrBlank()) {
                builder.description = headerRenderer.description?.first_text
            }

            if (builder.thumbnail_provider == null) {
                builder.thumbnail_provider =
                    ThumbnailProvider.fromThumbnails(headerRenderer.getThumbnails())
            }

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

        return@runCatching builder.build()
    }

    private fun findRawModernArtistName(root: JsonObject): String? {
        val model = root["header"].obj()
            ?.get("musicElementHeaderRenderer").obj()
            ?.get("elementRenderer").obj()
            ?.get("elementRenderer").obj()
            ?.get("newElement").obj()
            ?.get("type").obj()
            ?.get("componentType").obj()
            ?.get("model").obj()
            ?: return null

        val musicPageTitle = model["youtubeModel"].obj()
            ?.get("musicPageHeaderModel").obj()
            ?.get("title").str()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (musicPageTitle != null) return musicPageTitle

        // Keep raw fallbacks for A/B variants whose typed model is also stale.
        val data = model["musicBlurredBackgroundHeaderModel"].obj()
            ?.get("data").obj()
            ?: return null

        return sequenceOf(
            data["title"].str(),
            data["formattedTitle"].obj()?.get("content").str(),
            data["straplineData"].obj()?.get("textLine1").obj()?.get("content").str()
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
}
