package org.schabi.newpipe.pipevr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.services.floatplane.FloatplaneService
import org.schabi.newpipe.extractor.services.kick.KickApi
import org.schabi.newpipe.extractor.services.patreon.PatreonService
import org.schabi.newpipe.extractor.services.twitch.TwitchApi
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.fragments.list.BaseListFragment
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PipeVR: one "Following" feed across every platform you are signed in to: YouTube
 * subscriptions, Twitch and Kick follows, Patreon home, Floatplane subscriptions. Live streams
 * first (most viewers on top), then everything else, newest first.
 */
class AllFollowingFragment : BaseListFragment<List<StreamInfoItem>, Void>() {
    private val loads = CompositeDisposable()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_kiosk, container, false)

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        super.onViewCreated(rootView, savedInstanceState)
        setTitle(getString(R.string.pipevr_all_following))
    }

    override fun onResume() {
        super.onResume()
        setTitle(getString(R.string.pipevr_all_following))
    }

    override fun startLoading(forceLoad: Boolean) {
        super.startLoading(forceLoad)
        loads.clear()
        loads.add(
            Single.fromCallable { gather() }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ handleResult(it) }, { showEmptyState() })
        )
    }

    override fun handleResult(result: List<StreamInfoItem>) {
        super.handleResult(result)
        infoListAdapter.setInfoItemList(result)
        if (result.isEmpty()) showEmptyState()
    }

    override fun loadMoreItems() {}

    override fun hasMoreItems(): Boolean = false

    override fun onDestroy() {
        loads.clear()
        super.onDestroy()
    }

    companion object {
        /** (service, kiosk) of the account feeds of the platforms you are signed in to. */
        private fun sources(): List<Pair<StreamingService, String>> = buildList {
            if (YouTubeAccountManager.isSignedIn()) add(ServiceList.YouTube to "Subscriptions")
            if (TwitchApi.isSignedIn()) add(ServiceList.Twitch to "Following")
            if (KickApi.isSignedIn()) add(ServiceList.Kick to "Following")
            if (PatreonService.isSignedIn()) add(ServiceList.Patreon to "Home")
            if (FloatplaneService.isSignedIn()) add(ServiceList.Floatplane to "Subscriptions")
        }

        /** Loads every feed in parallel and merges them. */
        fun gather(): List<StreamInfoItem> {
            val pool = Executors.newFixedThreadPool(5)
            try {
                val futures = sources().map { (service, kiosk) ->
                    pool.submit(Callable {
                        try {
                            val extractor = service.kioskList.getExtractorById(kiosk, null)
                            extractor.fetchPage()
                            @Suppress("UNCHECKED_CAST")
                            (extractor.initialPage.items as List<Any>)
                                .filterIsInstance<StreamInfoItem>()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    })
                }
                val perService = futures.map {
                    try {
                        it.get(30, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                return merge(perService)
            } finally {
                pool.shutdownNow()
            }
        }

        private fun isLive(item: StreamInfoItem) = item.streamType == StreamType.LIVE_STREAM

        /**
         * Live first (most viewers), then dated items newest first; items without a date keep
         * their feed's order and are interleaved between feeds.
         */
        fun merge(perService: List<List<StreamInfoItem>>): List<StreamInfoItem> {
            val all = perService.flatten()
            val live = all.filter { isLive(it) }.sortedByDescending { it.viewCount }
            val dated = all.filter { !isLive(it) && it.uploadDate != null }
                .sortedByDescending { it.uploadDate!!.offsetDateTime() }
            val undated = perService.map { list -> list.filter { !isLive(it) && it.uploadDate == null } }
            val interleaved = mutableListOf<StreamInfoItem>()
            val longest = undated.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) {
                undated.forEach { list -> list.getOrNull(i)?.let { interleaved += it } }
            }
            return live + dated + interleaved
        }
    }
}
