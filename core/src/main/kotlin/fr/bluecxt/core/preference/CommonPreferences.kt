package fr.bluecxt.core.preference

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import fr.bluecxt.core.extractors.router.DEFAULT_SERVER
import fr.bluecxt.core.model.VoiceLanguage
import fr.bluecxt.core.source.Source
import keiyoushi.core.R
import keiyoushi.utils.addEditTextPreference
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

interface CommonPreferences : ConfigurableAnimeSource {

    /**
     * URL de base par défaut de l'extension (valeur d'usine).
     */
    val defaultBaseUrl: String

    /**
     * Instance des SharedPreferences de la source.
     */
    val preferences: SharedPreferences

    /**
     * Liste des serveurs supportés par l'extension.
     */
    val supportedServers: List<String> get() = DEFAULT_SERVER

    /**
     * Serveur préféré par défaut (si non défini, prend le premier de la liste).
     */
    val defaultServer: String? get() = supportedServers.firstOrNull()

    /**
     * Force ou masque l'affichage du menu "Serveur préféré".
     * null (défaut) : Affiche si supportedServers.size > 1
     */
    val forceShowServerPreference: Boolean? get() = null

    /**
     * Langues (voix) supportées par l'extension.
     */
    val supportedVoices: Set<VoiceLanguage> get() = setOf(VoiceLanguage.VOSTFR, VoiceLanguage.VF)

    /**
     * Langue par défaut.
     */
    val defaultVoice: VoiceLanguage get() = VoiceLanguage.VOSTFR

    /**
     * Force ou masque l'affichage du menu "Préférence des voix".
     * null (défaut) : Affiche si supportedVoices.size > 1
     */
    val forceShowVoicesPreference: Boolean? get() = null

    /**
     * Qualités supportées par l'extension.
     */
    val supportedQualities: Array<String> get() = DEFAULT_QUALITIES

    /**
     * Qualité par défaut.
     */
    val defaultQuality: String get() = "Highest"

    /**
     * Force ou masque l'affichage du menu "Qualité préférée".
     * null (défaut) : Affiche si supportedQualities.size > 1
     */
    val forceShowQualityPreference: Boolean? get() = null

    /**
     * le text affiché en dessous de base_url_pref
     */
    val baseUrlSummary: String? get() = null

    /**
     * Implémentation automatique du menu de réglages communs.
     */
    fun setupCommonPreferences(screen: PreferenceScreen) {
        val source = this as Source
        val prefs = source.preferences
        val context = screen.context

        screen.addBaseUrlPreference(source, prefs, defaultBaseUrl, key = PREF_URL_KEY, summary = baseUrlSummary)
        // Gestion des Langues
        val showVoices = forceShowVoicesPreference ?: (supportedVoices.size > 1)
        if (showVoices) {
            ListPreference(context).apply {
                key = PREF_VOICES_KEY
                title = source.getString(R.string.pref_voices_title)
                entries = supportedVoices.map { source.getString(R.string.pref_voices_entry_prefer, it.tag) }.toTypedArray()
                entryValues = supportedVoices.map { it.tag }.toTypedArray()
                setDefaultValue(defaultVoice.tag)
                summary = "%s"
                setOnPreferenceChangeListener { _, _ -> true }
            }.also(screen::addPreference)
        }

        // Gestion de la Qualité
        val qualities = supportedQualities
        val showQuality = forceShowQualityPreference ?: (qualities.size > 1)
        if (showQuality) {
            ListPreference(context).apply {
                key = PREF_QUALITY_KEY
                title = source.getString(R.string.pref_quality_title)
                entries = Array(qualities.size) { i ->
                    val q = qualities[i]
                    if (q.all { it.isDigit() }) "${q}p" else q
                }
                entryValues = qualities
                setDefaultValue(defaultQuality)
                summary = "%s"
                setOnPreferenceChangeListener { _, _ -> true }
            }.also(screen::addPreference)
        }

        // Gestion du Serveur
        val servers = supportedServers.toTypedArray()
        val showServerPref = forceShowServerPreference ?: (servers.size > 1)
        if (showServerPref) {
            ListPreference(context).apply {
                key = PREF_SERVER_KEY
                title = source.getString(R.string.pref_server_title)
                entries = servers
                entryValues = servers
                setDefaultValue(defaultServer ?: "")
                summary = "%s"
                setOnPreferenceChangeListener { _, _ -> true }
            }.also(screen::addPreference)
        }

        if (servers.any { it.equals("Filemoon", ignoreCase = true) }) {
            SwitchPreferenceCompat(context).apply {
                key = PREF_DISABLE_FILEMOON_KEY
                title = source.getString(R.string.pref_disable_filemoon_title)
                summary = source.getString(R.string.pref_disable_filemoon_summary)
                setDefaultValue(false)
                setOnPreferenceChangeListener { _, _ -> true }
            }.also(screen::addPreference)
        }
    }

    /**
     * Adds an EditTextPreference for managing the extension's base URL.
     * Automatically cleans the input (trim and trailing slash removal).
     * If the field is cleared, it resets the preference to the default URL.
     */
    private fun PreferenceScreen.addBaseUrlPreference(
        source: Source,
        preferences: SharedPreferences,
        defaultUrl: String,
        title: String = source.getString(R.string.pref_base_url_title),
        key: String = PREF_URL_KEY,
        summary: String? = null,
        onComplete: (String) -> Unit = {},
    ) {
        val currentUrl = preferences.getString(key, null)?.ifBlank { null } ?: defaultUrl
        val hasExtraSummary = !summary.isNullOrBlank()

        fun formatSummary(url: String): String {
            val baseSummary = source.getString(R.string.pref_base_url_summary, url)
            return if (hasExtraSummary) {
                buildString(baseSummary.length + 1 + summary.length) {
                    append(baseSummary).append('\n').append(summary)
                }
            } else {
                baseSummary
            }
        }

        addEditTextPreference(
            key = key,
            title = title,
            summary = formatSummary(currentUrl),
            default = defaultUrl,
            sanitize = { it.trim() },
            getSummary = { newValue ->
                formatSummary(newValue.ifEmpty { defaultUrl })
            },
            validate = { url ->
                !url.endsWith('/') && url.toHttpUrlOrNull() != null
            },
            validationMessage = { url ->
                when {
                    url.endsWith('/') -> source.getString(R.string.pref_base_url_invalid_end_with_slash)
                    else -> source.getString(R.string.pref_base_url_invalid)
                }
            },
            onChange = { pref, newValue ->
                if (newValue.isEmpty()) {
                    (pref as? EditTextPreference)?.text = defaultUrl
                    preferences.edit().remove(key).apply()
                    pref.summary = formatSummary(defaultUrl)
                    onComplete(defaultUrl)
                    false
                } else {
                    true
                }
            },
            onComplete = onComplete,
        )
    }

    companion object {
        private val DEFAULT_QUALITIES = arrayOf("Highest", "1080", "720", "480")

        const val PREF_URL_KEY = "preferred_baseUrl"
        const val PREF_VOICES_KEY = "preferred_voices"
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_SERVER_KEY = "preferred_server"
        const val PREF_DISABLE_FILEMOON_KEY = "disable_filemoon"
    }
}
