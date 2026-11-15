package org.schabi.newpipe.pipevr

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import org.schabi.newpipe.extractor.services.youtube.YoutubeOAuth
import java.io.IOException

/**
 * PipeVR: the signed-in YouTube accounts.
 *
 * Every Google account signed in with a code can own several YouTube channels (its main
 * channel and brand channels). Each of them is one [Account] entry; entries of the same Google
 * account share its refresh token and differ by [Account.pageId]. Exactly one entry is selected.
 */
object YouTubeAccountManager {
    private const val TAG = "PipeVRAccounts"
    private const val PREF_ACCOUNTS = "pipevr_youtube_accounts"

    /** Refresh this long before the token really expires. */
    private const val EXPIRY_MARGIN_MS = 5 * 60 * 1000L

    data class Account(
        val name: String,
        val email: String?,
        val handle: String?,
        val avatarUrl: String?,
        /** sent as X-Goog-Pageid to act as this channel; null if YouTube gave none */
        val pageId: String?,
        val refreshToken: String,
        val selected: Boolean,
    ) {
        val subtitle: String
            get() = listOfNotNull(handle, email).distinct().joinToString(" · ")
    }

    private lateinit var appContext: Context
    private var accounts: List<Account> = emptyList()
    private val accessTokens = HashMap<String, Pair<String, Long>>() // refreshToken -> token,expiry
    private val listeners = mutableListOf<() -> Unit>()

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
        accounts = load()
        YoutubeOAuth.setTokenProvider { freshAccessTokenBlocking() }
        publishToExtractor()
    }

    @JvmStatic
    fun getAccounts(): List<Account> = accounts

    internal fun context(): Context = appContext

    @JvmStatic
    fun getSelected(): Account? = accounts.firstOrNull { it.selected }

    @JvmStatic
    fun isSignedIn(): Boolean = getSelected() != null

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /**
     * Called after a successful code sign-in: loads the Google account's channels and stores
     * them. Blocking (network), call off the main thread.
     */
    @Throws(IOException::class)
    fun addSignedInGoogleAccount(token: YouTubeTvAuth.TokenResult.Success) {
        val refreshToken = token.refreshToken
            ?: throw IOException("YouTube did not return a refresh token")
        synchronized(this) {
            accessTokens[refreshToken] = token.accessToken to
                (System.currentTimeMillis() + token.expiresInSeconds * 1000L)
        }
        val identities = YouTubeTvAuth.listIdentities(token.accessToken)
        val newAccounts = if (identities.isEmpty()) {
            listOf(Account("YouTube", null, null, null, null, refreshToken, true))
        } else {
            val selectedIndex = identities.indexOfFirst { it.isSelected }.coerceAtLeast(0)
            identities.mapIndexed { i, id ->
                Account(
                    name = id.name,
                    email = id.email,
                    handle = id.handle,
                    avatarUrl = id.avatarUrl,
                    // the channel is chosen by sending its page id (as SmartTube does)
                    pageId = id.pageId,
                    refreshToken = refreshToken,
                    selected = i == selectedIndex,
                )
            }
        }
        val email = newAccounts.firstNotNullOfOrNull { it.email }
        // replace an earlier sign-in of the same Google account, unselect everything else
        val kept = accounts.filterNot { old ->
            old.refreshToken == refreshToken || (email != null && old.email == email)
        }.map { it.copy(selected = false) }
        accounts = kept + newAccounts
        save()
        changed()
    }

    @JvmStatic
    fun select(account: Account) {
        accounts = accounts.map { it.copy(selected = it == account) }
        save()
        changed()
    }

    /** Removes the Google account the given entry belongs to, with all its channels. */
    @JvmStatic
    fun signOut(account: Account) {
        val wasSelected = accounts.any { it.selected && it.refreshToken == account.refreshToken }
        accounts = accounts.filterNot { it.refreshToken == account.refreshToken }
        synchronized(this) { accessTokens.remove(account.refreshToken) }
        if (wasSelected && accounts.isNotEmpty()) {
            accounts = accounts.mapIndexed { i, a -> a.copy(selected = i == 0) }
        }
        save()
        changed()
    }

    private fun changed() {
        publishToExtractor()
        // feeds of the previous account must not be shown from the cache
        org.schabi.newpipe.util.InfoCache.getInstance().clearCache()
        org.schabi.newpipe.extractor.services.youtube.YoutubeTvAccountFeeds.RESUME_SECONDS.clear()
        listeners.toList().forEach { it() }
    }

    private fun publishToExtractor() {
        val selected = getSelected()
        YoutubeOAuth.setAccount(selected != null, selected?.pageId)
    }

    /** Blocking; used by the extractor before each signed-in request. */
    @Synchronized
    private fun freshAccessTokenBlocking(): String? {
        val selected = getSelected() ?: return null
        val cached = accessTokens[selected.refreshToken]
        if (cached != null && cached.second - EXPIRY_MARGIN_MS > System.currentTimeMillis()) {
            return cached.first
        }
        return when (val result = YouTubeTvAuth.refresh(appContext, selected.refreshToken)) {
            is YouTubeTvAuth.TokenResult.Success -> {
                accessTokens[selected.refreshToken] = result.accessToken to
                    (System.currentTimeMillis() + result.expiresInSeconds * 1000L)
                result.accessToken
            }
            else -> {
                Log.w(TAG, "Token refresh failed: $result")
                cached?.first
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun load(): List<Account> {
        val raw = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString(PREF_ACCOUNTS, null) ?: return emptyList()
        return try {
            val array: JsonArray = JsonParser.array().from(raw)
            (0 until array.size).map { i ->
                val o = array.getObject(i)
                Account(
                    name = o.getString("name", "YouTube"),
                    email = o.getString("email"),
                    handle = o.getString("handle"),
                    avatarUrl = o.getString("avatar"),
                    pageId = o.getString("pageId"),
                    refreshToken = o.getString("refreshToken"),
                    selected = o.getBoolean("selected", false),
                )
            }.filter { it.refreshToken.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read saved accounts", e)
            emptyList()
        }
    }

    private fun save() {
        val array = JsonArray()
        accounts.forEach { a ->
            val o = JsonObject()
            o["name"] = a.name
            o["email"] = a.email
            o["handle"] = a.handle
            o["avatar"] = a.avatarUrl
            o["pageId"] = a.pageId
            o["refreshToken"] = a.refreshToken
            o["selected"] = a.selected
            array.add(o)
        }
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
            .putString(PREF_ACCOUNTS, JsonWriter.string(array))
            .apply()
    }
}
