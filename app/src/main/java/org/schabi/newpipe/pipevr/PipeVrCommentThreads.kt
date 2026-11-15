package org.schabi.newpipe.pipevr

import android.util.TypedValue
import android.view.View
import android.widget.Button
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.info_list.InfoListAdapter
import org.schabi.newpipe.util.ExtractorHelper
import java.util.Collections
import java.util.WeakHashMap

/**
 * PipeVR: nested reply chains inside a comment's replies, like the YouTube app: a reply's own
 * replies open right under it, indented; after three levels "Show more replies" opens the rest
 * at one deepest level.
 */
object PipeVrCommentThreads {
    private const val MAX_INDENT = 3
    private const val MAX_LOADED = 60

    private val levels: MutableMap<CommentsInfoItem, Int> = Collections.synchronizedMap(WeakHashMap())
    /** Open chains: comment -> the replies shown under it. */
    private val openChildren: MutableMap<CommentsInfoItem, List<CommentsInfoItem>> =
        Collections.synchronizedMap(WeakHashMap())

    /** The replies shown under a comment, including those of chains opened inside it. */
    private fun descendants(item: CommentsInfoItem): List<CommentsInfoItem> {
        val children = openChildren[item] ?: return emptyList()
        return children + children.flatMap { descendants(it) }
    }

    @JvmStatic
    fun levelOf(item: CommentsInfoItem): Int = levels[item] ?: 0

    /** Indents a reply by its depth (padding is kept in a tag so it isn't added twice). */
    @JvmStatic
    fun applyIndent(itemView: View, item: CommentsInfoItem) {
        val base = (itemView.getTag(R.id.itemRoot) as? Int) ?: itemView.paddingStart.also {
            itemView.setTag(R.id.itemRoot, it)
        }
        val step = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f,
            itemView.resources.displayMetrics).toInt()
        val indent = minOf(levelOf(item), MAX_INDENT) * step
        itemView.setPaddingRelative(base + indent, itemView.paddingTop, itemView.paddingEnd,
            itemView.paddingBottom)
    }

    /** "N replies" becomes "Show more replies" once the chain is deep. */
    @JvmStatic
    fun labelReplyButton(button: Button, item: CommentsInfoItem) {
        if (levelOf(item) >= MAX_INDENT - 1) {
            button.setText(R.string.pipevr_show_more_replies)
        }
    }

    /**
     * Opens a reply's own replies right under it. Returns false if this item can't be
     * expanded inline (then the normal replies screen opens).
     */
    @JvmStatic
    fun expandInline(
        adapter: InfoListAdapter,
        info: CommentsInfo?,
        serviceId: Int,
        item: CommentsInfoItem,
    ): Disposable? {
        val page: Page = item.replies ?: return null
        if (info == null || !YouTubePlaylists.isAvailable(serviceId)) return null
        openChildren[item]?.let {
            // already open: tapping again collapses the chain (and chains opened inside it)
            val all = descendants(item)
            all.forEach { openChildren.remove(it) }
            openChildren.remove(item)
            adapter.removeInfoItems(all)
            return io.reactivex.rxjava3.disposables.Disposable.empty()
        }
        openChildren[item] = emptyList()
        val childLevel = levelOf(item) + 1
        return Single.fromCallable {
            val children = mutableListOf<CommentsInfoItem>()
            var next: Page? = page
            while (next != null && children.size < MAX_LOADED) {
                val result = ExtractorHelper.getMoreCommentItems(serviceId, info, next).blockingGet()
                children += result.items
                next = result.nextPage
            }
            children
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ loaded ->
                // YouTube sometimes answers a reply's "replies" with that same reply (or ones
                // already on screen): never show a comment twice
                val shown = adapter.itemsList.mapNotNull {
                    (it as? CommentsInfoItem)?.commentId
                }.toHashSet()
                val children = loaded.filter {
                    val id = it.commentId
                    id == null || (id != item.commentId && shown.add(id))
                }
                children.forEach { levels[it] = childLevel }
                openChildren[item] = children
                adapter.insertInfoItemsAfter(item, children)
            }, { error ->
                openChildren.remove(item)
                error.printStackTrace()
            })
    }
}
