package org.schabi.newpipe.pipevr

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.floatplane.FloatplaneService
import org.schabi.newpipe.extractor.services.kick.KickApi
import org.schabi.newpipe.extractor.services.patreon.PatreonService
import org.schabi.newpipe.extractor.services.twitch.TwitchApi
import org.schabi.newpipe.extractor.services.youtube.YoutubeTvAccountFeeds
import org.schabi.newpipe.fragments.list.kiosk.KioskFragment
import org.schabi.newpipe.local.subscription.SubscriptionFragment
import org.schabi.newpipe.util.ServiceHelper

/**
 * PipeVR: the Subscriptions page (tab and side menu) of the site you are on. It decides what to
 * show every time it is shown, because the app restores old pages when you switch sites:
 * - YouTube: the YouTube account's subscriptions (local list when signed out)
 * - Twitch / Kick: the account's follows; Patreon / Floatplane: the account's feed
 * - other sites (or signed out): only that site's channels you subscribed to in PipeVR
 */
class PipeVrSubscriptionsHost : Fragment() {
    private val containerId = org.schabi.newpipe.R.id.pipevr_subs_container

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = containerId }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!isAdded || view == null) return
        val key = keyFor(requireContext())
        val current = childFragmentManager.findFragmentById(containerId)
        if (current != null && current.tag == key) return
        val fragment = try {
            fragmentFor(requireContext())
        } catch (e: Exception) {
            SubscriptionFragment()
        }
        childFragmentManager.beginTransaction().replace(containerId, fragment, key)
            .commitNowAllowingStateLoss()
    }

    companion object {
        private fun keyFor(context: Context): String {
            val id = ServiceHelper.getSelectedServiceId(context)
            return "pipevr-subs-$id-" + when (id) {
                ServiceList.YouTube.serviceId -> YouTubeAccountManager.isSignedIn()
                ServiceList.Twitch.serviceId -> TwitchApi.isSignedIn()
                ServiceList.Kick.serviceId -> KickApi.isSignedIn()
                ServiceList.Patreon.serviceId -> PatreonService.isSignedIn()
                ServiceList.Floatplane.serviceId -> FloatplaneService.isSignedIn()
                else -> false
            }
        }

        @JvmStatic
        fun fragmentFor(context: Context): Fragment {
            val id = ServiceHelper.getSelectedServiceId(context)
            return when {
                id == ServiceList.YouTube.serviceId && YouTubeAccountManager.isSignedIn() ->
                    KioskFragment.getInstance(id, YoutubeTvAccountFeeds.SUBSCRIPTIONS)
                id == ServiceList.Twitch.serviceId && TwitchApi.isSignedIn() ->
                    KioskFragment.getInstance(id, "Following")
                id == ServiceList.Kick.serviceId && KickApi.isSignedIn() ->
                    KioskFragment.getInstance(id, "Following")
                id == ServiceList.Patreon.serviceId && PatreonService.isSignedIn() ->
                    KioskFragment.getInstance(id, "Home")
                id == ServiceList.Floatplane.serviceId && FloatplaneService.isSignedIn() ->
                    KioskFragment.getInstance(id, "Subscriptions")
                else -> SubscriptionFragment.forService(id)
            }
        }
    }
}
