package org.schabi.newpipe.pipevr.captions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.text.Cue
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.helper.PlayerHelper

/**
 * PipeVR captions:
 * - YouTube tracks with their own styling (srv3 with pens/positions, e.g. made with
 *   YTSubConverter) are drawn by [Srv3] on top of the video, in the normal player and in VR.
 *   The picture is only redrawn when the set of visible caption events changes, and that
 *   drawing happens on a background thread, so animated captions don't stutter the player.
 * - Plain captions stay with the player's usual subtitle view; in VR they are drawn from the
 *   player's cues with the YouTube default look.
 */
object PipeVrCaptions {
    private const val TAG = "PipeVRCaptions"
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("pipevr-captions").apply { start() }
    private val bg = Handler(worker.looper)

    private var player: Player? = null
    private var overlay: OverlayView? = null
    private var loadingUrl: String? = null
    private var track: Srv3.Track? = null          // the styled track in use, or null

    // the picture: two buffers, the worker draws into [back], then they swap
    private val lock = Any()
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    @Volatile private var version = 0
    private var shownKey = ""                       // which events [front] shows
    private var rendering = false
    private var pendingKey: String? = null

    /** Set by the VR view while it is open: plain captions are then drawn for it too. */
    @Volatile var vrActive = false
        set(value) {
            field = value
            if (!value && track == null) clearPicture()
        }

    private val active = ArrayList<Int>()
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val t = track ?: return
            val p = player ?: return
            try {
                t.activeAt(p.currentPosition, active)
                val key = active.joinToString(",")
                if (key != shownKey && key != pendingKey) request(t, ArrayList(active), key, p)
            } catch (e: Exception) {
                Log.w(TAG, "tick failed", e)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ------------------------------------------------------------------ hooks from the player

    /** The player's tracks changed: a caption track may have been switched on or off. */
    @JvmStatic
    fun onTracksChanged(p: Player, tracks: Tracks) {
        player = p
        val label = selectedCaptionLabel(tracks)
        val stream = label?.let { findStream(p, it) }
        val url = stream?.let { srv3Url(it) }
        if (url == null) {
            stop(p)
            return
        }
        if (url == loadingUrl) return
        loadingUrl = url
        stop(p)
        loadingUrl = url
        Thread({
            try {
                val xml = DownloaderImpl.getInstance().get(url).responseBody()
                val parsed = Srv3.parse(xml)
                main.post {
                    if (loadingUrl != url) return@post
                    if (parsed.styled && parsed.events.isNotEmpty()) start(p, parsed)
                    else Log.i(TAG, "plain captions: the normal subtitle view keeps them")
                }
            } catch (e: Exception) {
                Log.w(TAG, "srv3 load failed", e)
            }
        }, "pipevr-srv3").start()
    }

    /** The player's plain captions (used for VR when no styled track is active). */
    @JvmStatic
    fun onCues(p: Player, cues: List<Cue>) {
        player = p
        if (track != null || !vrActive) return
        val text = cues.mapNotNull { it.text?.toString() }.joinToString("\n").trim()
        val key = "cue:$text"
        if (key == shownKey || key == pendingKey) return
        if (text.isEmpty()) {
            clearPicture(); return
        }
        val plain = Srv3.Track(listOf(Srv3.Event(0, Long.MAX_VALUE, Srv3.WinPos(),
            Srv3.WinStyle(), listOf(Srv3.Segment(text, Srv3.Pen())))), false)
        request(plain, listOf(0), key, p)
    }

    // ------------------------------------------------------------------ VR access

    /** Changes whenever the caption picture changes. */
    @JvmStatic
    fun version(): Int = version

    /** A copy of the current caption picture (video-shaped, transparent), or null for none. */
    @JvmStatic
    fun snapshot(): Bitmap? = synchronized(lock) {
        val f = front ?: return null
        if (shownKey.isEmpty() || shownKey == "cue:") null else f.copy(Bitmap.Config.ARGB_8888, false)
    }

    // ------------------------------------------------------------------ internals

    private fun selectedCaptionLabel(tracks: Tracks): String? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT || !group.isSelected) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) return group.getTrackFormat(i).language
            }
        }
        return null
    }

    private fun findStream(p: Player, label: String): SubtitlesStream? {
        val info = p.currentStreamInfo.orElse(null) ?: return null
        if (info.serviceId != ServiceList.YouTube.serviceId) return null
        // ExoPlayer normalises the label it keeps (lower case, "_" -> "-")
        val norm = { x: String -> x.lowercase().replace('_', '-') }
        return info.subtitles?.firstOrNull {
            it.isUrl && norm(PlayerHelper.captionLanguageOf(p.context, it)) == norm(label)
        }
    }

    private fun srv3Url(s: SubtitlesStream): String? {
        if (s.isAutoGenerated) return null      // automatic captions are never styled
        val url = s.content ?: return null
        if (!url.startsWith("http")) return null
        return if (url.contains("fmt=")) url.replace(Regex("fmt=[^&]*"), "fmt=srv3")
        else "$url&fmt=srv3"
    }

    private fun start(p: Player, t: Srv3.Track) {
        track = t
        shownKey = ""
        pendingKey = null
        ensureOverlay(p)
        p.binding.subtitleView.visibility = View.INVISIBLE
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
        Log.i(TAG, "styled captions: ${t.events.size} events")
    }

    private fun stop(p: Player) {
        loadingUrl = null
        if (track != null) {
            track = null
            Choreographer.getInstance().removeFrameCallback(frame)
            try {
                p.binding.subtitleView.visibility = View.VISIBLE
            } catch (e: Exception) { /* binding gone */ }
            clearPicture()
        }
    }

    private fun clearPicture() {
        synchronized(lock) {
            front?.eraseColor(0)
            shownKey = ""
        }
        version++
        overlay?.invalidate()
    }

    private fun request(t: Srv3.Track, events: List<Int>, key: String, p: Player) {
        if (rendering) {
            // draw the newest state as soon as the current drawing is done
            pendingKey = key
            pendingJob = Triple(t, events, key)
            return
        }
        rendering = true
        pendingKey = key
        val size = p.videoSize
        val vw = if (size != null && size.size >= 2 && size[0] > 0) size[0] else 16
        val vh = if (size != null && size.size >= 2 && size[1] > 0) size[1] else 9
        val w = 1280
        val h = (w.toLong() * vh / vw).toInt().coerceIn(360, 2276)
        bg.post {
            val target = synchronized(lock) {
                back?.takeIf { it.width == w && it.height == h }
                    ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { back = it }
            }
            try {
                Srv3.render(t, events, target)
            } catch (e: Exception) {
                Log.w(TAG, "render failed", e)
            }
            main.post {
                synchronized(lock) {
                    val old = front
                    front = target
                    back = old
                    shownKey = key
                }
                version++
                rendering = false
                if (pendingKey == key) pendingKey = null
                overlay?.invalidate()
                val next = pendingJob
                pendingJob = null
                if (next != null && next.third != shownKey) request(next.first, next.second, next.third, p)
            }
        }
    }

    private var pendingJob: Triple<Srv3.Track, List<Int>, String>? = null

    private fun ensureOverlay(p: Player) {
        val root = p.binding.root as? ViewGroup ?: return
        val existing = overlay
        if (existing != null && existing.parent === root) return
        (existing?.parent as? ViewGroup)?.removeView(existing)
        val view = OverlayView(root.context, p)
        val index = root.indexOfChild(p.binding.subtitleView)
        root.addView(view, if (index >= 0) index + 1 else root.childCount,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = view
    }

    /** Draws the caption picture exactly over the video picture. */
    private class OverlayView(context: Context, private val p: Player) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()

        init {
            isClickable = false
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            if (track == null) return
            val surface = p.binding.surfaceView
            rect.set(surface.left.toFloat(), surface.top.toFloat(),
                surface.right.toFloat(), surface.bottom.toFloat())
            if (rect.isEmpty) rect.set(0f, 0f, width.toFloat(), height.toFloat())
            synchronized(lock) {
                val f = front ?: return
                if (shownKey.isEmpty()) return
                canvas.drawBitmap(f, null, rect, paint)
            }
        }
    }
}
