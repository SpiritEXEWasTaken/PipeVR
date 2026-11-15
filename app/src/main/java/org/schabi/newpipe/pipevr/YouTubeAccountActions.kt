package org.schabi.newpipe.pipevr

import android.util.Base64
import android.util.Log
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeTvAccountFeeds
import java.io.ByteArrayOutputStream

/**
 * PipeVR: actions on the signed-in YouTube account (like/dislike, subscribe, comment), sent
 * with the account's token like the YouTube TV app / SmartTube do. All calls are blocking.
 */
object YouTubeAccountActions {
    private const val TAG = "PipeVRActions"

    enum class Rating { LIKE, DISLIKE, NONE }

    data class VideoState(val rating: Rating, val channelId: String?, val subscribed: Boolean?)

    private fun post(endpoint: String, build: (com.grack.nanojson.JsonBuilder<JsonObject>) -> Unit): JsonObject =
        YoutubeTvAccountFeeds.post(endpoint, { b -> build(b) }, NewPipe.getPreferredLocalization())

    /** The account's rating of a video and its subscription to the video's channel. */
    fun videoState(videoId: String): VideoState {
        val next = post("next") { it.value("videoId", videoId) }
        var rating = Rating.NONE
        var channelId: String? = null
        var subscribed: Boolean? = null
        walk(next) { key, value ->
            when (key) {
                "likeButtonRenderer" -> when ((value as JsonObject).getString("likeStatus")) {
                    "LIKE" -> rating = Rating.LIKE
                    "DISLIKE" -> rating = Rating.DISLIKE
                }
                "subscribeButtonRenderer" -> if (subscribed == null) {
                    val o = value as JsonObject
                    if (o.has("subscribed")) subscribed = o.getBoolean("subscribed")
                    channelId = channelId ?: o.getString("channelId")
                }
                "subscribeEndpoint" -> if (channelId == null) {
                    channelId = (value as JsonObject).getArray("channelIds").getString(0)
                }
            }
        }
        return VideoState(rating, channelId, subscribed)
    }

    fun rate(videoId: String, rating: Rating): Boolean {
        val endpoint = when (rating) {
            Rating.LIKE -> "like/like"
            Rating.DISLIKE -> "like/dislike"
            Rating.NONE -> "like/removelike"
        }
        val response = post(endpoint) { b ->
            b.`object`("target").value("videoId", videoId).end()
        }
        Log.i(TAG, "$endpoint $videoId -> ${response.keys}")
        return !response.has("error")
    }

    /** Whether the account is subscribed to a channel, read from the channel's TV page. */
    fun isSubscribed(channelId: String): Boolean? {
        val page = post("browse") { it.value("browseId", channelId) }
        var subscribed: Boolean? = null
        walk(page) { key, value ->
            if (key == "subscribeButtonRenderer" && subscribed == null) {
                val o = value as JsonObject
                if (o.has("subscribed")) subscribed = o.getBoolean("subscribed")
            }
        }
        return subscribed
    }

    fun setSubscribed(channelId: String, subscribe: Boolean): Boolean {
        val response = post(if (subscribe) "subscription/subscribe" else "subscription/unsubscribe") { b ->
            b.array("channelIds").value(channelId).end()
        }
        Log.i(TAG, "subscribe=$subscribe $channelId -> ${response.keys}")
        return !response.has("error")
    }

    /**
     * Posts a top-level comment. createCommentParams is the protobuf YouTube's apps send
     * {2: videoId, 5: {1: 0}, 10: 7} (as built by open source clients).
     */
    fun comment(videoId: String, text: String): Boolean {
        val proto = ByteArrayOutputStream().apply {
            val id = videoId.toByteArray()
            write(0x12); write(id.size); write(id)
            write(0x2a); write(2); write(0x08); write(0x00)
            write(0x50); write(0x07)
        }.toByteArray()
        val params = Base64.encodeToString(proto, Base64.URL_SAFE or Base64.NO_WRAP)
        val response = post("comment/create_comment") { b ->
            b.value("createCommentParams", params).value("commentText", text)
        }
        Log.i(TAG, "comment $videoId -> ${response.keys} status=${response.getString("status")}")
        return response.getString("status") == "STATUS_SUCCEEDED" ||
            response.has("actions") && !response.has("error")
    }

    /**
     * Sends a live chat message. The params are the protobuf YouTube's apps use
     * {1: {5: {1: channelId, 2: videoId}}, 2: 1, 3: 4}, base64 encoded twice (as built by open
     * source clients).
     */
    fun sendLiveChat(videoId: String, channelId: String, text: String): Boolean {
        fun field(tag: Int, bytes: ByteArray) = byteArrayOf(tag.toByte(), bytes.size.toByte()) + bytes
        val ids = field(0x0a, channelId.toByteArray()) + field(0x12, videoId.toByteArray())
        val params = field(0x2a, ids)
        val proto = field(0x0a, params) + byteArrayOf(0x10, 0x01, 0x18, 0x04)
        val inner = Base64.encodeToString(proto, Base64.NO_WRAP)
        val encoded = Base64.encodeToString(
            java.net.URLEncoder.encode(inner, "UTF-8").toByteArray(), Base64.NO_WRAP
        )
        val fill = { b: com.grack.nanojson.JsonBuilder<JsonObject> ->
            b.`object`("richMessage")
                .array("textSegments").`object`().value("text", text).end().end()
                .end()
                .value("params", encoded)
                .value("clientMessageId", java.util.UUID.randomUUID().toString())
            Unit
        }
        // the TV app has no live chat: try it, then the website and the mobile website
        val attempts = listOf<Pair<String, () -> JsonObject>>(
            "TV" to { post("live_chat/send_message") { b -> fill(b) } },
            "WEB" to {
                YoutubeTvAccountFeeds.postAs("WEB", "2.20261101.01.00", "live_chat/send_message",
                    { b -> fill(b) }, NewPipe.getPreferredLocalization())
            },
            "MWEB" to {
                YoutubeTvAccountFeeds.postAs("MWEB", "2.20261101.01.00", "live_chat/send_message",
                    { b -> fill(b) }, NewPipe.getPreferredLocalization())
            },
        )
        for ((name, call) in attempts) {
            val response = try {
                call()
            } catch (e: Exception) {
                Log.w(TAG, "live chat $videoId via $name failed: ${e.message}")
                continue
            }
            if (response.has("actions") && !response.has("error")) {
                Log.i(TAG, "live chat $videoId sent via $name")
                return true
            }
            val err = response.getObject("error")
            Log.w(TAG, "live chat $videoId via $name refused: ${err.getInt("code")} " +
                err.getString("status") + " " + err.getString("message"))
        }
        return false
    }

    private fun walk(node: Any?, visit: (String, Any?) -> Unit) {
        when (node) {
            is JsonObject -> node.forEach { (k, v) ->
                visit(k, v)
                walk(v, visit)
            }
            is JsonArray -> node.forEach { walk(it, visit) }
        }
    }
}
