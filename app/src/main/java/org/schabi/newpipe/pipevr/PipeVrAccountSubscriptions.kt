package org.schabi.newpipe.pipevr

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.twitch.TwitchApi

/**
 * PipeVR: subscribing (YouTube) / following (Twitch) on the signed-in account of the channel's
 * service. All calls except [handles] are blocking.
 */
object PipeVrAccountSubscriptions {
    @JvmStatic
    fun handles(serviceId: Int): Boolean =
        YouTubePlaylists.isAvailable(serviceId) ||
            (serviceId == ServiceList.Twitch.serviceId && TwitchApi.isSignedIn()) ||
            (serviceId == ServiceList.Kick.serviceId &&
                org.schabi.newpipe.extractor.services.kick.KickApi.isSignedIn())

    /** @param channelId YouTube channel id (UC…) or Twitch login */
    @JvmStatic
    fun set(serviceId: Int, channelId: String, subscribe: Boolean): Boolean =
        if (serviceId == ServiceList.Kick.serviceId) {
            org.schabi.newpipe.extractor.services.kick.KickApi.setFollowing(channelId, subscribe)
        } else if (serviceId == ServiceList.Twitch.serviceId) {
            TwitchApi.setFollowing(channelId, subscribe)
        } else {
            YouTubeAccountActions.setSubscribed(channelId, subscribe)
        }

    @JvmStatic
    fun get(serviceId: Int, channelId: String): Boolean? =
        if (serviceId == ServiceList.Kick.serviceId) {
            org.schabi.newpipe.extractor.services.kick.KickApi.isFollowing(channelId)
        } else if (serviceId == ServiceList.Twitch.serviceId) {
            TwitchApi.isFollowing(channelId)
        } else {
            YouTubeAccountActions.isSubscribed(channelId)
        }
}
