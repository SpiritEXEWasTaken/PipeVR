package org.schabi.newpipe.pipevr

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R

/** PipeVR: "Add a comment…" row on top of a video's comments (signed-in YouTube account). */
object PipeVrCommentBox {
    @JvmStatic
    fun create(context: Context, videoUrl: String, onPosted: Runnable): View {
        val padding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
        ).toInt()
        val name = YouTubeAccountManager.getSelected()?.name
        return TextView(context).apply {
            text = context.getString(R.string.pipevr_add_comment, name ?: "")
            textSize = 16f
            alpha = 0.8f
            setPadding(padding, padding, padding, padding)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { showDialog(context, videoUrl, onPosted) }
        }
    }

    private fun showDialog(context: Context, videoUrl: String, onPosted: Runnable) {
        val videoId = PipeVrPlayback.videoIdOf(videoUrl) ?: return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            setHint(R.string.pipevr_comment_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.pipevr_comment_as,
                YouTubeAccountManager.getSelected()?.name ?: ""))
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pipevr_comment_post) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                Single.fromCallable { YouTubeAccountActions.comment(videoId, text) }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ ok ->
                        Toast.makeText(context,
                            if (ok) R.string.pipevr_comment_posted else R.string.pipevr_playlist_failed,
                            Toast.LENGTH_SHORT).show()
                        if (ok) onPosted.run()
                    }, {
                        Toast.makeText(context, R.string.pipevr_playlist_failed,
                            Toast.LENGTH_SHORT).show()
                    })
            }
            .show()
    }
}
