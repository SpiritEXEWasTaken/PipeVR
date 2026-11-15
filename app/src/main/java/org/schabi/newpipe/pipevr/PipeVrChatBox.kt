package org.schabi.newpipe.pipevr

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.helper.PlayerHolder

/** PipeVR: "Chat as …" bar to send YouTube live chat messages with the signed-in account. */
object PipeVrChatBox {
    /** Twitch and Kick chats (both kept connected by their chat extractors). */
    private fun showTwitchDialog(context: Context, serviceId: Int) {
        val kick = serviceId == org.schabi.newpipe.extractor.ServiceList.Kick.serviceId
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHint(R.string.pipevr_chat_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.pipevr_chat_as, accountName(serviceId)))
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pipevr_chat_send) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                Single.fromCallable {
                    if (kick) {
                        org.schabi.newpipe.extractor.services.kick.KickChat.current()
                            ?.sendMessage(text) == true
                    } else {
                        org.schabi.newpipe.extractor.services.twitch.TwitchChat.current()
                            ?.sendMessage(text) == true
                    }
                }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ ok ->
                        if (!ok) Toast.makeText(context, R.string.pipevr_playlist_failed,
                            Toast.LENGTH_SHORT).show()
                    }, {
                        Toast.makeText(context, R.string.pipevr_playlist_failed,
                            Toast.LENGTH_SHORT).show()
                    })
            }
            .show()
    }

    private fun currentInfo(): StreamInfo? =
        PlayerHolder.getInstance().player?.currentStreamInfo?.orElse(null)

    private fun channelIdOf(info: StreamInfo): String? =
        info.uploaderUrl?.substringAfter("/channel/", "")?.substringBefore('/')
            ?.takeIf { it.startsWith("UC") }

    /** Adds the bar on top of the chat list (only when signed in to YouTube). */
    @JvmStatic
    fun attach(root: FrameLayout, list: View) {
        val context = root.context
        val dp = { v: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
                context.resources.displayMetrics).toInt()
        }
        val bar = TextView(context).apply {
            text = context.getString(R.string.pipevr_chat_as,
                YouTubeAccountManager.getSelected()?.name ?: "")
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(230, 40, 40, 48))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { showDialog(context) }
        }
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP))
        bar.visibility = View.GONE
        // show it for Twitch/Kick live streams with an account
        val refresh = object : Runnable {
            override fun run() {
                val info = currentInfo()
                val live = info != null &&
                    info.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM
                // YouTube: no sending (YouTube refuses chat from the TV-style sign-in; README)
                val show = live && (
                    (info!!.serviceId == org.schabi.newpipe.extractor.ServiceList.Twitch.serviceId &&
                        org.schabi.newpipe.extractor.services.twitch.TwitchApi.isSignedIn()) ||
                    (info.serviceId == org.schabi.newpipe.extractor.ServiceList.Kick.serviceId &&
                        org.schabi.newpipe.extractor.services.kick.KickApi.isSignedIn()))
                if (show) {
                    bar.text = context.getString(R.string.pipevr_chat_as, accountName(info.serviceId))
                }
                bar.visibility = if (show) View.VISIBLE else View.GONE
                val top = if (show) dp(46) else 0
                if (list.paddingTop != top) {
                    list.setPadding(list.paddingLeft, top, list.paddingRight, list.paddingBottom)
                }
                if (bar.isAttachedToWindow) bar.postDelayed(this, 3000)
            }
        }
        bar.post(refresh)
    }

    private fun accountName(serviceId: Int): String =
        if (serviceId == org.schabi.newpipe.extractor.ServiceList.Kick.serviceId) {
            org.schabi.newpipe.extractor.services.kick.KickApi.getUsername() ?: ""
        } else if (serviceId == org.schabi.newpipe.extractor.ServiceList.Twitch.serviceId) {
            org.schabi.newpipe.extractor.services.twitch.TwitchApi.getDisplayName() ?: ""
        } else {
            YouTubeAccountManager.getSelected()?.name ?: ""
        }

    private fun showDialog(context: Context) {
        val info = currentInfo() ?: return
        if (info.serviceId == org.schabi.newpipe.extractor.ServiceList.Twitch.serviceId ||
            info.serviceId == org.schabi.newpipe.extractor.ServiceList.Kick.serviceId) {
            showTwitchDialog(context, info.serviceId)
            return
        }
        val videoId = PipeVrPlayback.videoIdOf(info.url) ?: return
        val channelId = channelIdOf(info)
        if (channelId == null) {
            Toast.makeText(context, R.string.pipevr_playlist_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHint(R.string.pipevr_chat_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.pipevr_chat_as,
                YouTubeAccountManager.getSelected()?.name ?: ""))
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pipevr_chat_send) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                Single.fromCallable { YouTubeAccountActions.sendLiveChat(videoId, channelId, text) }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ ok ->
                        if (!ok) Toast.makeText(context, R.string.pipevr_yt_chat_unsupported,
                            Toast.LENGTH_LONG).show()
                    }, {
                        Toast.makeText(context, R.string.pipevr_yt_chat_unsupported,
                            Toast.LENGTH_LONG).show()
                    })
            }
            .show()
    }
}
