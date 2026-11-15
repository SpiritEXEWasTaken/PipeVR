package org.schabi.newpipe.pipevr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.Localization
import java.util.concurrent.atomic.AtomicInteger

/**
 * PipeVR: the Shorts panels inside VR, pressed with the controllers' pointers.
 * - the Shorts panel (next to the tall screen): title, channel, Previous, Like, Dislike,
 *   Comments, Next
 * - the comments panel (other side): a few comments at a time, More and Close
 * Both are drawn as pictures; [version] changes whenever one of them needs redrawing.
 */
class VrShortsPanels(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val version = AtomicInteger(0)

    @Volatile private var info: StreamInfo? = null
    @Volatile private var rating = YouTubeAccountActions.Rating.NONE
    @Volatile private var commentsOpen = false
    @Volatile private var comments: List<CommentsInfoItem> = emptyList()
    @Volatile private var commentsLoading = false
    @Volatile private var commentsPage = 0
    @Volatile private var flash: String? = null      // button just pressed (drawn highlighted)

    // button areas in panel coordinates (0..1), filled while drawing
    private data class Button(val id: String, val rect: RectF)
    @Volatile private var shortsButtons: List<Button> = emptyList()
    @Volatile private var commentButtons: List<Button> = emptyList()

    fun version(): Int = version.get()
    private fun changed() = version.incrementAndGet()

    /** Called often (main thread): follows the player's current short. */
    fun poll() {
        val player = PlayerHolder.getInstance().player
        val shorts = player?.shortsController
        val now = if (shorts != null && shorts.isActive) shorts.currentInfo else null
        if (now?.url != info?.url) {
            info = now
            rating = YouTubeAccountActions.Rating.NONE
            commentsOpen = false
            comments = emptyList()
            commentsPage = 0
            changed()
            val videoId = now?.url?.let { PipeVrPlayback.videoIdOf(it) }
            if (videoId != null && YouTubePlaylists.isAvailable(now.serviceId)) {
                Single.fromCallable { YouTubeAccountActions.videoState(videoId).rating }
                    .subscribeOn(Schedulers.io())
                    .subscribe({ r ->
                        if (info?.url == now.url) {
                            rating = r
                            changed()
                        }
                    }, { })
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 16, 16, 22) }
    private val buttonBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 44, 44, 54) }
    private val buttonOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3D6BE0") }
    private val buttonFlash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6F8FE8") }

    private fun text(size: Float, bold: Boolean = false, color: Int = Color.WHITE) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun layout(s: CharSequence, p: TextPaint, width: Int, lines: Int): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, width)
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

    /** The Shorts panel, or null when the video isn't shown as a short. */
    fun renderShorts(): Bitmap? {
        val current = info ?: return null
        val w = 560
        val h = 980
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 36f, 36f, bg)
        val pad = 32
        var y = 36f
        val title = layout(current.name ?: "", text(34f, true), w - 2 * pad, 3)
        c.save(); c.translate(pad.toFloat(), y); title.draw(c); c.restore()
        y += title.height + 10
        c.drawText(current.uploaderName ?: "", pad.toFloat(), y + 28, text(26f, color = Color.argb(200, 255, 255, 255)))
        y += 64

        val buttons = ArrayList<Button>()
        fun count(n: Long) = if (n >= 0) Localization.shortCount(context, n) else ""
        val rows = listOf(
            Triple("prev", "▲  Previous", ""),
            Triple("like", "👍  Like", count(current.likeCount)),
            Triple("dislike", "👎  Dislike", count(current.dislikeCount)),
            Triple("comments", if (commentsOpen) "💬  Hide comments" else "💬  Comments", ""),
            Triple("next", "▼  Next", ""),
        )
        val bh = (h - y - 36f - 4 * 18f) / rows.size
        for ((id, label, extra) in rows) {
            val r = RectF(pad.toFloat(), y, (w - pad).toFloat(), y + bh)
            val on = (id == "like" && rating == YouTubeAccountActions.Rating.LIKE) ||
                (id == "dislike" && rating == YouTubeAccountActions.Rating.DISLIKE) ||
                (id == "comments" && commentsOpen)
            c.drawRoundRect(r, 24f, 24f, when {
                flash == id -> buttonFlash
                on -> buttonOn
                else -> buttonBg
            })
            val tp = text(34f, true)
            c.drawText(label, r.left + 30, r.centerY() + 12, tp)
            if (extra.isNotEmpty()) {
                val ep = text(30f, color = Color.argb(220, 255, 255, 255))
                c.drawText(extra, r.right - 30 - ep.measureText(extra), r.centerY() + 11, ep)
            }
            buttons += Button(id, RectF(r.left / w, r.top / h, r.right / w, r.bottom / h))
            y += bh + 18f
        }
        shortsButtons = buttons
        return bmp
    }

    /** The comments panel, or null when closed. */
    fun renderComments(): Bitmap? {
        if (!commentsOpen || info == null) return null
        val w = 760
        val h = 1000
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 36f, 36f, bg)
        val pad = 32
        c.drawText("Comments", pad.toFloat(), 68f, text(40f, true))
        val buttons = ArrayList<Button>()
        // Close (top right)
        val close = RectF(w - pad - 170f, 22f, (w - pad).toFloat(), 86f)
        c.drawRoundRect(close, 20f, 20f, if (flash == "close") buttonFlash else buttonBg)
        c.drawText("Close", close.left + 42, close.centerY() + 11, text(30f, true))
        buttons += Button("close", RectF(close.left / w, close.top / h, close.right / w, close.bottom / h))

        var y = 112f
        val per = 6
        val shown = comments.drop(commentsPage * per).take(per)
        when {
            commentsLoading -> c.drawText("Loading…", pad.toFloat(), y + 40, text(30f))
            comments.isEmpty() -> c.drawText("No comments", pad.toFloat(), y + 40, text(30f))
        }
        val bottomLimit = h - 120f
        for (item in shown) {
            val author = layout(item.uploaderName ?: "", text(26f, true, Color.parseColor("#9DB7FF")), w - 2 * pad, 1)
            val raw = item.commentText?.content ?: ""
            val plain = androidx.core.text.HtmlCompat.fromHtml(raw, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
            val body = layout(plain, text(28f), w - 2 * pad, 4)
            if (y + author.height + body.height > bottomLimit) break
            c.save(); c.translate(pad.toFloat(), y); author.draw(c); c.restore()
            y += author.height + 4
            c.save(); c.translate(pad.toFloat(), y); body.draw(c); c.restore()
            y += body.height + 22
        }
        // More (bottom), going back to the start after the last page
        if (comments.size > per) {
            val more = RectF(pad.toFloat(), h - 100f, (w - pad).toFloat(), h - 30f)
            c.drawRoundRect(more, 22f, 22f, if (flash == "more") buttonFlash else buttonBg)
            val last = (commentsPage + 1) * per >= comments.size
            c.drawText(if (last) "Back to the top" else "More comments", more.left + 30,
                more.centerY() + 11, text(30f, true))
            buttons += Button("more", RectF(more.left / w, more.top / h, more.right / w, more.bottom / h))
        }
        commentButtons = buttons
        return bmp
    }

    // ------------------------------------------------------------------ clicks

    /** A trigger press on a panel: [panel] 1 = Shorts, 2 = comments; u/v from its top left. */
    fun onClick(panel: Int, u: Float, v: Float) {
        val list = if (panel == 1) shortsButtons else commentButtons
        val hit = list.firstOrNull { it.rect.contains(u, v) } ?: return
        main.post { press(hit.id) }
    }

    private fun press(id: String) {
        val player = PlayerHolder.getInstance().player ?: return
        flash = id
        changed()
        main.postDelayed({
            if (flash == id) {
                flash = null
                changed()
            }
        }, 250)
        val current = info ?: return
        when (id) {
            "prev" -> player.playPrevious()
            "next" -> player.playNext()
            "like", "dislike" -> rate(current, if (id == "like") YouTubeAccountActions.Rating.LIKE
                else YouTubeAccountActions.Rating.DISLIKE)
            "comments" -> {
                commentsOpen = !commentsOpen
                if (commentsOpen && comments.isEmpty()) loadComments(current)
                changed()
            }
            "close" -> {
                commentsOpen = false
                changed()
            }
            "more" -> {
                val per = 6
                commentsPage = if ((commentsPage + 1) * per >= comments.size) 0 else commentsPage + 1
                changed()
            }
        }
    }

    private fun rate(current: StreamInfo, wanted: YouTubeAccountActions.Rating) {
        val videoId = PipeVrPlayback.videoIdOf(current.url) ?: return
        if (!YouTubePlaylists.isAvailable(current.serviceId)) return
        val new = if (rating == wanted) YouTubeAccountActions.Rating.NONE else wanted
        val before = rating
        rating = new
        changed()
        Single.fromCallable { YouTubeAccountActions.rate(videoId, new) }
            .subscribeOn(Schedulers.io())
            .subscribe({ ok ->
                if (!ok) {
                    rating = before
                    changed()
                }
            }, {
                rating = before
                changed()
            })
    }

    private fun loadComments(current: StreamInfo) {
        commentsLoading = true
        changed()
        ExtractorHelper.getCommentsInfo(current.serviceId, current.url, false)
            .subscribeOn(Schedulers.io())
            .subscribe({ ci ->
                if (info?.url == current.url) {
                    comments = ci.relatedItems.orEmpty()
                }
                commentsLoading = false
                changed()
            }, { e ->
                Log.w("PipeVRShorts", "VR comments failed", e)
                commentsLoading = false
                changed()
            })
    }
}
