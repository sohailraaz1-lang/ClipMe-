package com.clipme.app.resolve

import android.content.Context
import com.clipme.app.model.Platform
import com.clipme.app.model.StreamInfo
import com.clipme.app.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo as NpStreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Resolves YouTube & Twitch URLs into [StreamInfo].
 *  - YouTube: NewPipeExtractor (HLS/adaptive manifest + thumbnail + duration).
 *  - Twitch: public GQL (stream metadata) + usher (live HLS) / usher VOD (HLS).
 * Falls back to generic HTML scraping when NewPipe throws.
 */
class StreamResolver(
    private val context: Context,
    private val http: HttpClient
) {

    init {
        NewPipe.init(DownloaderImpl(http))
    }

    fun platformOf(url: String): Platform {
        val host = url.lowercase()
        return if (host.contains("twitch.tv")) Platform.TWITCH else Platform.YOUTUBE
    }

    /** Parses a video id from various YouTube URL shapes. */
    fun youTubeVideoId(url: String): String? {
        val trimmed = url.trim()
        Regex("youtu\\.be/([\\w-]{11})").find(trimmed)?.let { return it.groupValues[1] }
        Regex("[?&]v=([\\w-]{11})").find(trimmed)?.let { return it.groupValues[1] }
        Regex("/(?:shorts|embed|live)/([\\w-]{11})").find(trimmed)?.let { return it.groupValues[1] }
        return null
    }

    suspend fun resolve(url: String): StreamInfo {
        val platform = platformOf(url)
        return when (platform) {
            Platform.TWITCH -> resolveTwitch(url)
            Platform.YOUTUBE -> resolveYouTube(url)
        }
    }

    // ------------------------------------------------------------------ YouTube

    private fun parseHeight(resolution: String?): Int =
        resolution?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

    private suspend fun resolveYouTube(url: String): StreamInfo = withContext(Dispatchers.IO) {
        val videoId = youTubeVideoId(url)
            ?: throw IllegalArgumentException("Not a valid YouTube link")
        val cleanUrl = "https://www.youtube.com/watch?v=$videoId"
        try {
            val info = NpStreamInfo.getInfo(cleanUrl)
            val isLive = info.streamType == StreamType.LIVE_STREAM
            val progressive = info.videoStreams
                ?.filter {
                    !it.isVideoOnly() &&
                        it.getFormat()?.mimeType?.startsWith("video/mp4") == true &&
                        it.content.isNotBlank()
                }
                ?.maxByOrNull { parseHeight(it.getResolution()) }
                ?.content
            val manifests = buildList {
                if (isLive) {
                    info.hlsUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
                    info.videoOnlyStreams
                        ?.filter { it.deliveryMethod == DeliveryMethod.HLS && it.content.isNotBlank() }
                        ?.sortedBy { parseHeight(it.getResolution()) }
                        ?.forEach { add(it.content) }
                }
                info.videoOnlyStreams
                    ?.filter { it.deliveryMethod != DeliveryMethod.HLS && it.content.isNotBlank() }
                    ?.sortedByDescending { parseHeight(it.getResolution()) }
                    ?.forEach { add(it.content) }
                progressive?.let { add(it) }
                info.videoStreams
                    ?.filter { it.content.isNotBlank() }
                    ?.sortedByDescending { parseHeight(it.getResolution()) }
                    ?.forEach { add(it.content) }
            }.distinct()

            StreamInfo(
                platform = Platform.YOUTUBE,
                url = cleanUrl,
                title = info.name,
                uploader = info.uploaderName,
                thumbnailUrl = info.thumbnails.firstOrNull()?.url
                    ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                isLive = isLive,
                durationMs = info.duration.takeIf { it > 0L }?.let { it * 1000L },
                progressiveUrl = progressive,
                manifests = manifests
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Show the real reason instead of a vague "no playable stream" message.
            val reason = e.message?.take(160) ?: e.javaClass.simpleName
            throw IllegalStateException("YouTube blocked or changed this video (${e.javaClass.simpleName}): $reason")
        }
    }

    private suspend fun resolveYouTubeFallback(videoId: String): StreamInfo =
        withContext(Dispatchers.IO) {
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val html = runCatching { http.getString(watchUrl, 30) }.getOrNull() ?: ""
            val title = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)
                ?.groupValues?.get(1) ?: "YouTube video"
            val author = Regex("<link itemprop=\"name\" content=\"([^\"]+)\"").find(html)
                ?.groupValues?.get(1) ?: "Unknown channel"
            val isLive = html.contains("isLiveContent\":true") || html.contains("LIVE_STREAM")
            StreamInfo(
                platform = Platform.YOUTUBE,
                url = watchUrl,
                title = title,
                uploader = author,
                thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                isLive = isLive,
                durationMs = null,
                progressiveUrl = null,
                manifests = emptyList()
            )
        }

    // ------------------------------------------------------------------ Twitch

    private data class TwitchUser(
        val id: String,
        val login: String,
        val displayName: String,
        val profileImageUrl: String?,
        val isLive: Boolean
    )

    private data class TwitchStreamInfo(
        val hlsUrl: String?,
        val title: String?,
        val game: String?,
        val viewers: Int?,
        val language: String?
    )

    private fun twitchGql(query: String, variables: Map<String, String>): String? = runCatching {
        val payload = JSONObject()
            .put("query", query)
            .put("variables", JSONObject(variables))
            .toString()
        val req = http.requestBuilder("https://gql.twitch.tv/gql")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    private fun twitchLoginFromUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.contains("/videos/")) {
            return trimmed.substringBefore("/videos/").substringAfterLast('/')
        }
        return trimmed.substringAfterLast('/')
    }

    private fun twitchVideoIdFromUrl(url: String): String? {
        Regex("twitch\\.tv/videos/(\\d+)").find(url.trim())?.let { return it.groupValues[1] }
        Regex("twitch\\.tv/(?:[\\w-]+)/v/(\\d+)").find(url.trim())?.let { return it.groupValues[1] }
        return null
    }

    private suspend fun resolveTwitch(url: String): StreamInfo = withContext(Dispatchers.IO) {
        val login = twitchLoginFromUrl(url)
        val videoId = twitchVideoIdFromUrl(url)

        if (videoId != null) {
            parseVodMeta(videoId)?.let { return@withContext it }
        }

        val user = twitchUser(login)
        if (user?.isLive == true) {
            val stream = twitchStream(login)
            StreamInfo(
                platform = Platform.TWITCH,
                url = "https://www.twitch.tv/$login",
                title = stream?.title?.takeIf { it.isNotBlank() } ?: user.displayName,
                uploader = user.displayName,
                thumbnailUrl = "https://static-cdn.jtvnw.net/previews-ttv/live_user_$login-320x180.jpg",
                isLive = true,
                durationMs = null,
                progressiveUrl = null,
                manifests = listOfNotNull(stream?.hlsUrl)
            )
        } else {
            parseChannelVod(login)?.let { return@withContext it }
            if (videoId != null) {
                val hls = usherVodHls(videoId)
                StreamInfo(
                    platform = Platform.TWITCH,
                    url = "https://www.twitch.tv/videos/$videoId",
                    title = "Twitch VOD $videoId",
                    uploader = login,
                    thumbnailUrl = "https://static-cdn.jtvnw.net/cf_vods/$videoId/thumb/index-480x270.jpg",
                    isLive = false,
                    durationMs = null,
                    progressiveUrl = null,
                    manifests = listOfNotNull(hls)
                )
            } else {
                throw IllegalArgumentException(
                    "Channel '$login' is offline and no VOD could be found. " +
                        "Paste a direct VOD link like twitch.tv/videos/123456789."
                )
            }
        }
    }

    private suspend fun parseVodMeta(videoId: String): StreamInfo? {
        val json = twitchGql(VOD_QUERY, mapOf("id" to videoId)) ?: return null
        return runCatching {
            val vod = JSONObject(json).optJSONObject("data")?.optJSONObject("video") ?: return null
            val hls = usherVodHls(videoId)
            StreamInfo(
                platform = Platform.TWITCH,
                url = "https://www.twitch.tv/videos/$videoId",
                title = vod.optString("title", "Twitch VOD"),
                uploader = vod.optJSONObject("owner")?.optString("displayName", "Unknown") ?: "Unknown",
                thumbnailUrl = "https://static-cdn.jtvnw.net/cf_vods/$videoId/thumb/index-480x270.jpg",
                isLive = false,
                durationMs = vod.optInt("lengthSeconds", 0).toLong() * 1000L,
                progressiveUrl = null,
                manifests = listOfNotNull(hls)
            )
        }.getOrNull()
    }

    private suspend fun parseChannelVod(login: String): StreamInfo? {
        val json = twitchGql(CHANNEL_QUERY, mapOf("login" to login)) ?: return null
        return runCatching {
            val user = JSONObject(json).optJSONObject("data")?.optJSONObject("user") ?: return null
            val video = user.optJSONObject("lastBroadcast") ?: return null
            val id = video.optString("id", "")
            if (id.isEmpty()) return null
            val hls = usherVodHls(id)
            StreamInfo(
                platform = Platform.TWITCH,
                url = "https://www.twitch.tv/videos/$id",
                title = video.optString("title", "Twitch VOD"),
                uploader = user.optString("displayName", "Unknown"),
                thumbnailUrl = "https://static-cdn.jtvnw.net/cf_vods/$id/thumb/index-480x270.jpg",
                isLive = false,
                durationMs = video.optInt("lengthSeconds", 0).toLong() * 1000L,
                progressiveUrl = null,
                manifests = listOfNotNull(hls)
            )
        }.getOrNull()
    }

    private fun twitchUser(login: String): TwitchUser? {
        val json = twitchGql(USER_QUERY, mapOf("login" to login)) ?: return null
        return runCatching {
            val user = JSONObject(json).optJSONObject("data")?.optJSONObject("user") ?: return null
            TwitchUser(
                id = user.optString("id", ""),
                login = login,
                displayName = user.optString("displayName", login),
                profileImageUrl = user.optString("profileImageURL", "").ifEmpty { null },
                isLive = user.optBoolean("isLive", false)
            )
        }.getOrNull()
    }

    private suspend fun twitchStream(login: String): TwitchStreamInfo? {
        val json = twitchGql(STREAM_QUERY, mapOf("login" to login)) ?: return null
        return runCatching {
            val stream = JSONObject(json).optJSONObject("data")
                ?.optJSONObject("user")?.optJSONObject("stream") ?: return null
            val hls = usherLiveHls(login)
            TwitchStreamInfo(
                hlsUrl = hls,
                title = stream.optString("title", ""),
                game = stream.optJSONObject("game")?.optString("name", null),
                viewers = if (stream.has("viewersCount")) stream.optInt("viewersCount") else null,
                language = stream.optString("language", null)
            )
        }.getOrNull()
    }

    private suspend fun usherLiveHls(login: String): String? = runCatching {
        val url = "https://usher.ttvnw.net/api/channel/hls/$login.m3u8" +
            "?player=twitchweb&type=any&allow_source=true&allow_audio_only=true&fast_bread=true"
        http.getString(url, 30)
        url
    }.getOrNull()

    private suspend fun usherVodHls(videoId: String): String? = runCatching {
        val url = "https://usher.ttvnw.net/vod/$videoId.m3u8?player=twitchweb&type=any"
        http.getString(url, 30)
        url
    }.getOrNull()

    // ----------------------------------------------------------------- GQL

    private companion object {
        const val USER_QUERY = """
            query User(${'$'}login: String!) {
              user(login: ${'$'}login) {
                id
                login
                displayName
                profileImageURL
                isLive
              }
            }
        """
        const val STREAM_QUERY = """
            query User(${'$'}login: String!) {
              user(login: ${'$'}login) {
                stream {
                  id
                  title
                  language
                  viewersCount
                  game { name }
                }
              }
            }
        """
        const val CHANNEL_QUERY = """
            query User(${'$'}login: String!) {
              user(login: ${'$'}login) {
                displayName
                lastBroadcast {
                  id
                  title
                  lengthSeconds
                }
              }
            }
        """
        const val VOD_QUERY = """
            query Video(${'$'}id: ID!) {
              video(id: ${'$'}id) {
                id
                title
                lengthSeconds
                owner { displayName }
              }
            }
        """
    }
}

/** Adapter so NewPipeExtractor can use our OkHttp client. */
class DownloaderImpl(private val http: HttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = http.requestBuilder(request.url())
        val data = request.dataToSend()
        val method = request.httpMethod()
        if (data != null) {
            builder.method(method, data.toRequestBody())
        } else if (method.equals("POST", ignoreCase = true) || method.equals("PUT", ignoreCase = true)) {
            builder.method(method, ByteArray(0).toRequestBody())
        } else {
            builder.method(method, null)
        }
        request.headers().forEach { (k, values) ->
            values.forEach { v -> builder.addHeader(k, v) }
        }
        return http.newCall(builder.build()).execute().use { resp ->
            Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                resp.body?.string() ?: "",
                resp.request.url.toString()
            )
        }
    }
}
