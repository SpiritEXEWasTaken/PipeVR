package org.schabi.newpipe.pipevr

import android.content.Context
import androidx.preference.PreferenceManager
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeTvAccountFeeds
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * PipeVR: "Sign in with a code" exactly like the YouTube TV app (and SmartTube) does it:
 * OAuth 2.0 device flow with the TV app's client id/secret, which are read from the TV app's
 * base.js on youtube.com/tv.
 */
object YouTubeTvAuth {
    private const val TV_URL = "https://www.youtube.com/tv"
    private const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
    private const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
    private const val ACCOUNTS_URL =
        "https://www.youtube.com/youtubei/v1/account/accounts_list?prettyPrint=false"
    private const val SCOPE =
        "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"

    const val ACTIVATE_URL = "https://yt.be/activate"
    const val ACTIVATE_DISPLAY = "yt.be/activate"

    private const val PREF_CLIENT_ID = "pipevr_tv_client_id"
    private const val PREF_CLIENT_SECRET = "pipevr_tv_client_secret"
    private const val PREF_DEVICE_ID = "pipevr_tv_device_id"

    private val JSON = "application/json".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class ClientCredentials(val id: String, val secret: String)

    data class DeviceCode(
        val deviceCode: String,
        /** formatted for display, e.g. "ABC-DEF-GHI" */
        val userCode: String,
        val expiresInSeconds: Int,
        val intervalSeconds: Int,
    ) {
        /** Scanning this with a phone opens the activation page with the code filled in. */
        val qrUrl: String get() = "https://youtube.com/qr/activate/" + userCode.replace(" ", "-")
    }

    sealed class TokenResult {
        data class Success(
            val accessToken: String,
            val refreshToken: String?,
            val expiresInSeconds: Int,
        ) : TokenResult()

        object Pending : TokenResult()
        object SlowDown : TokenResult()
        object Expired : TokenResult()
        object Denied : TokenResult()
        data class Error(val message: String) : TokenResult()
    }

    /** One selectable identity of a Google account: the main channel or a brand channel. */
    data class Identity(
        val name: String,
        val email: String?,
        val handle: String?,
        val avatarUrl: String?,
        val pageId: String?,
        val isSelected: Boolean,
        val hasChannel: Boolean,
    )

    // ---------------------------------------------------------------------------------------
    // TV app client credentials
    // ---------------------------------------------------------------------------------------

    @Throws(IOException::class)
    fun getClientCredentials(context: Context, forceRefresh: Boolean = false): ClientCredentials {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val cachedId = prefs.getString(PREF_CLIENT_ID, null)
        val cachedSecret = prefs.getString(PREF_CLIENT_SECRET, null)
        if (!forceRefresh && cachedId != null && cachedSecret != null) {
            return ClientCredentials(cachedId, cachedSecret)
        }
        val credentials = scrapeClientCredentials()
        prefs.edit()
            .putString(PREF_CLIENT_ID, credentials.id)
            .putString(PREF_CLIENT_SECRET, credentials.secret)
            .apply()
        return credentials
    }

    private fun scrapeClientCredentials(): ClientCredentials {
        val html = getText(TV_URL)
        val baseJsPatterns = listOf(
            Regex("""id="base-js" src="(.*?)""""),
            Regex("""\.src = '(.*?m=base)'"""),
            Regex("""\.src = '(.*?)'; .\.id = 'base-js'"""),
        )
        var scriptUrl = baseJsPatterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
            ?: throw IOException("Could not find the YouTube TV app script")
        scriptUrl = absolute(scriptUrl)

        fun extract(js: String): ClientCredentials? {
            // only the first match is valid; later ones give HTTP 401 (per SmartTube)
            val m = Regex(
                """clientId:"([-\w]+\.apps\.googleusercontent\.com)",\n?[${'$'}\w]+:"(\w+)""""
            ).find(js) ?: return null
            return ClientCredentials(m.groupValues[1], m.groupValues[2])
        }

        extract(getText(scriptUrl))?.let { return it }
        // legacy script layout
        val legacyUrl = scriptUrl.replace("/dg=0/", "/exm=base/ed=1/").replace("/m=base", "/m=main")
        if (legacyUrl != scriptUrl) {
            extract(getText(legacyUrl))?.let { return it }
        }
        throw IOException("Could not find the YouTube TV app sign-in id")
    }

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://www.youtube.com$url"
        else -> "https://www.youtube.com/$url"
    }

    // ---------------------------------------------------------------------------------------
    // Device flow
    // ---------------------------------------------------------------------------------------

    private fun deviceId(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.getString(PREF_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_DEVICE_ID, id).apply()
        return id
    }

    @Throws(IOException::class)
    fun requestDeviceCode(context: Context): DeviceCode {
        val creds = getClientCredentials(context)
        val body = JsonObject.builder()
            .value("client_id", creds.id)
            .value("device_id", deviceId(context))
            .value("model_name", "ytlr::")
            .value("scope", SCOPE)
            .done()
        val (code, json) = postJson(DEVICE_CODE_URL, body)
        if (code == 401 || code == 400) {
            // the scraped client id went stale; fetch it again once
            val fresh = getClientCredentials(context, forceRefresh = true)
            body["client_id"] = fresh.id
            val (code2, json2) = postJson(DEVICE_CODE_URL, body)
            return parseDeviceCode(code2, json2)
        }
        return parseDeviceCode(code, json)
    }

    private fun parseDeviceCode(code: Int, json: JsonObject): DeviceCode {
        val deviceCode = json.getString("device_code")
        val userCode = json.getString("user_code")
        if (code !in 200..299 || deviceCode == null || userCode == null) {
            throw IOException(
                "YouTube did not give a sign-in code (HTTP $code " +
                    (json.getString("error") ?: "") + ")"
            )
        }
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            expiresInSeconds = json.getInt("expires_in", 1800),
            intervalSeconds = json.getInt("interval", 5).coerceAtLeast(3),
        )
    }

    /** One poll of the token endpoint while the user is entering the code. */
    fun pollToken(context: Context, deviceCode: DeviceCode): TokenResult {
        return try {
            val creds = getClientCredentials(context)
            val body = JsonObject.builder()
                .value("code", deviceCode.deviceCode)
                .value("client_id", creds.id)
                .value("client_secret", creds.secret)
                .value("grant_type", "http://oauth.net/grant_type/device/1.0")
                .done()
            parseToken(postJson(TOKEN_URL, body))
        } catch (e: IOException) {
            TokenResult.Error(e.message ?: e.toString())
        }
    }

    fun refresh(context: Context, refreshToken: String): TokenResult {
        return try {
            val creds = getClientCredentials(context)
            val body = JsonObject.builder()
                .value("refresh_token", refreshToken)
                .value("client_id", creds.id)
                .value("client_secret", creds.secret)
                .value("grant_type", "refresh_token")
                .done()
            parseToken(postJson(TOKEN_URL, body))
        } catch (e: IOException) {
            TokenResult.Error(e.message ?: e.toString())
        }
    }

    private fun parseToken(response: Pair<Int, JsonObject>): TokenResult {
        val (code, json) = response
        val accessToken = json.getString("access_token")
        if (code in 200..299 && accessToken != null) {
            return TokenResult.Success(
                accessToken,
                json.getString("refresh_token"),
                json.getInt("expires_in", 3600),
            )
        }
        return when (json.getString("error")) {
            "authorization_pending" -> TokenResult.Pending
            "slow_down" -> TokenResult.SlowDown
            "expired_token" -> TokenResult.Expired
            "access_denied" -> TokenResult.Denied
            // YouTube answers 428 while it waits for the user (per SmartTube)
            null -> if (code == 428) TokenResult.Pending
            else TokenResult.Error("HTTP $code")
            else -> TokenResult.Error(
                json.getString("error_description") ?: json.getString("error")
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Accounts & brand channels
    // ---------------------------------------------------------------------------------------

    @Throws(IOException::class)
    fun listIdentities(accessToken: String): List<Identity> {
        val body = JsonObject.builder()
            .`object`("context")
            .`object`("client")
            .value("clientName", YoutubeTvAccountFeeds.TV_CLIENT_NAME)
            .value("clientVersion", YoutubeTvAccountFeeds.TV_CLIENT_VERSION)
            .value("userAgent", YoutubeTvAccountFeeds.TV_USER_AGENT)
            .value("hl", "en")
            .value("gl", "US")
            .end()
            .end()
            .`object`("accountReadMask")
            .value("returnOwner", true)
            .value("returnBrandAccounts", true)
            .value("returnPersonaAccounts", false)
            .end()
            .done()
        val request = Request.Builder()
            .url(ACCOUNTS_URL)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", YoutubeTvAccountFeeds.TV_USER_AGENT)
            .header("Referer", TV_URL)
            .post(JsonWriter.string(body).toRequestBody(JSON))
            .build()
        val json = http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("Could not load your channels (HTTP ${response.code})")
            }
            JsonParser.`object`().from(text)
        }

        val identities = mutableListOf<Identity>()
        val sections = json.getArray("contents")
        for (i in 0 until sections.size) {
            val sectionContents = sections.getObject(i)
                .getObject("accountSectionListRenderer").getArray("contents")
            for (j in 0 until sectionContents.size) {
                val items = sectionContents.getObject(j)
                    .getObject("accountItemSectionRenderer").getArray("contents")
                for (k in 0 until items.size) {
                    val item = items.getObject(k).getObject("accountItem")
                    if (item.isEmpty() || item.getBoolean("isDisabled", false)) continue
                    identities += Identity(
                        name = text(item.getObject("accountName")) ?: "YouTube",
                        email = text(item.getObject("accountByline")),
                        handle = text(item.getObject("channelHandle")),
                        avatarUrl = item.getObject("accountPhoto").getArray("thumbnails")
                            .lastObject()?.getString("url"),
                        pageId = item.getObject("serviceEndpoint")
                            .getObject("selectActiveIdentityEndpoint")
                            .getArray("supportedTokens").firstPageId(),
                        isSelected = item.getBoolean("isSelected", false),
                        hasChannel = item.getBoolean("hasChannel", false),
                    )
                }
            }
        }
        return identities
    }

    private fun JsonArray.lastObject(): JsonObject? = if (isEmpty()) null else getObject(size - 1)

    private fun JsonArray.firstPageId(): String? {
        for (i in 0 until size) {
            val pageId = getObject(i).getObject("pageIdToken").getString("pageId")
            if (!pageId.isNullOrEmpty()) return pageId
        }
        return null
    }

    private fun text(obj: JsonObject?): String? {
        if (obj == null || obj.isEmpty()) return null
        obj.getString("simpleText")?.let { return it }
        val runs = obj.getArray("runs")
        if (runs.isEmpty()) return null
        return (0 until runs.size).joinToString("") { runs.getObject(it).getString("text", "") }
    }

    // ---------------------------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------------------------

    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", YoutubeTvAccountFeeds.TV_USER_AGENT)
            .header("Referer", TV_URL)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body.string()
        }
    }

    private fun postJson(url: String, body: JsonObject): Pair<Int, JsonObject> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", YoutubeTvAccountFeeds.TV_USER_AGENT)
            .header("Referer", TV_URL)
            .post(JsonWriter.string(body).toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            val json = try {
                JsonParser.`object`().from(text)
            } catch (e: Exception) {
                JsonObject()
            }
            return response.code to json
        }
    }
}
