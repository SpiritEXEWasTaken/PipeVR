package org.schabi.newpipe.pipevr

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONTokener
import org.schabi.newpipe.extractor.services.twitch.TwitchApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * PipeVR: Twitch no longer gives apps your follow list. Its website still shows you which
 * followed channels are live, so an invisible web view opens twitch.tv's "Following - Live"
 * page (signed in with the cookies of the Twitch sign-in) and reads the channel names from it.
 */
object TwitchWebFollows : TwitchApi.FollowedLiveProvider {
    private const val TAG = "PipeVRTwitch"
    private const val URL = "https://www.twitch.tv/directory/following/live"
    private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    // channel links on the page: live cards first, then the sidebar's followed channels
    private const val SCRIPT = """(function() {
        var out = [], seen = {};
        var skip = {directory:1, search:1, settings:1, subscriptions:1, inventory:1, drops:1,
                    downloads:1, jobs:1, p:1, turbo:1, friends:1, messages:1, wallet:1, videos:1,
                    following:1, prime:1, store:1, legal:1};
        var sel = 'a[data-a-target="preview-card-image-link"], ' +
                  'a[data-a-target="preview-card-channel-link"], ' +
                  'a[data-a-target="preview-card-title-link"], ' +
                  'a[data-test-selector="followed-channel"]';
        document.querySelectorAll(sel).forEach(function(a) {
            var m = (a.getAttribute('href') || '').match(/^\/([A-Za-z0-9_]+)(?:\/|\?|$)/);
            if (!m) return;
            var l = m[1].toLowerCase();
            if (skip[l] || seen[l]) return;
            seen[l] = 1;
            out.push(l);
        });
        var empty = !!document.querySelector('[data-a-target="following-live-empty-state"], ' +
            '[data-test-selector="following-live-empty-state"]');
        return JSON.stringify({logins: out, empty: empty, path: location.pathname});
    })()"""

    @JvmStatic
    fun init(context: Context) {
        app = context.applicationContext
        TwitchApi.followedLiveProvider = this
    }

    override fun liveLogins(): List<String> = synchronized(lock) {
        if (Looper.myLooper() == Looper.getMainLooper()) return emptyList()
        val result = AtomicReference<List<String>>(emptyList())
        val done = CountDownLatch(1)
        val holder = AtomicReference<WebView?>(null)
        main.post { start(result, done, holder) }
        if (!done.await(20, TimeUnit.SECONDS)) Log.w(TAG, "twitch.tv following page timed out")
        main.post {
            holder.get()?.let {
                it.stopLoading()
                it.destroy()
            }
        }
        result.get()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(
        result: AtomicReference<List<String>>,
        done: CountDownLatch,
        holder: AtomicReference<WebView?>,
    ) {
        try {
            val web = WebView(app)
            holder.set(web)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.userAgentString = DESKTOP_UA
            CookieManager.getInstance().setAcceptCookie(true)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    poll(view, result, done, 0)
                }
            }
            web.loadUrl(URL)
        } catch (e: Exception) {
            Log.w(TAG, "web view failed", e)
            done.countDown()
        }
    }

    /** Twitch fills the page with script: look a few times until the cards are there. */
    private fun poll(
        view: WebView,
        result: AtomicReference<List<String>>,
        done: CountDownLatch,
        attempt: Int,
    ) {
        if (done.count == 0L) return
        view.evaluateJavascript(SCRIPT) { encoded ->
            if (done.count == 0L) return@evaluateJavascript
            try {
                val json = org.json.JSONObject(JSONTokener(encoded).nextValue() as String)
                val arr: JSONArray = json.getJSONArray("logins")
                val logins = (0 until arr.length()).map { arr.getString(it) }
                val empty = json.optBoolean("empty")
                if (logins.isNotEmpty() || empty || attempt >= 24) {
                    Log.i(TAG, "twitch.tv following page (${json.optString("path")}): " +
                        "${logins.size} channels, empty=$empty, tries=$attempt")
                    result.set(logins)
                    done.countDown()
                    return@evaluateJavascript
                }
            } catch (e: Exception) {
                // page not ready yet
            }
            main.postDelayed({ poll(view, result, done, attempt + 1) }, 600)
        }
    }
}
