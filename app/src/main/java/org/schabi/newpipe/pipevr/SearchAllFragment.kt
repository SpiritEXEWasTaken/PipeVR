package org.schabi.newpipe.pipevr

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.info_list.InfoListAdapter
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ServiceHelper
import org.schabi.newpipe.util.OnClickGesture
import java.util.concurrent.TimeUnit

/**
 * PipeVR: one search across every platform at once. Each site gets its own row of results (in
 * the usual service order, as soon as it answers) with a "See all" button that opens the normal
 * search for that site.
 */
class SearchAllFragment : Fragment() {
    private val loads = CompositeDisposable()
    private lateinit var input: EditText
    private lateinit var sections: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var pending = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        }
        input = EditText(ctx).apply {
            hint = getString(R.string.pipevr_search_all_hint)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 18f
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER
                ) {
                    search(); true
                } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(ctx).apply {
            text = getString(R.string.search)
            minHeight = (52 * d).toInt()
            setOnClickListener { search() }
        })
        root.addView(bar)
        val statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
        }
        progress = ProgressBar(ctx).apply { visibility = View.GONE }
        statusRow.addView(progress, LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()))
        status = TextView(ctx).apply { setPadding((8 * d).toInt(), 0, 0, 0) }
        statusRow.addView(status)
        root.addView(statusRow)
        sections = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (24 * d).toInt())
        }
        root.addView(
            ScrollView(ctx).apply { addView(sections) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return root
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title =
            getString(R.string.pipevr_search_all)
        if (sections.childCount == 0) {
            input.requestFocus()
            input.post {
                (requireContext().getSystemService(InputMethodManager::class.java))
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun search() {
        val query = input.text.toString().trim()
        if (query.isEmpty()) return
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        loads.clear()
        sections.removeAllViews()
        val services = NewPipe.getServices().filter { searchable(it) }
        // one placeholder per site keeps the rows in a stable order while answers arrive
        val slots = services.map { s ->
            LinearLayout(requireContext()).also {
                it.orientation = LinearLayout.VERTICAL
                sections.addView(it)
            } to s
        }
        pending = slots.size
        progress.visibility = View.VISIBLE
        status.text = getString(R.string.pipevr_search_all_searching, pending)
        var found = 0
        for ((slot, service) in slots) {
            loads.add(
                // a site that can't build its search turns into an error, never a crash
                io.reactivex.rxjava3.core.Single.defer {
                    // each site's default "all" filter, like the normal search screen
                    val filters = try {
                        listOf(service.searchQHFactory.getFilterItem(0))
                    } catch (e: Exception) {
                        emptyList()
                    }
                    ExtractorHelper.searchFor(service.serviceId, query, filters, emptyList())
                }
                    .timeout(20, TimeUnit.SECONDS)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .doFinally {
                        pending--
                        if (pending <= 0) {
                            progress.visibility = View.GONE
                            status.text = if (found == 0) getString(R.string.pipevr_search_all_none)
                            else ""
                        } else {
                            status.text = getString(R.string.pipevr_search_all_searching, pending)
                        }
                    }
                    .subscribe({ info ->
                        val items = info.relatedItems.orEmpty().take(MAX_PER_SITE)
                        if (items.isNotEmpty()) {
                            found++
                            fillSection(slot, service, query, items)
                        }
                    }, { /* a site that can't search right now is left out */ })
            )
        }
    }

    private fun fillSection(
        slot: LinearLayout,
        service: StreamingService,
        query: String,
        items: List<InfoItem>,
    ) {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (14 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        }
        header.addView(ImageView(ctx).apply {
            setImageResource(ServiceHelper.getIcon(service.serviceId))
        }, LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()))
        header.addView(TextView(ctx).apply {
            text = service.serviceInfo.name
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((10 * d).toInt(), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.pipevr_see_all)
            minHeight = (48 * d).toInt()
            setOnClickListener {
                NavigationHelper.openSearchFragment(parentFragmentManager, service.serviceId, query)
            }
        })
        slot.addView(header)

        val adapter = InfoListAdapter(ctx)
        adapter.setItemViewMode(ItemViewMode.GRID)
        adapter.setOnStreamSelectedListener(object : OnClickGesture<StreamInfoItem>() {
            override fun selected(selectedItem: StreamInfoItem) {
                NavigationHelper.openVideoDetailFragment(ctx, parentFragmentManager,
                    selectedItem.serviceId, selectedItem.url, selectedItem.name, null, false)
            }
        })
        adapter.setOnChannelSelectedListener(object : OnClickGesture<ChannelInfoItem>() {
            override fun selected(selectedItem: ChannelInfoItem) {
                NavigationHelper.openChannelFragment(parentFragmentManager,
                    selectedItem.serviceId, selectedItem.url, selectedItem.name)
            }
        })
        adapter.setOnPlaylistSelectedListener(object : OnClickGesture<PlaylistInfoItem>() {
            override fun selected(selectedItem: PlaylistInfoItem) {
                NavigationHelper.openPlaylistFragment(parentFragmentManager,
                    selectedItem.serviceId, selectedItem.url, selectedItem.name)
            }
        })
        adapter.setInfoItemList(items)
        val row = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            isNestedScrollingEnabled = false
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
            clipToPadding = false
        }
        slot.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // grid cards are sized for a 2-column grid: give each a fixed width in the row
        row.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.layoutParams = view.layoutParams.apply { width = (220 * d).toInt() }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    override fun onDestroyView() {
        loads.clear()
        super.onDestroyView()
    }

    companion object {
        private const val MAX_PER_SITE = 12

        /** Sites that need an account to search are only asked when signed in. */
        private fun searchable(s: StreamingService): Boolean = try {
            s.searchQHFactory != null && when (s.serviceInfo.name) {
                "Patreon" -> org.schabi.newpipe.extractor.services.patreon.PatreonService.isSignedIn()
                else -> true
            }
        } catch (e: Exception) {
            false
        }
    }
}
