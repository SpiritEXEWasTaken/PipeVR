package org.schabi.newpipe.pipevr

import android.util.Log
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeOAuth
import org.schabi.newpipe.extractor.services.youtube.YoutubeTvAccountFeeds
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PipeVR: keeps the signed-in YouTube account's watch history and resume positions in sync,
 * like SmartTube: "playback" and "watchtime" stats pings with the account's token, and resume
 * positions read from the account's history.
 */
object YouTubeWatchSync {
    private const val TAG = "PipeVRWatchSync"
    private const val UPDATE_INTERVAL_MS = 30_000L
    private const val HISTORY_CACHE_MS = 2 * 60_000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PipeVR-watch-sync").apply { isDaemon = true }
    }
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).build()
    }

    // state of the video being played (main thread)
    private var currentVideoId: String? = null
    private var cpn: String = newCpn()
    private var recordCreated = false
    private var lastSentPositionSec = 0f
    private var lastSentAt = 0L
    private var wasPlaying = false
    private var finalSent = false

    @Volatile
    private var historyFetchedAt = 0L

    /** Tracking params (ei, vm, of) from a player lookup made WITH the account, per video. */
    private val signedInTracking = HashMap<String, Triple<String, String, String>?>()

    private val SAMSUNG_UA = "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 " +
        "(KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"

    /** Appends to a small log file readable with adb, to diagnose the history sync. */
    private fun debug(message: String) {
        Log.i(TAG, message)
        try {
            val dir = YouTubeAccountManager.context().getExternalFilesDir(null) ?: return
            val file = File(dir, "pipevr-sync.log")
            if (file.length() > 256 * 1024) file.delete()
            file.appendText(
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + " " + message + "\n"
            )
        } catch (ignored: Exception) {
        }
    }

    private fun isActive(info: StreamInfo?): Boolean =
        info != null && info.serviceId == ServiceList.YouTube.serviceId &&
            YouTubeAccountManager.isSignedIn()

    // ---------------------------------------------------------------------------------------
    // Resume
    // ---------------------------------------------------------------------------------------

    /**
     * Blocking. Where YouTube says the account stopped watching this video: milliseconds,
     * 0 if it was finished, or null if YouTube has no position (use the local one).
     */
    @JvmStatic
    fun resumePositionMs(serviceId: Int, url: String): Long? {
        if (serviceId != ServiceList.YouTube.serviceId || !YouTubeAccountManager.isSignedIn()) {
            return null
        }
        val id = PipeVrPlayback.videoIdOf(url) ?: return null
        YoutubeTvAccountFeeds.RESUME_SECONDS[id]?.let { return it * 1000L }
        if (System.currentTimeMillis() - historyFetchedAt > HISTORY_CACHE_MS) {
            try {
                YoutubeTvAccountFeeds.refreshResumePositions(NewPipe.getPreferredLocalization())
                historyFetchedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "Could not load the YouTube history for resuming", e)
            }
        }
        return YoutubeTvAccountFeeds.RESUME_SECONDS[id]?.let { it * 1000L }
    }

    // ---------------------------------------------------------------------------------------
    // History
    // ---------------------------------------------------------------------------------------

    /** Called from the player's progress loop (main thread, about twice a second). */
    @JvmStatic
    fun onProgress(info: StreamInfo?, positionMs: Int, durationMs: Int, isPlaying: Boolean) {
        if (!isActive(info) || durationMs <= 0) {
            return
        }
        val id = PipeVrPlayback.videoIdOf(info!!.url) ?: return
        val positionSec = positionMs / 1000f
        val lengthSec = durationMs / 1000f
        if (id != currentVideoId) {
            currentVideoId = id
            cpn = newCpn()
            recordCreated = false
            finalSent = false
            lastSentPositionSec = positionSec
            lastSentAt = 0L
            wasPlaying = false
        }
        val now = System.currentTimeMillis()
        val nearEnd = lengthSec - positionSec < endThresholdSec(lengthSec)
        val pausedNow = wasPlaying && !isPlaying
        wasPlaying = isPlaying

        if (!recordCreated) {
            if (!isPlaying) return // wait until it really plays
            recordCreated = true
            send(id, cpn, lengthSec, positionSec, positionSec, create = true, final = false)
            lastSentAt = now
            lastSentPositionSec = positionSec
            return
        }
        if (nearEnd && !finalSent) {
            finalSent = true
            send(id, cpn, lengthSec, lastSentPositionSec, lengthSec, create = false, final = true)
            YoutubeTvAccountFeeds.RESUME_SECONDS[id] = 0
            return
        }
        if (finalSent) return
        if (pausedNow || (isPlaying && now - lastSentAt >= UPDATE_INTERVAL_MS)) {
            send(id, cpn, lengthSec, lastSentPositionSec, positionSec, create = false, final = false)
            lastSentAt = now
            lastSentPositionSec = positionSec
            YoutubeTvAccountFeeds.RESUME_SECONDS[id] = positionSec.toInt()
        }
    }

    private fun endThresholdSec(lengthSec: Float): Float =
        // like SmartTube: count the video as watched when only the end is left
        if (lengthSec > 30 * 60) 120f else if (lengthSec > 5 * 60) 60f else 10f

    private fun send(
        videoId: String,
        cpn: String,
        lengthSec: Float,
        fromSec: Float,
        toSec: Float,
        create: Boolean,
        final: Boolean,
    ) {
        executor.execute {
            try {
                // like SmartTube: prefer the tracking params of a player lookup made with the
                // account, so YouTube credits the views to it; fall back to the anonymous ones
                val signedIn = if (signedInTracking.containsKey(videoId)) {
                    signedInTracking[videoId]
                } else {
                    fetchSignedInTracking(videoId).also { signedInTracking[videoId] = it }
                }
                val (ei, vm, of) = signedIn ?: anonymousTracking(videoId) ?: run {
                    debug("$videoId: no tracking parameters at all; history not updated")
                    return@execute
                }
                debug(
                    "$videoId: ${if (create) "start" else if (final) "finished" else "progress"}" +
                        " ${fmt(fromSec)}->${fmt(toSec)}s using " +
                        (if (signedIn != null) "signed-in" else "anonymous") + " tracking"
                )
                if (create || final) {
                    get(
                        statsUrl("playback", videoId, cpn, ei, vm, of)
                            .addQueryParameter("len", if (final) null else fmt(lengthSec))
                            .addQueryParameter("cmt", if (final) null else fmt(fromSec))
                            .addQueryParameter("final", if (final) "1" else null)
                            .build().toString()
                    )
                }
                get(
                    statsUrl("watchtime", videoId, cpn, ei, vm, of)
                        .addQueryParameter("len", if (final) null else fmt(lengthSec))
                        .addQueryParameter("st", fmt(fromSec))
                        .addQueryParameter("et", fmt(toSec))
                        .addQueryParameter("cmt", if (final) null else fmt(toSec))
                        .addQueryParameter("final", if (final) "1" else null)
                        .build().toString()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not update the YouTube history", e)
            }
        }
    }

    private fun anonymousTracking(videoId: String): Triple<String, String, String>? {
        val tracking = YoutubeTvAccountFeeds.PLAYBACK_TRACKING[videoId]
        if (tracking == null) {
            debug("$videoId: anonymous player response had no playbackTracking")
            return null
        }
        return trackingParams(tracking)
    }

    private fun trackingParams(tracking: JsonObject): Triple<String, String, String>? {
        val params = tracking.getObject("videostatsWatchtimeUrl").getString("baseUrl")
            ?.toHttpUrlOrNull() ?: return null
        val ei = params.queryParameter("ei") ?: return null
        val vm = params.queryParameter("vm") ?: return null
        val of = params.queryParameter("of") ?: return null
        return Triple(ei, vm, of)
    }

    /** Player lookup with the account's token (TV clients), only for its tracking params. */
    private fun fetchSignedInTracking(videoId: String): Triple<String, String, String>? {
        val sts = try {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        } catch (e: Exception) {
            debug("$videoId: no signature timestamp: $e")
            null
        }
        // the older TV client answers signed-in player lookups; the newest one asks to
        // "reload the page" (verified on a Quest 3, Nov 2026), so it is only the fallback
        val clients = listOf(
            Triple("5.20260707", SAMSUNG_UA, true),
            Triple(YoutubeTvAccountFeeds.TV_CLIENT_VERSION, YoutubeTvAccountFeeds.TV_USER_AGENT,
                false),
        )
        for ((version, ua, samsung) in clients) {
            try {
                val client = JsonObject.builder()
                    .value("clientName", "TVHTML5")
                    .value("clientVersion", version)
                    .value("userAgent", ua)
                    .value("hl", "en")
                    .value("gl", "US")
                if (samsung) {
                    client.value("deviceMake", "Samsung").value("deviceModel", "UKS9800")
                        .value("osName", "Tizen").value("osVersion", "2.4.0")
                }
                val playback = JsonObject.builder().value("html5Preference", "HTML5_PREF_WANTS")
                if (sts != null) playback.value("signatureTimestamp", sts)
                val body = JsonObject.builder()
                    .value("context", JsonObject.builder().value("client", client.done()).done())
                    .value("videoId", videoId)
                    .value("racyCheckOk", true)
                    .value("contentCheckOk", true)
                    .value("playbackContext", JsonObject.builder()
                        .value("contentPlaybackContext", playback.done()).done())
                    .done()
                val headers = HashMap<String, List<String>>()
                YoutubeOAuth.addAuthHeaders(headers)
                val request = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("User-Agent", ua)
                    .header("Referer", "https://www.youtube.com/tv")
                    .header("X-YouTube-Client-Name", "7")
                    .header("X-YouTube-Client-Version", version)
                    .post(JsonWriter.string(body).toRequestBody("application/json".toMediaType()))
                headers.forEach { (k, v) -> v.firstOrNull()?.let { request.header(k, it) } }
                val json = http.newCall(request.build()).execute().use { response ->
                    JsonParser.`object`().from(response.body.string())
                }
                val status = json.getObject("playabilityStatus")
                // diagnostics: what the TV client offers (does it go beyond 4K for 360°?)
                val formats = json.getObject("streamingData").getArray("adaptiveFormats")
                var maxHeight = 0
                val projections = HashSet<String>()
                for (i in 0 until formats.size) {
                    val f = formats.getObject(i)
                    maxHeight = maxOf(maxHeight, f.getInt("height", 0))
                    f.getString("projectionType")?.let { projections += it }
                }
                debug("$videoId: TV client formats up to ${maxHeight}p, projections=$projections")
                val params = trackingParams(json.getObject("playbackTracking"))
                debug(
                    "$videoId: signed-in player lookup (TVHTML5 $version): " +
                        "${status.getString("status")} ${status.getString("reason") ?: ""}" +
                        " tracking=${params != null}"
                )
                if (params != null) return params
            } catch (e: Exception) {
                debug("$videoId: signed-in player lookup (TVHTML5 $version) failed: $e")
            }
        }
        return null
    }

    private fun statsUrl(
        kind: String, videoId: String, cpn: String, ei: String, vm: String, of: String
    ) = "https://www.youtube.com/api/stats/$kind?ns=yt&ver=2".toHttpUrlOrNull()!!.newBuilder()
        .addQueryParameter("docid", videoId)
        .addQueryParameter("cpn", cpn)
        .addQueryParameter("ei", ei)
        .addQueryParameter("vm", vm)
        .addQueryParameter("of", of)

    private fun get(url: String) {
        val headers = HashMap<String, List<String>>()
        YoutubeOAuth.addAuthHeaders(headers)
        val builder = Request.Builder().url(url)
            .header("User-Agent", YoutubeTvAccountFeeds.TV_USER_AGENT)
            .header("Referer", "https://www.youtube.com/tv")
        headers.forEach { (k, v) -> v.firstOrNull()?.let { builder.header(k, it) } }
        http.newCall(builder.build()).execute().use { response ->
            debug("  ${url.substringBefore('?').substringAfterLast('/')} -> HTTP ${response.code}")
        }
    }

    private fun fmt(sec: Float) = String.format(java.util.Locale.US, "%.3f", sec)

    private fun newCpn(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return (1..16).map { chars.random() }.joinToString("")
    }
}
