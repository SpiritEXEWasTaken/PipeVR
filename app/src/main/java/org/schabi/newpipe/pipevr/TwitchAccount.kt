package org.schabi.newpipe.pipevr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.webkit.CookieManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.services.twitch.TwitchApi
import org.schabi.newpipe.util.InfoCache
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.PicassoHelper
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.views.TwitchLoginWebViewActivity

/** PipeVR: the signed-in Twitch account (token from the twitch.tv sign-in page). */
object TwitchAccount {
    private const val PREF_TOKEN = "pipevr_twitch_token"
    private const val PREF_LOGIN = "pipevr_twitch_login"
    private const val PREF_NAME = "pipevr_twitch_name"
    private const val PREF_AVATAR = "pipevr_twitch_avatar"

    @JvmStatic
    fun init(context: Context) {
        // Twitch's answers in the app log (for diagnosing follows / chat)
        TwitchApi.logger = java.util.function.Consumer { android.util.Log.i("PipeVRTwitch", it) }
        TwitchWebFollows.init(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        TwitchApi.setAccount(
            prefs.getString(PREF_TOKEN, null),
            prefs.getString(PREF_LOGIN, null),
            prefs.getString(PREF_NAME, null)
        )
    }

    @JvmStatic
    fun isSignedIn(): Boolean = TwitchApi.isSignedIn()

    fun name(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_NAME, null)

    fun avatar(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_AVATAR, null)

    /** Blocking: checks the token and saves the account. */
    fun signIn(context: Context, token: String) {
        val (login, name, avatar) = TwitchApi.whoAmI(token)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_TOKEN, token).putString(PREF_LOGIN, login)
            .putString(PREF_NAME, name).putString(PREF_AVATAR, avatar).apply()
        TwitchApi.setAccount(token, login, name)
        InfoCache.getInstance().clearCache()
    }

    fun signOut(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(PREF_TOKEN).remove(PREF_LOGIN).remove(PREF_NAME).remove(PREF_AVATAR).apply()
        TwitchApi.setAccount(null, null, null)
        InfoCache.getInstance().clearCache()
        // forget the twitch.tv session in the sign-in page too
        CookieManager.getInstance().removeAllCookies(null)
    }
}

/** PipeVR: Twitch sign-in / sign-out screen. */
class TwitchAccountActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Localization.assureCorrectAppLanguage(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.setTheme(this)
        title = getString(R.string.pipevr_twitch_account)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(32)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(root)
        render()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun render() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            setText(R.string.pipevr_twitch_account)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (TwitchAccount.isSignedIn()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(24), 0, dp(24))
            }
            val avatar = ImageView(this)
            row.addView(avatar, LinearLayout.LayoutParams(dp(56), dp(56)))
            TwitchAccount.avatar(this)?.let { PicassoHelper.loadAvatar(it).into(avatar) }
            row.addView(TextView(this).apply {
                text = TwitchAccount.name(this@TwitchAccountActivity) ?: ""
                textSize = 20f
                setPadding(dp(16), dp(12), 0, 0)
            })
            root.addView(row)
            root.addView(Button(this).apply {
                setText(R.string.pipevr_accounts_sign_out)
                setOnClickListener {
                    TwitchAccount.signOut(this@TwitchAccountActivity)
                    changed = true
                    render()
                }
            })
        } else {
            root.addView(TextView(this).apply {
                setText(R.string.pipevr_twitch_sign_in_hint)
                textSize = 17f
                setPadding(0, dp(16), 0, dp(16))
            })
            root.addView(Button(this).apply {
                setText(R.string.pipevr_twitch_sign_in)
                setOnClickListener {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(this@TwitchAccountActivity,
                        TwitchLoginWebViewActivity::class.java), 1)
                }
            })
        }
    }

    @Deprecated("startActivityForResult is enough here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val token = data?.getStringExtra("token")
        if (resultCode != Activity.RESULT_OK || token == null) return
        Single.fromCallable { TwitchAccount.signIn(this, token) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                changed = true
                render()
            }, {
                Toast.makeText(this, getString(R.string.pipevr_sign_in_error, it.message),
                    Toast.LENGTH_LONG).show()
            })
    }

    override fun finish() {
        if (changed) {
            // the Twitch front page (Following) depends on the account
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        super.finish()
    }
}
