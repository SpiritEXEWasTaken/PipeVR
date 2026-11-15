package org.schabi.newpipe.pipevr

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.services.nebula.NebulaService
import org.schabi.newpipe.util.InfoCache
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.ThemeHelper

/**
 * PipeVR: the signed-in Nebula account. Only Nebula's long-lived API key is stored (never the
 * password); a fresh short-lived user token is made from it at start-up.
 */
object NebulaAccount {
    private const val PREF_KEY = "pipevr_nebula_key"

    @JvmStatic
    fun init(context: Context) {
        val key = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, null)
            ?: return
        Thread {
            try {
                NebulaService.setUserToken(NebulaService.authorize(key))
            } catch (e: Exception) {
                android.util.Log.w("PipeVR", "Nebula token refresh failed", e)
            }
        }.start()
    }

    fun hasKey(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, null) != null

    /** Signs in; returns nothing on success, throws on wrong password etc. */
    fun signIn(context: Context, email: String, password: String) {
        val key = NebulaService.loginKey(email, password)
        val token = NebulaService.authorize(key)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_KEY, key).apply()
        NebulaService.setUserToken(token)
        InfoCache.getInstance().clearCache()
    }

    fun signOut(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PREF_KEY).apply()
        NebulaService.setUserToken(null)
        InfoCache.getInstance().clearCache()
    }
}

/** PipeVR: Nebula sign-in / sign-out screen. */
class NebulaAccountActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private val disposables = CompositeDisposable()
    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Localization.assureCorrectAppLanguage(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.setTheme(this)
        title = getString(R.string.pipevr_nebula_account)
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
            setText(R.string.pipevr_nebula_account)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        val signedIn = NebulaAccount.hasKey(this)
        root.addView(TextView(this).apply {
            setText(if (signedIn) R.string.pipevr_nebula_signed_in
                else R.string.pipevr_nebula_sign_in_hint)
            textSize = 17f
            setPadding(0, 40, 0, 40)
        })
        if (signedIn) {
            root.addView(Button(this).apply {
                setText(R.string.pipevr_accounts_sign_out)
                setOnClickListener {
                    NebulaAccount.signOut(this@NebulaAccountActivity)
                    changed = true
                    render()
                }
            })
            return
        }
        val email = EditText(this).apply {
            setHint(R.string.pipevr_nebula_email)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        }
        val password = EditText(this).apply {
            setHint(R.string.pipevr_nebula_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        root.addView(email)
        root.addView(password)
        root.addView(Button(this).apply {
            setText(R.string.pipevr_nebula_sign_in)
            setOnClickListener { button ->
                val e = email.text.toString().trim()
                val p = password.text.toString()
                if (e.isEmpty() || p.isEmpty()) return@setOnClickListener
                button.isEnabled = false
                disposables.add(
                    Single.fromCallable { NebulaAccount.signIn(this@NebulaAccountActivity, e, p) }
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe({
                            changed = true
                            render()
                        }, { err ->
                            button.isEnabled = true
                            Toast.makeText(this@NebulaAccountActivity,
                                getString(R.string.pipevr_nebula_sign_in_failed, err.message ?: ""),
                                Toast.LENGTH_LONG).show()
                        })
                )
            }
        })
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    override fun finish() {
        if (changed) {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        super.finish()
    }
}
