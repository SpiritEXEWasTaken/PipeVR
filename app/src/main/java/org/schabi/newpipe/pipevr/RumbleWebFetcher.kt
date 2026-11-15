package org.schabi.newpipe.pipevr

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener
import org.schabi.newpipe.extractor.services.rumble.RumbleService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * PipeVR: Rumble's Cloudflare check refuses the app's HTTP client, so blocked requests are
 * repeated in an invisible WebView (the system browser engine), which passes the check and
 * solves its JavaScript challenge when one is shown. One request at a time.
 */
object RumbleWebFetcher : RumbleService.ChallengeFetcher {
    private const val TAG = "PipeVRRumble"
    private const val TIMEOUT_S = 25L
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @JvmStatic
    fun init(context: Context) {
        app = context.applicationContext
        RumbleService.setChallengeFetcher(this)
    }

    override fun fetch(url: String, json: Boolean): String? = synchronized(lock) {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val result = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        val view = AtomicReference<WebView?>(null)
        main.post { start(url, json, result, done, view) }
        if (!done.await(TIMEOUT_S, TimeUnit.SECONDS)) Log.w(TAG, "timed out: $url")
        main.post {
            view.get()?.let {
                it.stopLoading()
                it.destroy()
            }
        }
        result.get()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(
        url: String,
        json: Boolean,
        result: AtomicReference<String?>,
        done: CountDownLatch,
        holder: AtomicReference<WebView?>,
    ) {
        try {
            val web = WebView(app)
            holder.set(web)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String?) {
                    // the challenge page finishes too, then reloads by itself: poll the content
                    poll(view, json, result, done, 0)
                }
            }
            web.loadUrl(url, mapOf("Referer" to "https://rumble.com/"))
        } catch (e: Exception) {
            Log.w(TAG, "WebView failed", e)
            done.countDown()
        }
    }

    private fun poll(
        view: WebView,
        json: Boolean,
        result: AtomicReference<String?>,
        done: CountDownLatch,
        attempt: Int,
    ) {
        if (done.count == 0L) return
        val script = if (json) "document.body ? document.body.innerText : ''"
        else "document.documentElement.outerHTML"
        view.evaluateJavascript(script) { encoded ->
            if (done.count == 0L) return@evaluateJavascript
            val text = try {
                JSONTokener(encoded).nextValue() as? String
            } catch (e: Exception) {
                null
            }
            val challenge = text == null || text.contains("Just a moment") ||
                text.contains("challenge-platform") && text.length < 20000
            if (!challenge && !text.isNullOrBlank()) {
                result.set(text)
                done.countDown()
            } else if (attempt < 40) {
                main.postDelayed({ poll(view, json, result, done, attempt + 1) }, 500)
            }
        }
    }
}
