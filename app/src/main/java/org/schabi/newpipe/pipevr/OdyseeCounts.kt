package org.schabi.newpipe.pipevr

import android.content.Context
import androidx.preference.PreferenceManager
import org.schabi.newpipe.extractor.services.odysee.OdyseeService

/**
 * PipeVR: Odysee only gives view/follower counts to a (anonymous) Odysee user, like the
 * website makes for every visitor. Keep that user's token so one install is one visitor.
 */
object OdyseeCounts {
    private const val PREF = "pipevr_odysee_anon_token"

    @JvmStatic
    fun init(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        OdyseeService.setTokenStore(object : OdyseeService.TokenStore {
            override fun load(): String? = prefs.getString(PREF, null)
            override fun save(token: String?) {
                prefs.edit().putString(PREF, token).apply()
            }
        })
    }
}
