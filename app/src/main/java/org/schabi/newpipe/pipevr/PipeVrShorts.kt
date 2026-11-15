package org.schabi.newpipe.pipevr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeShortsFeed
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.fragments.list.comments.CommentsFragment
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.NavigationHelper
import java.lang.ref.WeakReference

/**
 * PipeVR: Shorts mode. The Shorts feed (or a channel's Shorts) plays in the normal player with
 * a Shorts layout: each short loops, a side column has like/dislike/comments/previous/next.
 * Shorts opened anywhere else still play as normal videos.
 */
object PipeVrShorts {
    private var shortsQueue = WeakReference<PlayQueue>(null)
    private var feedToken: String? = null
    private var loadingMore = false

    /** Videos of the current Shorts session (the queue is copied on its way to the player). */
    private val shortsUrls = HashSet<String>()

    @JvmStatic
    fun isShortsQueue(queue: PlayQueue?): Boolean {
        if (queue == null) return false
        if (queue === shortsQueue.get()) return true
        val streams = queue.streams
        if (streams.isEmpty() || shortsUrls.isEmpty()) return false
        if (streams.all { it.url in shortsUrls }) {
            shortsQueue = WeakReference(queue)   // the player's copy is the queue from now on
            return true
        }
        return false
    }

    private fun itemFor(videoId: String) = StreamInfoItem(
        ServiceList.YouTube.serviceId, "https://www.youtube.com/shorts/$videoId", "Short",
        StreamType.VIDEO_STREAM
    ).apply { thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hq720.jpg" }

    private fun start(activity: AppCompatActivity, items: List<StreamInfoItem>, index: Int) {
        val queue = SinglePlayQueue(items, index)
        shortsQueue = WeakReference(queue)
        shortsUrls.clear()
        items.forEach { shortsUrls.add(it.url) }
        NavigationHelper.playOnMainPlayer(activity, queue)
    }

    /** "Shorts" in the side menu: play the Shorts feed. */
    @JvmStatic
    fun playFeed(activity: AppCompatActivity) {
        Toast.makeText(activity, R.string.pipevr_loading_shorts, Toast.LENGTH_SHORT).show()
        Single.fromCallable {
            YoutubeShortsFeed.start(
                NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry()
            )
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ page ->
                if (page.videoIds.isEmpty()) {
                    Toast.makeText(activity, R.string.pipevr_playlist_failed, Toast.LENGTH_SHORT)
                        .show()
                    return@subscribe
                }
                feedToken = page.nextToken
                start(activity, page.videoIds.map { itemFor(it) }, 0)
            }, {
                Toast.makeText(activity, R.string.pipevr_playlist_failed, Toast.LENGTH_SHORT)
                    .show()
            })
    }

    /** A short tapped in a channel's Shorts tab: play that tab's shorts from there. */
    @JvmStatic
    fun playList(activity: AppCompatActivity, items: List<StreamInfoItem>, index: Int) {
        feedToken = null
        start(activity, items, index)
    }

    /** Keeps the feed going: loads the next shorts when the end of the queue gets close. */
    @JvmStatic
    fun maybeLoadMore(queue: PlayQueue?) {
        if (!isShortsQueue(queue) || loadingMore) return
        val token = feedToken ?: return
        if (queue!!.index < queue.size() - 3) return
        loadingMore = true
        Single.fromCallable {
            YoutubeShortsFeed.next(
                token, NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry()
            )
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ page ->
                loadingMore = false
                feedToken = page.nextToken
                if (isShortsQueue(queue)) {
                    page.videoIds.forEach { shortsUrls.add(itemFor(it).url) }
                    queue.append(page.videoIds.map {
                        PlayerMediaItem.forStreamInfoItem(itemFor(it))
                    })
                }
            }, { loadingMore = false })
    }

    /** Comments of the current short in a panel. */
    @JvmStatic
    fun showComments(activity: FragmentActivity?, serviceId: Int, url: String, title: String) {
        if (activity == null) return
        CommentsDialog.newInstance(serviceId, url, title)
            .show(activity.supportFragmentManager, "pipevr_shorts_comments")
    }

    class CommentsDialog : DialogFragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            dialog?.setTitle(R.string.comments_tab_description)
            if (savedInstanceState == null) {
                val args = requireArguments()
                childFragmentManager.beginTransaction()
                    .replace(
                        view.id,
                        CommentsFragment.getInstance(
                            args.getInt("service"), args.getString("url"), args.getString("title")
                        )
                    )
                    .commit()
            }
        }

        override fun onStart() {
            super.onStart()
            val metrics = resources.displayMetrics
            dialog?.window?.setLayout(
                (metrics.widthPixels * 0.45).toInt(), (metrics.heightPixels * 0.85).toInt()
            )
        }

        companion object {
            fun newInstance(serviceId: Int, url: String, title: String) = CommentsDialog().apply {
                arguments = Bundle().apply {
                    putInt("service", serviceId)
                    putString("url", url)
                    putString("title", title)
                }
            }
        }
    }
}
