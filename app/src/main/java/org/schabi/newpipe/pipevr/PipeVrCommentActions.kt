package org.schabi.newpipe.pipevr

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeTvAccountFeeds
import org.schabi.newpipe.util.Localization

/**
 * PipeVR: comments with the signed-in YouTube account: own comments first with a badge,
 * liking comments/replies and replying (as SmartTube does: actions from a comment's detail view).
 */
object PipeVrCommentActions {
    private val ACCENT = Color.parseColor("#4FC3F7")
    private const val BADGE_GREEN = "#2E7D32"

    @JvmStatic
    fun isOwn(item: CommentsInfoItem): Boolean {
        val account = YouTubeAccountManager.getSelected() ?: return false
        val author = item.uploaderName?.trim() ?: return false
        return author.equals(account.handle?.trim(), ignoreCase = true) ||
            author.equals(account.name.trim(), ignoreCase = true)
    }

    /** Copies of the account's comments shown on top of the list (originals stay in place). */
    private val topCopies: MutableSet<CommentsInfoItem> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    private fun SpannableStringBuilder.badge(text: String, color: String) {
        append("  ")
        val start = length
        append(" $text ")
        setSpan(BackgroundColorSpan(Color.parseColor(color)), start, length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(Color.WHITE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * The author's name with a "Your comment" badge for the account's own comments, plus a
     * "Shown at top" badge on the copy pinned to the top of the list.
     */
    @JvmStatic
    fun decorateAuthor(context: Context, item: CommentsInfoItem, name: CharSequence?): CharSequence? {
        if (name == null || !isOwn(item)) return name
        return SpannableStringBuilder(name).apply {
            badge(context.getString(R.string.pipevr_your_comment), BADGE_GREEN)
            if (item in topCopies) badge(context.getString(R.string.pipevr_shown_at_top), "#455A64")
        }
    }

    /** Adds a copy of the account's own comments on top; the originals stay where they are. */
    @JvmStatic
    fun ownFirst(info: CommentsInfo): CommentsInfo {
        if (YouTubeAccountManager.getSelected() == null) return info
        // the comments list is cached and handed back on every visit: drop the copies a
        // previous visit put on top, or they pile up (one more each time)
        val items: List<CommentsInfoItem> =
            (info.relatedItems ?: return info).filter { it !in topCopies }
        val copies = items.filter { isOwn(it) }.mapNotNull {
            try {
                org.schabi.newpipe.util.SerializedUtils.clone(it, CommentsInfoItem::class.java)
            } catch (e: Exception) {
                null
            }
        }
        if (copies.isEmpty()) {
            info.relatedItems = items
            return info
        }
        topCopies += copies
        info.relatedItems = copies + items
        return info
    }

    // ---- likes ----

    private fun post(endpoint: String, build: (com.grack.nanojson.JsonBuilder<JsonObject>) -> Unit) =
        YoutubeTvAccountFeeds.post(endpoint, { b -> build(b) }, NewPipe.getPreferredLocalization())

    private fun recordAll(node: Any?) {
        when (node) {
            is JsonObject -> {
                val c = node.getObject("commentRenderer")
                if (!c.isEmpty()) YoutubeTvAccountFeeds.recordComment(c)
                node.values.forEach { recordAll(it) }
            }
            is JsonArray -> node.forEach { recordAll(it) }
        }
    }

    /** Blocking: like/unlike a comment; returns the new liked state or null on failure. */
    private fun toggleLike(commentId: String): Boolean? {
        var actions = YoutubeTvAccountFeeds.COMMENT_LIKE_ACTIONS[commentId]
        if (actions == null) {
            val token = YoutubeTvAccountFeeds.COMMENT_DETAIL_TOKENS[commentId] ?: return null
            recordAll(post("next") { it.value("continuation", token) })
            actions = YoutubeTvAccountFeeds.COMMENT_LIKE_ACTIONS[commentId] ?: return null
        }
        val liked = YoutubeTvAccountFeeds.COMMENT_LIKED[commentId] == true
        val response = post("comment/perform_comment_action") { b ->
            b.array("actions").value(if (liked) actions[1] else actions[0]).end()
        }
        if (response.has("error")) return null
        YoutubeTvAccountFeeds.COMMENT_LIKED[commentId] = !liked
        return !liked
    }

    // ---- replies ----

    private fun replyParams(videoId: String, commentId: String): String {
        fun field(tag: Int, bytes: ByteArray) = byteArrayOf(tag.toByte(), bytes.size.toByte()) + bytes
        val proto = field(0x12, videoId.toByteArray()) + field(0x22, commentId.toByteArray()) +
            byteArrayOf(0x2a, 0x02, 0x08, 0x00, 0x50, 0x07)
        return Base64.encodeToString(proto, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun reply(videoId: String, commentId: String, text: String): Boolean {
        val response = post("comment/create_comment_reply") { b ->
            b.value("createReplyParams", replyParams(videoId, commentId)).value("commentText", text)
        }
        return response.has("actions") && !response.has("error")
    }

    // ---- UI ----

    /** Makes a comment's like button and a "Reply" link work with the account. */
    @JvmStatic
    fun bindItem(itemView: View, item: CommentsInfoItem, likesCount: TextView) {
        val thumb = itemView.findViewById<ImageView?>(R.id.detail_thumbs_up_img_view)
        val replyTag = "pipevr_reply"
        val row = likesCount.parent as? ViewGroup
        var replyView = row?.findViewWithTag<TextView>(replyTag)
        val id = item.commentId
        if (id == null || !YouTubePlaylists.isAvailable(item.serviceId)) {
            thumb?.setOnClickListener(null)
            likesCount.setOnClickListener(null)
            replyView?.visibility = View.GONE
            return
        }
        val context = itemView.context
        val normalColor = likesCount.currentTextColor.takeIf { likesCount.tag == null }
            ?: (likesCount.tag as Int)
        likesCount.tag = normalColor
        fun render() {
            val liked = YoutubeTvAccountFeeds.COMMENT_LIKED[id] == true
            if (liked) thumb?.setColorFilter(ACCENT) else thumb?.clearColorFilter()
            likesCount.setTextColor(if (liked) ACCENT else normalColor)
        }
        render()
        val onLike = View.OnClickListener {
            Single.fromCallable { java.util.Optional.ofNullable(toggleLike(id)) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ result ->
                    if (!result.isPresent) {
                        Toast.makeText(context, R.string.pipevr_playlist_failed,
                            Toast.LENGTH_SHORT).show()
                        return@subscribe
                    }
                    val base = if (item.likeCount >= 0) item.likeCount.toLong() else 0L
                    val shown = base + if (result.get()) 1 else 0
                    likesCount.text = Localization.shortCount(context, shown)
                    render()
                }, {
                    Toast.makeText(context, R.string.pipevr_playlist_failed, Toast.LENGTH_SHORT)
                        .show()
                })
        }
        thumb?.setOnClickListener(onLike)
        likesCount.setOnClickListener(onLike)

        // "Reply" on comments and replies (YouTube threads are one level deep: replying to a
        // reply replies to its comment, mentioning the reply's author)
        val parent = YoutubeTvAccountFeeds.COMMENT_PARENT[id]
        val canReply = parent != null || YoutubeTvAccountFeeds.COMMENT_DETAIL_TOKENS.containsKey(id)
        if (row != null && replyView == null) {
            replyView = TextView(context).apply {
                tag = replyTag
                setText(R.string.pipevr_reply)
                textSize = 13f
                setTextColor(ACCENT)
                setPadding(32, 0, 0, 0)
            }
            if (row is android.widget.RelativeLayout) {
                // after the date on the likes line (a RelativeLayout needs a place, or the
                // button lands in the top-left corner over the picture and name)
                val anchor = row.findViewById<View?>(R.id.itemPublishedTime)
                val lp = android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (anchor != null) {
                    lp.addRule(android.widget.RelativeLayout.RIGHT_OF, anchor.id)
                    lp.addRule(android.widget.RelativeLayout.ALIGN_BASELINE, anchor.id)
                } else {
                    lp.addRule(android.widget.RelativeLayout.RIGHT_OF, likesCount.id)
                    lp.addRule(android.widget.RelativeLayout.ALIGN_BASELINE, likesCount.id)
                }
                row.addView(replyView, lp)
            } else {
                row.addView(replyView)
            }
        }
        // "Delete" on the account's own comments
        val deleteTag = "pipevr_delete"
        var deleteView = row?.findViewWithTag<TextView>(deleteTag)
        if (row != null && deleteView == null && replyView != null) {
            deleteView = TextView(context).apply {
                tag = deleteTag
                setText(R.string.pipevr_delete_comment)
                textSize = 13f
                setTextColor(Color.parseColor("#EF5350"))
                setPadding(32, 0, 0, 0)
                this.id = View.generateViewId()
            }
            if (replyView.id == View.NO_ID) replyView.id = View.generateViewId()
            if (row is android.widget.RelativeLayout) {
                val lp = android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.addRule(android.widget.RelativeLayout.RIGHT_OF, replyView.id)
                lp.addRule(android.widget.RelativeLayout.ALIGN_BASELINE, replyView.id)
                row.addView(deleteView, lp)
            } else {
                row.addView(deleteView)
            }
        }
        val own = isOwn(item) && item !in topCopies
        // YouTube's TV app offers no Delete for comments (only like/dislike): hidden
        deleteView?.visibility = View.GONE
        deleteView?.setOnClickListener {
            AlertDialog.Builder(context)
                .setMessage(R.string.pipevr_delete_comment_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.pipevr_delete_comment) { _, _ ->
                    Single.fromCallable { deleteComment(id) }
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe({ ok ->
                            if (ok) {
                                itemView.visibility = View.GONE
                                itemView.layoutParams = itemView.layoutParams?.apply { height = 0 }
                            }
                            Toast.makeText(context,
                                if (ok) R.string.pipevr_comment_deleted
                                else R.string.pipevr_comment_delete_unavailable,
                                Toast.LENGTH_LONG).show()
                        }, {
                            Toast.makeText(context, R.string.pipevr_comment_delete_unavailable,
                                Toast.LENGTH_LONG).show()
                        })
                }
                .show()
        }
        replyView?.visibility = if (canReply) View.VISIBLE else View.GONE
        replyView?.setOnClickListener {
            val videoId = PipeVrPlayback.videoIdOf(item.url) ?: return@setOnClickListener
            showReplyDialog(context, videoId, id, parent, item.uploaderName,
                if (parent != null) item.uploaderName else null)
        }
    }

    private fun COMMENT_DETAIL_HAS(id: String): Boolean =
        YoutubeTvAccountFeeds.COMMENT_DETAIL_TOKENS.containsKey(id)

    /** Text shown on a button-like object (its label), lower case, for matching. */
    private fun labelOf(node: Any?, depth: Int = 0): String {
        if (depth > 6) return ""
        return when (node) {
            is JsonObject -> buildString {
                node.getString("simpleText")?.let { append(it).append(' ') }
                node.getString("iconType")?.let { append(it).append(' ') }
                node.getString("label")?.let { append(it).append(' ') }
                node.forEach { (k, v) ->
                    if (k != "performCommentActionEndpoint" && k != "commandMetadata") {
                        append(labelOf(v, depth + 1))
                    }
                }
            }
            is JsonArray -> buildString { node.forEach { append(labelOf(it, depth + 1)) } }
            is String -> ""
            else -> ""
        }.lowercase()
    }

    /** Finds the "Delete" action of a comment in its detail view (own comments have it). */
    private fun findDeleteAction(node: Any?): String? {
        when (node) {
            is JsonObject -> {
                // an object that leads to a comment action and is labelled "delete"
                for ((_, v) in node) {
                    if (v is JsonObject) {
                        val action = v.getObject("performCommentActionEndpoint").getString("action")
                            ?: v.getObject("serviceEndpoint").getObject("performCommentActionEndpoint")
                                .getString("action")
                        if (action != null) {
                            val label = labelOf(node)
                            android.util.Log.i("PipeVRActions", "comment action label: " +
                                label.take(120))
                            // only "delete" ("remove" also matches e.g. removing a like)
                            if (label.contains("delete")) {
                                return action
                            }
                        }
                    }
                }
                for ((_, v) in node) findDeleteAction(v)?.let { return it }
            }
            is JsonArray -> node.forEach { v -> findDeleteAction(v)?.let { return it } }
        }
        return null
    }

    /** Blocking: deletes one of the account's own comments. */
    private fun deleteComment(commentId: String): Boolean {
        val token = YoutubeTvAccountFeeds.COMMENT_DETAIL_TOKENS[commentId] ?: return false
        val detail = post("next") { it.value("continuation", token) }
        val action = findDeleteAction(detail)
        if (action == null) {
            android.util.Log.w("PipeVRActions", "no delete action for $commentId; detail keys " +
                detail.keys)
            return false
        }
        val response = post("comment/perform_comment_action") { b ->
            b.array("actions").value(action).end()
        }
        val results = response.getArray("actionResults")
        android.util.Log.i("PipeVRActions", "delete $commentId -> " +
            com.grack.nanojson.JsonWriter.string(results).take(400))
        val status = if (results.isEmpty()) null else results.getObject(0).getString("status")
        return !response.has("error") && (status == null || status == "STATUS_SUCCEEDED")
    }

    private fun showReplyDialog(context: Context, videoId: String, commentId: String,
                                fallbackId: String?, author: String?, mention: String?) {
        val input = EditText(context).apply {
            if (mention != null) {
                setText("$mention ")
                setSelection(text.length)
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            setHint(R.string.pipevr_comment_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.pipevr_reply_to, author ?: ""))
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pipevr_comment_post) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                // nested threads: reply to this reply; if YouTube refuses, to its comment
                Single.fromCallable {
                    reply(videoId, commentId, text) ||
                        (fallbackId != null && reply(videoId, fallbackId, text))
                }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ ok ->
                        Toast.makeText(context,
                            if (ok) R.string.pipevr_reply_posted else R.string.pipevr_playlist_failed,
                            Toast.LENGTH_SHORT).show()
                    }, {
                        Toast.makeText(context, R.string.pipevr_playlist_failed,
                            Toast.LENGTH_SHORT).show()
                    })
            }
            .show()
    }
}
