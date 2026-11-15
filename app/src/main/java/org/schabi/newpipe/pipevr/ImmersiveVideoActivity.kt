package org.schabi.newpipe.pipevr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.player.helper.PlayerHolder

/**
 * PipeVR: full VR ("immersive") playback of 360°/180° videos on OpenXR headsets.
 *
 * The video keeps playing in the app's normal player (queue, SponsorBlock, history sync all
 * keep working); its output is redirected to a texture that the native OpenXR renderer
 * (pipevr_xr.cpp) draws around the viewer, following the head.
 *
 * Controller: trigger = play/pause, thumbstick left/right = seek 10 s, grip = recenter,
 * thumbstick click = switch the video layout (360/180, mono/3D), B/Y = back to the window.
 */
class ImmersiveVideoActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    @Volatile
    private var frameAvailable = false

    /** Size of the playing video, kept up to date on the main thread for the renderer. */
    @Volatile
    private var videoSize = intArrayOf(0, 0)
    // player state for the controls overlay, read on the main thread
    @Volatile
    private var title = ""
    @Volatile
    private var positionMs = 0L
    @Volatile
    private var durationMs = 0L
    @Volatile
    private var playing = false

    private val videoSizePoller = object : Runnable {
        override fun run() {
            val player = PlayerHolder.getInstance().player
            player?.videoSize?.let {
                if (it[0] > 0 && it[1] > 0) videoSize = it
            }
            panels.poll()
            if (player != null) {
                guardVideo(player)
                title = player.currentStreamInfo.map { it.name ?: "" }.orElse("")
                positionMs = player.currentPosition
                durationMs = player.currentStreamInfo.map { it.duration * 1000 }.orElse(0L)
                playing = player.isPlaying
            }
            main.postDelayed(this, 300)
        }
    }

    /** Shorts panels pressed with the controller pointers. */
    private val panels by lazy { VrShortsPanels(this) }

    @Suppress("unused")
    private fun getPanelsVersion(): Int = panels.version()

    @Suppress("unused")
    private fun renderShortsPanel(): Bitmap? = panels.renderShorts()

    @Suppress("unused")
    private fun renderCommentsPanel(): Bitmap? = panels.renderComments()

    @Suppress("unused")
    private fun onPanelClick(panel: Int, u: Float, v: Float) = panels.onClick(panel, u, v)

    @Volatile private var lastFrameMs = 0L
    private var surfaceSetAt = 0L
    private var reclaims = 0

    /**
     * Keeps the video in VR: when the app's normal window is put away it sometimes hands the
     * player another surface or switches it to audio-only, which left VR black. Take the video
     * back whenever frames stop arriving while it plays, and twice early on (paused videos).
     */
    private fun guardVideo(player: org.schabi.newpipe.player.Player) {
        val s = surface ?: return
        if (surfaceSetAt == 0L) return
        val now = android.os.SystemClock.elapsedRealtime()
        val sinceStart = now - surfaceSetAt
        val early = (reclaims == 0 && sinceStart > 400) || (reclaims == 1 && sinceStart > 1600)
        val stalled = player.isPlaying && now - lastFrameMs > 900 && sinceStart > 900
        if (early || stalled) {
            reclaims++
            if (stalled) Log.w(TAG, "no video frames for ${now - lastFrameMs} ms: re-attaching")
            player.pipeVrEnsureVideo()
            player.surfaceController.setExternalSurface(s)
            lastFrameMs = now
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        org.schabi.newpipe.pipevr.captions.PipeVrCaptions.vrActive = true
        if (PlayerHolder.getInstance().player == null) {
            finish()
            return
        }
        try {
            System.loadLibrary("pipevr_xr")
        } catch (e: Throwable) {
            Log.e(TAG, "Could not load the VR renderer", e)
            Toast.makeText(this, R.string.pipevr_vr_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        main.postDelayed(videoSizePoller, 0)
        nativeStart(intent.getIntExtra(EXTRA_LAYOUT, LAYOUT_MONO_360))
        Toast.makeText(this, R.string.pipevr_vr_controls, Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        super.onStop()
        // leaving VR (headset menu, another app): go back to the window
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        org.schabi.newpipe.pipevr.captions.PipeVrCaptions.vrActive = false
        main.removeCallbacks(videoSizePoller)
        try {
            nativeStop()
        } catch (ignored: Throwable) {
            // library not loaded
        }
        PlayerHolder.getInstance().player?.surfaceController?.setExternalSurface(null)
        // the headset closes the app's window when VR starts: bring it back
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
        surface?.release()
        surfaceTexture?.release()
        surface = null
        surfaceTexture = null
    }

    // ---- called by the native renderer (render thread) ----

    @Suppress("unused")
    private fun onCreateVideoTexture(textureId: Int) {
        val texture = SurfaceTexture(textureId)
        texture.setOnFrameAvailableListener({
            frameAvailable = true
            lastFrameMs = android.os.SystemClock.elapsedRealtime()
        }, main)
        val videoSurface = Surface(texture)
        surfaceTexture = texture
        surface = videoSurface
        main.post {
            PlayerHolder.getInstance().player?.surfaceController
                ?.setExternalSurface(videoSurface)
            surfaceSetAt = android.os.SystemClock.elapsedRealtime()
            lastFrameMs = surfaceSetAt
        }
    }

    /** Quest path: the headset gives a surface it maps around the viewer itself. */
    @Suppress("unused")
    private fun onCreateVideoSurface(videoSurface: Surface) {
        main.post {
            PlayerHolder.getInstance().player?.surfaceController
                ?.setExternalSurface(videoSurface)
        }
    }

    @Suppress("unused")
    private fun getVideoSize(): IntArray = videoSize

    /** The controls overlay shown inside VR: title, progress, view name and button hints. */
    @Suppress("unused")
    private fun renderOverlay(layout: Int): Bitmap {
        val width = 1100
        val height = 400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 18, 18, 22) }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 48f, 48f, panel)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            textSize = 30f
        }
        val accent = Color.parseColor("#4FC3F7")

        // title (shortened to fit)
        var shown = title
        val maxTitle = width - 96f
        if (titlePaint.measureText(shown) > maxTitle) {
            while (shown.isNotEmpty() && titlePaint.measureText("$shown…") > maxTitle) {
                shown = shown.dropLast(1)
            }
            shown = "$shown…"
        }
        canvas.drawText(shown, 48f, 76f, titlePaint)

        // play state + progress bar + times
        val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        if (!playing) {
            canvas.drawPath(android.graphics.Path().apply {
                moveTo(52f, 114f); lineTo(88f, 136f); lineTo(52f, 158f); close()
            }, icon)
        } else {
            canvas.drawRoundRect(RectF(52f, 114f, 64f, 158f), 3f, 3f, icon)
            canvas.drawRoundRect(RectF(76f, 114f, 88f, 158f), 3f, 3f, icon)
        }
        val barLeft = 120f
        val barRight = width - 280f
        val barY = 136f
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }
        canvas.drawRoundRect(RectF(barLeft, barY - 6, barRight, barY + 6), 6f, 6f, track)
        if (durationMs > 0) {
            val f = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            canvas.drawRoundRect(
                RectF(barLeft, barY - 6, barLeft + (barRight - barLeft) * f, barY + 6), 6f, 6f, fill
            )
            canvas.drawCircle(barLeft + (barRight - barLeft) * f, barY, 13f, fill)
        }
        canvas.drawText("${time(positionMs)} / ${time(durationMs)}", barRight + 28f, 147f, small)

        // view name + button hints
        val viewPaint = Paint(small).apply { color = accent; typeface = Typeface.DEFAULT_BOLD }
        val n = noteText(layout)
        canvas.drawText(
            if (n != null && System.currentTimeMillis() < noteUntil) "${layoutName(this, layout)} — $n"
            else layoutName(this, layout), 48f, 212f, viewPaint
        )
        // button hints, split over two lines
        val hints = getString(R.string.pipevr_vr_hints).split(" · ")
        // pack the hints into rows that fit the panel
        val rows = ArrayList<String>()
        var row = ""
        for (h in hints) {
            val next = if (row.isEmpty()) h else "$row · $h"
            if (row.isNotEmpty() && small.measureText(next) > width - 96f) {
                rows.add(row); row = h
            } else row = next
        }
        if (row.isNotEmpty()) rows.add(row)
        small.textSize = if (rows.size > 4) 24f else 28f
        rows.take(5).forEachIndexed { i, r -> canvas.drawText(r, 48f, 248f + i * (if (rows.size > 4) 30f else 36f), small) }
        return bitmap
    }

    private var chatAnchorSize = -1

    /** Captions for VR: a video-shaped transparent picture, redrawn only when it changes. */
    @Suppress("unused")
    private fun getCaptionVersion(): Int = org.schabi.newpipe.pipevr.captions.PipeVrCaptions.version()

    @Suppress("unused")
    private fun renderCaptions(): Bitmap? = org.schabi.newpipe.pipevr.captions.PipeVrCaptions.snapshot()
    @Volatile private var noteArg = 0
    @Volatile private var noteUntil = 0L

    private fun noteText(layout: Int): String? {
        val name = when (noteArg) {
            -1 -> return getString(R.string.pipevr_vr_no_passthrough)
            1 -> getString(R.string.pipevr_env_theater)
            2 -> getString(R.string.pipevr_env_space)
            4 -> getString(R.string.pipevr_env_beach)
            5 -> getString(R.string.pipevr_env_aurora)
            6 -> getString(R.string.pipevr_env_city)
            7 -> getString(R.string.pipevr_env_passthrough)
            8 -> getString(R.string.pipevr_env_underwater)
            else -> getString(R.string.pipevr_env_dark)
        }
        return if (layout < LAYOUT_SCREEN_2D) getString(R.string.pipevr_env_screen_only, name)
        else name
    }

    /** Environment of the screen views (ENV_* in pipevr_xr.cpp; 7 is passthrough). */
    @Suppress("unused")
    private fun getEnvironment(): Int {
        val p = getSharedPreferences(CHAT_PREFS, MODE_PRIVATE)
        // "environment" (v1) used 3 for passthrough; it is 7 now
        if (p.contains("environment2")) return p.getInt("environment2", 0)
        val old = if (p.contains("environment")) p.getInt("environment", 0)
        else if (p.getBoolean("passthrough", false)) 3 else 0
        return if (old == 3) 7 else old
    }

    @Suppress("unused")
    private fun onEnvironment(value: Int) {
        getSharedPreferences(CHAT_PREFS, MODE_PRIVATE).edit().putInt("environment2", value).apply()
    }

    /** The live chat panel shown next to the screen in VR (null when there is no chat). */
    @Suppress("unused")
    private fun renderChat(scroll: Int): Bitmap? {
        val all = org.schabi.newpipe.player.chat.ChatStore.items.value.orEmpty()
        if (all.isEmpty()) return null
        // scrolled up by [scroll] messages (left stick); clamp at the oldest kept message
        // while scrolled up, new messages don't move what you're reading
        if (scroll == 0) chatAnchorSize = -1 else if (chatAnchorSize < 0) chatAnchorSize = all.size
        val grown = if (chatAnchorSize < 0) 0 else (all.size - chatAnchorSize).coerceAtLeast(0)
        val skip = (scroll + grown).coerceIn(0, (all.size - 1).coerceAtLeast(0))
        val items = all.subList(0, all.size - skip).takeLast(14)
        val width = 720
        val height = 1000
        val pad = 32f
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 40f, 40f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 18, 18, 22) })
        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(getString(R.string.pipevr_live_chat), pad, 64f, header)
        if (skip > 0) {
            val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4FC3F7")
                textSize = 26f
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(getString(R.string.pipevr_vr_chat_scrolled, skip), width - pad, 62f, hint)
        }
        val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
        }
        val nameColor = Color.parseColor("#BF94FF")
        // newest at the bottom: lay the messages out from the bottom up
        var bottom = height - pad
        for (item in items.asReversed()) {
            val author = try { item.authorName } catch (e: Exception) { null } ?: ""
            val message = try { item.commentText } catch (e: Exception) { null } ?: ""
            val spannable = android.text.SpannableStringBuilder()
            if (author.isNotEmpty()) {
                spannable.append(author)
                spannable.setSpan(android.text.style.ForegroundColorSpan(nameColor), 0,
                    author.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, author.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.append("  ")
            }
            spannable.append(message)
            val layout = android.text.StaticLayout.Builder
                .obtain(spannable, 0, spannable.length, text, (width - 2 * pad).toInt())
                .setMaxLines(4)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            val top = bottom - layout.height
            if (top < 100f) break
            canvas.save()
            canvas.translate(pad, top)
            layout.draw(canvas)
            canvas.restore()
            bottom = top - 14f
        }
        return bitmap
    }

    /** Where the chat panel was left: x, y, z (metres from the viewer), scale, visible (1/0). */
    @Suppress("unused")
    private fun getChatLayout(): FloatArray {
        val p = getSharedPreferences(CHAT_PREFS, MODE_PRIVATE)
        if (!p.contains("scale")) return floatArrayOf(0f, 0f, 0f, 0f, 1f)
        return floatArrayOf(
            p.getFloat("x", 2.75f), p.getFloat("y", 0.05f), p.getFloat("z", -2.4f),
            p.getFloat("scale", 1f), if (p.getBoolean("visible", true)) 1f else 0f
        )
    }

    @Suppress("unused")
    private fun onChatLayout(x: Float, y: Float, z: Float, scale: Float, visible: Boolean) {
        getSharedPreferences(CHAT_PREFS, MODE_PRIVATE).edit()
            .putFloat("x", x).putFloat("y", y).putFloat("z", z)
            .putFloat("scale", scale).putBoolean("visible", visible).apply()
    }

    private fun time(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    @Suppress("unused")
    private fun onUpdateVideoTexture(matrix: FloatArray): Boolean {
        val texture = surfaceTexture ?: return false
        if (!frameAvailable) return false
        frameAvailable = false
        return try {
            texture.updateTexImage()
            texture.getTransformMatrix(matrix)
            true
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("unused")
    private fun onXrAction(action: Int, arg: Int) {
        if (action == ACTION_NOTE) {
            // shown in the controls panel (toasts aren't visible inside VR)
            noteArg = arg
            noteUntil = System.currentTimeMillis() + 4000
            return
        }
        main.post {
            val player = PlayerHolder.getInstance().player
            when (action) {
                ACTION_PLAY_PAUSE -> player?.playPause()
                ACTION_SEEK -> player?.let {
                    it.seekTo((it.currentPosition + arg * 1000L).coerceAtLeast(0))
                }
                ACTION_EXIT -> finish()
                ACTION_LAYOUT -> Log.i(TAG, "video layout $arg")
                ACTION_SKIP -> if (arg > 0) player?.playNext() else player?.playPrevious()
            }
        }
    }

    @Suppress("unused")
    private fun onXrFinished(error: String) {
        main.post {
            if (error.isNotEmpty()) {
                Log.e(TAG, "VR mode ended: $error")
                Toast.makeText(this, getString(R.string.pipevr_vr_error, error), Toast.LENGTH_LONG)
                    .show()
            }
            if (!isFinishing) finish()
        }
    }

    private external fun nativeStart(layout: Int)
    private external fun nativeStop()

    companion object {
        private const val TAG = "PipeVRImmersive"
        private const val EXTRA_LAYOUT = "layout"
        private const val CHAT_PREFS = "pipevr_vr_chat"

        // keep in sync with pipevr_xr.cpp
        const val LAYOUT_MONO_360 = 0
        const val LAYOUT_TOP_BOTTOM_360 = 1
        const val LAYOUT_SIDE_BY_SIDE_360 = 2
        const val LAYOUT_SIDE_BY_SIDE_180 = 3
        const val LAYOUT_TOP_BOTTOM_180 = 4
        const val LAYOUT_MONO_180 = 5
        const val LAYOUT_SCREEN_2D = 6
        const val LAYOUT_SCREEN_SIDE_BY_SIDE = 7
        const val LAYOUT_SCREEN_TOP_BOTTOM = 8

        private val THREE_D = Regex("""\b3d\b""")
        private val SBS = Regex("""\bsbs\b|side[\s-]*by[\s-]*side|\bhsbs\b|\bh-sbs\b""")
        private val TB = Regex("""\btb\b|\bhou\b|top[\s-]*(and[\s-]*)?bottom|over[\s-]*under""")

        /** A flat (not 360°) 3D movie: side-by-side or over-under. */
        @JvmStatic
        fun isFlat3d(title: String?): Boolean {
            val t = title?.lowercase() ?: return false
            return THREE_D.containsMatchIn(t) && (SBS.containsMatchIn(t) || TB.containsMatchIn(t))
        }

        private const val ACTION_PLAY_PAUSE = 1
        private const val ACTION_SEEK = 2
        private const val ACTION_EXIT = 3
        private const val ACTION_LAYOUT = 4
        private const val ACTION_SKIP = 5
        private const val ACTION_NOTE = 9

        /**
         * Guess the layout: flat videos go on the theater screen (3D if the title says so),
         * VR180 is usually side-by-side 3D, "3D" 360 is top-bottom.
         */
        @JvmStatic
        fun layoutFor(title: String?, spherical: Boolean): Int {
            val t = title?.lowercase() ?: ""
            if (!spherical) {
                return when {
                    THREE_D.containsMatchIn(t) && TB.containsMatchIn(t) -> LAYOUT_SCREEN_TOP_BOTTOM
                    THREE_D.containsMatchIn(t) && SBS.containsMatchIn(t) ->
                        LAYOUT_SCREEN_SIDE_BY_SIDE
                    else -> LAYOUT_SCREEN_2D
                }
            }
            return when {
                Regex("""\bvr\s*180\b|\b180\s*(°|vr|degree)""").containsMatchIn(t) ->
                    LAYOUT_SIDE_BY_SIDE_180
                Regex("""\b3d\b""").containsMatchIn(t) -> LAYOUT_TOP_BOTTOM_360
                else -> LAYOUT_MONO_360
            }
        }

        /** Views offered when entering VR, in a friendly order. */
        private val CHOICES = intArrayOf(
            LAYOUT_SCREEN_2D, LAYOUT_SCREEN_SIDE_BY_SIDE, LAYOUT_SCREEN_TOP_BOTTOM,
            LAYOUT_MONO_360, LAYOUT_TOP_BOTTOM_360, LAYOUT_SIDE_BY_SIDE_360,
            LAYOUT_SIDE_BY_SIDE_180, LAYOUT_TOP_BOTTOM_180, LAYOUT_MONO_180
        )

        @JvmStatic
        fun layoutName(context: Context, layout: Int): String = context.getString(
            when (layout) {
                LAYOUT_MONO_360 -> R.string.pipevr_layout_360
                LAYOUT_TOP_BOTTOM_360 -> R.string.pipevr_layout_360_tb
                LAYOUT_SIDE_BY_SIDE_360 -> R.string.pipevr_layout_360_sbs
                LAYOUT_SIDE_BY_SIDE_180 -> R.string.pipevr_layout_180_sbs
                LAYOUT_TOP_BOTTOM_180 -> R.string.pipevr_layout_180_tb
                LAYOUT_MONO_180 -> R.string.pipevr_layout_180
                LAYOUT_SCREEN_SIDE_BY_SIDE -> R.string.pipevr_layout_screen_sbs
                LAYOUT_SCREEN_TOP_BOTTOM -> R.string.pipevr_layout_screen_tb
                else -> R.string.pipevr_layout_screen
            }
        )

        private fun launch(context: Context, layout: Int) {
            context.startActivity(
                Intent(context, ImmersiveVideoActivity::class.java)
                    .putExtra(EXTRA_LAYOUT, layout)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        /**
         * Asks what kind of video this is (the best guess is preselected), then enters VR.
         * Without an activity to show the question, enters VR with the guess.
         */
        @JvmStatic
        fun chooseAndStart(context: Context, dialogContext: Context?, title: String?,
                           spherical: Boolean) {
            val guess = layoutFor(title, spherical)
            if (dialogContext == null) {
                launch(context, guess)
                return
            }
            var selected = CHOICES.indexOf(guess).coerceAtLeast(0)
            android.app.AlertDialog.Builder(dialogContext)
                .setTitle(R.string.pipevr_choose_view)
                .setSingleChoiceItems(
                    CHOICES.map { layoutName(dialogContext, it) }.toTypedArray(), selected
                ) { _, which -> selected = which }
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.pipevr_enter_vr) { _, _ ->
                    launch(context, CHOICES[selected])
                }
                .show()
        }
    }
}
