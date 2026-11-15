package org.schabi.newpipe.pipevr

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * PipeVR: a site's own web page inside the app (signed in with the same cookies as the app's
 * sign-in pages), for things a site only allows on its website, like following on Twitch.
 */
class PipeVrWebPageActivity : AppCompatActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = WebViewClient()
        setContentView(web)
        web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: "about:blank")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        @JvmStatic
        fun open(context: Context, url: String, title: String) {
            context.startActivity(Intent(context, PipeVrWebPageActivity::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title))
        }
    }
}
