package org.schabi.newpipe.player

import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.Observer
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem
import org.schabi.newpipe.fragments.detail.ChatListView
import org.schabi.newpipe.fragments.detail.PinnedScView
import org.schabi.newpipe.player.chat.ChatStore
import org.schabi.newpipe.player.chat.PinnedSc

/**
 * Chat side panel of the fullscreen (landscape) player, shown when the chat tab of the detail
 * page is not visible. Opened with the former bullet comments switch of the player controls.
 */
class ChatPanelController(private val player: Player) {

    private var chatList: ChatListView? = null
    private var pinnedView: PinnedScView? = null
    /** Whether the panel is currently on screen. */
    private var open = false
    private var observing = false

    /** The persisted choice of the user, which outlives the panel being hidden by the state of the player. */
    private var wanted: Boolean
        get() = player.prefs.getBoolean(PANEL_OPEN_PREF_KEY, false)
        set(value) = player.prefs.edit().putBoolean(PANEL_OPEN_PREF_KEY, value).apply()

    private val itemsObserver = Observer<List<BulletCommentsInfoItem>> { items ->
        chatList?.submit(items)
    }
    private val pinnedObserver = Observer<List<PinnedSc>> { pinnedView?.submit(it) }
    private val availableObserver = Observer<Boolean?> { update() }

    /** Whether the user wants the panel open (it is only shown while [canShow]). */
    val isWanted: Boolean
        get() = wanted

    /** Whether the switch is usable: chat mode and the stream has a chat, in any orientation. */
    fun canSwitch(): Boolean =
        player.bulletCommentsController.isChatMode &&
            player.videoPlayerSelected() &&
            ChatStore.url != null &&
            ChatStore.available.value != false

    /** Whether the panel itself may be on screen; independent of the switch being usable. */
    private fun canShow(): Boolean =
        canSwitch() &&
            player.isFullscreen &&
            player.context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Visibility of the player's switch button while in chat mode. */
    fun buttonVisibility(): Int = if (canSwitch()) View.VISIBLE else View.GONE

    /** Brings the button and the panel in line with the current state of the player. */
    fun update() {
        val binding = player.binding
        val show = canShow()
        val switchable = canSwitch()
        if (player.bulletCommentsController.isChatMode) {
            binding.switchCommentsVisibility.visibility = buttonVisibility()
            binding.switchCommentsVisibility.setImageDrawable(
                AppCompatResources.getDrawable(
                    player.context,
                    if (wanted) R.drawable.ic_bullet_comment_enabled
                    else R.drawable.ic_bullet_comment_disabled,
                ),
            )
        }
        // Keep observing while the switch is usable, so it appears once the chat becomes available.
        if (switchable) startObserving() else stopObserving()
        if (show) {
            if (wanted != open) {
                setOpen(wanted)
            }
        } else if (open) {
            setOpen(false)
        }
    }

    fun toggle() {
        if (canSwitch()) {
            wanted = !wanted
            update()
        }
    }

    fun release() {
        stopObserving()
        open = false
        player.binding.chatPanel.visibility = View.GONE
    }

    private fun setOpen(value: Boolean) {
        open = value
        val binding = player.binding
        if (value && chatList == null) {
            chatList = ChatListView(binding.chatPanelList, null, forceLight = true)
            val pinnedRoot = binding.chatPanelPinned.root
            pinnedRoot.setBackgroundColor(0xE6000000.toInt())
            pinnedView = PinnedScView(pinnedRoot)
            pinnedView?.submit(ChatStore.pinned.value.orEmpty())
            addHandle()
            // Never pass scrolls on to the AppBarLayout: it would scroll the fullscreen player away
            binding.chatPanelList.isNestedScrollingEnabled = false
            // Keep the touch stream of the list away from the bottom sheet / player gestures
            binding.chatPanelList.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        if (value) {
            // Bound the panel to the screen: an unbounded list would otherwise grow the player
            // layout and push the video out of view.
            val metrics = player.context.resources.displayMetrics
            val rootHeight = binding.root.height
            val height = if (rootHeight > 0) minOf(rootHeight, metrics.heightPixels)
            else metrics.heightPixels
            val saved = player.prefs.getInt(PANEL_WIDTH_PREF_KEY, 300)
            val width = minOf((saved * metrics.density).toInt(), (metrics.widthPixels * 0.6f).toInt())
            binding.chatPanel.layoutParams = binding.chatPanel.layoutParams.apply {
                this.height = height
                this.width = width
            }
            applySide()
        }
        binding.chatPanel.visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            chatList?.submit(ChatStore.items.value.orEmpty())
            chatList?.scrollToEnd()
        }
    }

    /** PipeVR: which side the panel sits on (tap the handle to swap). */
    private var onLeft: Boolean
        get() = player.prefs.getBoolean(PANEL_LEFT_PREF_KEY, false)
        set(value) = player.prefs.edit().putBoolean(PANEL_LEFT_PREF_KEY, value).apply()

    private var handle: View? = null

    private fun applySide() {
        val panel = player.binding.chatPanel
        val lp = panel.layoutParams as? android.widget.RelativeLayout.LayoutParams ?: return
        lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
        lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
        lp.addRule(
            if (onLeft) android.widget.RelativeLayout.ALIGN_PARENT_START
            else android.widget.RelativeLayout.ALIGN_PARENT_END
        )
        panel.layoutParams = lp
        handle?.let { h ->
            val hl = h.layoutParams as android.widget.FrameLayout.LayoutParams
            hl.gravity = (if (onLeft) android.view.Gravity.END else android.view.Gravity.START) or
                android.view.Gravity.CENTER_VERTICAL
            h.layoutParams = hl
        }
    }

    /**
     * PipeVR: a grip on the panel's inner edge. Drag it sideways to resize the panel, tap it to
     * move the panel to the other side of the screen. Both are remembered.
     */
    private fun addHandle() {
        val panel = player.binding.chatPanel
        val d = player.context.resources.displayMetrics.density
        val grip = View(player.context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCCFFFFFF.toInt())
                cornerRadius = 4 * d
            }
            contentDescription = player.context.getString(R.string.pipevr_chat_handle)
        }
        // the touch target is wider than the visible grip
        val target = android.widget.FrameLayout(player.context)
        target.addView(grip, android.widget.FrameLayout.LayoutParams(
            (5 * d).toInt(), (56 * d).toInt(), android.view.Gravity.CENTER))
        panel.addView(target, android.widget.FrameLayout.LayoutParams(
            (28 * d).toInt(), (120 * d).toInt()))
        handle = target
        var downX = 0f
        var startWidth = 0
        var moved = false
        target.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    downX = e.rawX
                    startWidth = panel.width
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (kotlin.math.abs(dx) > 8 * d) moved = true
                    if (moved) {
                        val metrics = player.context.resources.displayMetrics
                        val w = (startWidth + if (onLeft) dx else -dx).toInt()
                            .coerceIn((180 * d).toInt(), (metrics.widthPixels * 0.6f).toInt())
                        panel.layoutParams = panel.layoutParams.apply { width = w }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    if (moved) {
                        player.prefs.edit()
                            .putInt(PANEL_WIDTH_PREF_KEY, (panel.width / d).toInt()).apply()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        onLeft = !onLeft
                        applySide()
                    }
                }
            }
            true
        }
        applySide()
    }

    private fun startObserving() {
        if (!observing) {
            observing = true
            ChatStore.items.observeForever(itemsObserver)
            ChatStore.available.observeForever(availableObserver)
            ChatStore.pinned.observeForever(pinnedObserver)
        }
    }

    private fun stopObserving() {
        if (observing) {
            observing = false
            ChatStore.items.removeObserver(itemsObserver)
            ChatStore.available.removeObserver(availableObserver)
            ChatStore.pinned.removeObserver(pinnedObserver)
        }
    }

    private companion object {
        const val PANEL_OPEN_PREF_KEY = "isChatPanelOpen"
        const val PANEL_WIDTH_PREF_KEY = "pipevr_chat_panel_width_dp"
        const val PANEL_LEFT_PREF_KEY = "pipevr_chat_panel_left"
    }
}
