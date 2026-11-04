package fr.bluecxt.core.source

import androidx.preference.PreferenceScreen
import fr.bluecxt.core.preference.CommonPreferences

abstract class ConfigurableSource :
    Source(),
    CommonPreferences {
    override val baseUrl: String
        get() = preferences.getString(CommonPreferences.PREF_URL_KEY, null)?.ifEmpty { null } ?: defaultBaseUrl

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        this.setupCommonPreferences(screen)
    }
}
