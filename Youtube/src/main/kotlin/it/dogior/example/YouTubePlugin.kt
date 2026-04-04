package it.dogior.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import it.dogior.example.settings.SettingsFragment
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

@CloudstreamPlugin
class YouTubePlugin : Plugin() {
    private val sharedPref = activity?.getSharedPreferences("Youtube", Context.MODE_PRIVATE)

    override fun load(context: Context) {
        var language = sharedPref?.getString("language", "mx")
        var country = sharedPref?.getString("country", "MX")

        if (language.isNullOrEmpty()) {language = "mx"}
        if (country.isNullOrEmpty()) {country = "MX"}
        NewPipe.setupLocalization(Localization(language), ContentCountry(country))

        registerMainAPI(YouTubeProvider(language, sharedPref))
        registerMainAPI(YouTubePlaylistsProvider(language))
        registerMainAPI(YouTubeChannelProvider(language))

        openSettings = {context ->
            val activity = context as AppCompatActivity
            val frag = SettingsFragment(this, sharedPref)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}