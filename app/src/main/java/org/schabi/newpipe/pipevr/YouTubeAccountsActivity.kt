package org.schabi.newpipe.pipevr

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.util.PicassoHelper
import org.schabi.newpipe.util.ThemeHelper

/**
 * PipeVR: lists the signed-in Google accounts and their YouTube channels. Tap a channel to
 * switch to it; each Google account can be signed out; more accounts can be added.
 */
class YouTubeAccountsActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    /** the account selected when this screen opened, to restart the app only on a change */
    private var initialSelection: YouTubeAccountManager.Account? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Localization.assureCorrectAppLanguage(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.setTheme(this)
        title = getString(R.string.pipevr_accounts_title)
        initialSelection = YouTubeAccountManager.getSelected()
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(list) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun render() {
        list.removeAllViews()
        list.addView(text(26f, bold = true).apply { setText(R.string.pipevr_accounts_title) })

        val accounts = YouTubeAccountManager.getAccounts()
        if (accounts.isEmpty()) {
            list.addView(text(18f).apply {
                setText(R.string.pipevr_accounts_empty)
                setPadding(0, dp(16), 0, dp(16))
            })
        }

        accounts.groupBy { it.refreshToken }.values.forEach { channels ->
            val email = channels.firstNotNullOfOrNull { it.email }
            list.addView(text(16f, bold = true).apply {
                text = email ?: getString(R.string.pipevr_accounts_google_account)
                setPadding(0, dp(24), 0, dp(8))
                alpha = 0.7f
            })
            channels.forEach { account -> list.addView(channelRow(account)) }
            list.addView(Button(this).apply {
                setText(R.string.pipevr_accounts_sign_out)
                setOnClickListener { confirmSignOut(channels.first(), email) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        list.addView(Button(this).apply {
            setText(
                if (accounts.isEmpty()) R.string.pipevr_sign_in_title
                else R.string.pipevr_accounts_add
            )
            textSize = 18f
            setOnClickListener {
                startActivityForResult(
                    Intent(this@YouTubeAccountsActivity, YouTubeSignInActivity::class.java),
                    REQUEST_SIGN_IN
                )
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(32) })
    }

    private fun channelRow(account: YouTubeAccountManager.Account): LinearLayout {
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            val avatar = ImageView(context)
            addView(avatar, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(16)
            })
            if (!account.avatarUrl.isNullOrEmpty()) {
                PicassoHelper.loadAvatar(account.avatarUrl).into(avatar)
            } else {
                avatar.setImageResource(R.drawable.ic_account)
            }
            addView(texts)
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            texts.addView(text(20f, bold = account.selected).apply {
                text = if (account.selected) "✓  ${account.name}" else account.name
                if (account.selected) setTextColor(Color.parseColor("#4FC3F7"))
            })
            if (account.subtitle.isNotEmpty()) {
                texts.addView(text(14f).apply {
                    text = account.subtitle
                    alpha = 0.7f
                })
            }
            setOnClickListener {
                if (!account.selected) {
                    YouTubeAccountManager.select(account)
                }
                // back to the main screen, showing the chosen account's feeds
                finish()
            }
        }
    }

    private fun confirmSignOut(account: YouTubeAccountManager.Account, email: String?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.pipevr_accounts_sign_out)
            .setMessage(getString(R.string.pipevr_accounts_sign_out_message, email ?: account.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pipevr_accounts_sign_out) { _, _ ->
                YouTubeAccountManager.signOut(account)
                render()
            }
            .show()
    }

    @Deprecated("startActivityForResult is enough here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        render()
    }

    override fun finish() {
        val changed = YouTubeAccountManager.getSelected() != initialSelection
        if (changed) {
            // the main screen's tabs and feeds depend on the account: rebuild the main screen
            // (in-process, without killing the app)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        super.finish()
    }

    companion object {
        private const val REQUEST_SIGN_IN = 1
    }
}
