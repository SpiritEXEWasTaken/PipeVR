package org.schabi.newpipe.pipevr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.fragments.detail.ChatListView
import org.schabi.newpipe.player.chat.ChatStore
import org.schabi.newpipe.util.ThemeHelper

/**
 * PipeVR: the live chat in its own window. On Quest/Pico a new task opens as a separate panel
 * that can be placed next to the video panel.
 */
class ChatPopoutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.setTheme(this)
        super.onCreate(savedInstanceState)
        title = getString(R.string.pipevr_live_chat)
        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        val list = RecyclerView(this).apply {
            clipToPadding = false
            setPadding(0, (4 * d).toInt(), 0, (56 * d).toInt())
        }
        root.addView(list, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val empty = TextView(this).apply {
            setText(R.string.chat_empty)
            gravity = Gravity.CENTER
        }
        root.addView(empty, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
        val jump = Button(this, null, android.R.attr.buttonBarButtonStyle).apply {
            setText(R.string.chat_jump_to_latest)
            visibility = android.view.View.GONE
        }
        root.addView(jump, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (64 * d).toInt() })
        setContentView(root)
        current = java.lang.ref.WeakReference(this)
        popped.value = true
        val chat = ChatListView(list, jump, forceLight = false)
        PipeVrChatBox.attach(root, list)
        ChatStore.items.observe(this) { items ->
            empty.visibility = if (items.isEmpty()) android.view.View.VISIBLE
            else android.view.View.GONE
            chat.submit(items)
        }
    }

    override fun onDestroy() {
        if (current?.get() === this) {
            current = null
            popped.value = false
        }
        super.onDestroy()
    }

    companion object {
        /** Whether the chat is open in its own window (the chat tab says so). */
        @JvmStatic
        val popped = androidx.lifecycle.MutableLiveData(false)
        private var current: java.lang.ref.WeakReference<ChatPopoutActivity>? = null

        /** Closes the separate chat window (the chat goes back under the video). */
        @JvmStatic
        fun bringBack() {
            current?.get()?.finish()
        }

        fun open(context: Context) {
            val intent = Intent(context, ChatPopoutActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
