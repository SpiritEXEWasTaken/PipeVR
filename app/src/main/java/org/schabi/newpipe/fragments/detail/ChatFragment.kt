package org.schabi.newpipe.fragments.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import org.schabi.newpipe.R
import org.schabi.newpipe.player.chat.ChatStore

/** Chat tab of the video detail page, showing what [ChatStore] provides. */
class ChatFragment : Fragment() {

    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var jump: Button
    private lateinit var chat: ChatListView
    private var appBar: AppBarLayout? = null
    private val offsetListener = AppBarLayout.OnOffsetChangedListener { bar, offset ->
        applyBottomInset(bar.totalScrollRange + offset)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.chat_list)
        empty = view.findViewById(R.id.chat_empty)
        jump = view.findViewById(R.id.chat_jump_to_latest)
        chat = ChatListView(list, jump, forceLight = false)
        // PipeVR: send live chat messages with the signed-in YouTube account
        (view as? android.widget.FrameLayout)?.let {
            org.schabi.newpipe.pipevr.PipeVrChatBox.attach(it, list)
        }
        // PipeVR: open the chat in its own window, to place it next to the video
        (view as? android.widget.FrameLayout)?.let { root ->
            val d = resources.displayMetrics.density
            root.addView(
                Button(requireContext(), null, android.R.attr.buttonBarButtonStyle).apply {
                    setText(R.string.pipevr_chat_popout)
                    textSize = 13f
                    alpha = 0.85f
                    setOnClickListener {
                        org.schabi.newpipe.pipevr.ChatPopoutActivity.open(requireActivity())
                    }
                },
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP or android.view.Gravity.END
                ).apply { topMargin = (40 * d).toInt() }
            )
        }
        // PipeVR: while the chat is in its own window, say so here instead of showing it twice
        (view as? android.widget.FrameLayout)?.let { root ->
            val d = resources.displayMetrics.density
            val note = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding((24 * d).toInt(), 0, (24 * d).toInt(), 0)
                isClickable = true
                setBackgroundColor(resolveBackground())
                visibility = View.GONE
                addView(android.widget.TextView(context).apply {
                    setText(R.string.pipevr_chat_popped_out)
                    textSize = 18f
                    gravity = android.view.Gravity.CENTER
                })
                addView(Button(context, null, android.R.attr.buttonBarButtonStyle).apply {
                    setText(R.string.pipevr_chat_bring_back)
                    setOnClickListener {
                        org.schabi.newpipe.pipevr.ChatPopoutActivity.bringBack()
                    }
                })
            }
            root.addView(note, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            org.schabi.newpipe.pipevr.ChatPopoutActivity.popped.observe(viewLifecycleOwner) {
                note.visibility = if (it == true) View.VISIBLE else View.GONE
            }
        }
        val pinned = PinnedScView(view.findViewById(R.id.chat_pinned_root))
        ChatStore.pinned.observe(viewLifecycleOwner) { pinned.submit(it) }
        ChatStore.items.observe(viewLifecycleOwner) { items ->
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            chat.submit(items)
        }
    }

    private fun resolveBackground(): Int {
        val tv = android.util.TypedValue()
        return if (requireContext().theme.resolveAttribute(android.R.attr.colorBackground, tv, true))
            tv.data else 0xFF000000.toInt()
    }

    override fun onStart() {
        super.onStart()
        // The tab is laid out as if the app bar was fully collapsed, so while it is not the lower
        // part of the list is below the screen. Pad the list by that amount to keep the latest
        // message visible.
        var parent = view?.parent
        while (parent != null && parent !is CoordinatorLayout) {
            parent = parent.parent
        }
        val coordinator = parent as? CoordinatorLayout ?: return
        val bar = (0 until coordinator.childCount)
            .map { coordinator.getChildAt(it) }
            .firstOrNull { it is AppBarLayout } as? AppBarLayout ?: return
        appBar = bar
        bar.addOnOffsetChangedListener(offsetListener)
        bar.post { applyBottomInset(bar.totalScrollRange + bar.top) }
    }

    override fun onStop() {
        appBar?.removeOnOffsetChangedListener(offsetListener)
        appBar = null
        super.onStop()
    }

    private fun applyBottomInset(inset: Int) {
        val bottom = maxOf(0, inset)
        if (list.paddingBottom != bottom) {
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, bottom)
            if (chat.stickToBottom) {
                list.post { chat.scrollToEnd() }
            }
        }
    }
}
