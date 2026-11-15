package org.schabi.newpipe.pipevr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.services.floatplane.FloatplaneService
import org.schabi.newpipe.util.InfoCache
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.views.FloatplaneLoginWebViewActivity

/** PipeVR: the signed-in Floatplane account (cookies of the floatplane.com sign-in page). */
object FloatplaneAccount {
    private const val PREF_COOKIES = "pipevr_floatplane_cookies"

    @JvmStatic
    fun init(context: Context) {
        FloatplaneService.setCookie(
            PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_COOKIES, null))
    }

    fun save(context: Context, cookies: String?) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_COOKIES, cookies).apply()
        FloatplaneService.setCookie(cookies)
        InfoCache.getInstance().clearCache()
    }
}

/** PipeVR: Floatplane sign-in / sign-out screen. */
class FloatplaneAccountActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Localization.assureCorrectAppLanguage(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.setTheme(this)
        title = getString(R.string.pipevr_floatplane_account)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (32 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(root)
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            setText(R.string.pipevr_floatplane_account)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            setText(if (FloatplaneService.isSignedIn()) R.string.pipevr_floatplane_signed_in
                else R.string.pipevr_floatplane_sign_in_hint)
            textSize = 17f
            setPadding(0, 40, 0, 40)
        })
        root.addView(Button(this).apply {
            if (FloatplaneService.isSignedIn()) {
                setText(R.string.pipevr_accounts_sign_out)
                setOnClickListener {
                    FloatplaneAccount.save(this@FloatplaneAccountActivity, null)
                    CookieManager.getInstance().removeAllCookies(null)
                    changed = true
                    render()
                }
            } else {
                setText(R.string.pipevr_floatplane_sign_in)
                setOnClickListener {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(this@FloatplaneAccountActivity,
                        FloatplaneLoginWebViewActivity::class.java), 1)
                }
            }
        })
    }

    @Deprecated("startActivityForResult is enough here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val cookies = data?.getStringExtra("cookies")
        if (resultCode != Activity.RESULT_OK || cookies == null) return
        FloatplaneAccount.save(this, cookies)
        changed = true
        render()
    }

    override fun finish() {
        if (changed) {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        super.finish()
    }
}
