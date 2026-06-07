package fr.bluecxt.core

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import fr.bluecxt.core.addBaseUrlPreference

interface CommonPreferences : ConfigurableAnimeSource {

    /**
     * URL de base par défaut de l'extension (valeur d'usine).
     */
    val defaultBaseUrl: String

    /**
     * Liste des serveurs supportés par l'extension.
     */
    val supportedServers: List<String>

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
    val supportedVoices: Array<String> get() = arrayOf("VOSTFR", "VF")

    /**
     * Langue par défaut.
     */
    val defaultVoice: String get() = "VOSTFR"

    /**
     * Force ou masque l'affichage du menu "Préférence des voix".
     * null (défaut) : Affiche si supportedVoices.size > 1
     */
    val forceShowVoicesPreference: Boolean? get() = null

    /**
     * Qualités supportées par l'extension.
     */
    val supportedQualities: Array<String> get() = arrayOf("Highest", "1080", "720", "480")

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
     * Récupère l'URL de base actuelle (soit celle modifiée par l'utilisateur, soit celle par défaut).
     */
    val currentBaseUrl: String
        get() = (this as Source).preferences.getString(PREF_URL_KEY, defaultBaseUrl) ?: defaultBaseUrl

    /**
     * Implémentation automatique du menu de réglages.
     */
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val source = this as Source
        val prefs = source.preferences

        screen.addBaseUrlPreference(prefs, defaultBaseUrl, key = PREF_URL_KEY)
        // Gestion des Langues
        val showVoices = forceShowVoicesPreference ?: (supportedVoices.size > 1)
        if (showVoices) {
            ListPreference(screen.context).apply {
                key = PREF_VOICES_KEY
                title = "Préférence des voix"
                entries = supportedVoices.map { if (it == "VOSTFR" || it == "VF") "Préférer $it" else it }.toTypedArray()
                entryValues = supportedVoices
                setDefaultValue(defaultVoice)
                summary = "%s"
                setOnPreferenceChangeListener { _, _ -> true }
            }.also(screen::addPreference)
        }

        // Gestion de la Qualité
        val showQuality = forceShowQualityPreference ?: (supportedQualities.size > 1)
        if (showQuality) {
            ListPreference(screen.context).apply {
                key = PREF_QUALITY_KEY
                title = "Qualité préférée"
                entries = supportedQualities.map { q -> if (q.all { it.isDigit() }) "${q}p" else q }.toTypedArray()
                entryValues = supportedQualities
                setDefaultValue(defaultQuality)
                summary = "%s"
                setOnPreferenceChangeListener { _, _ -> true }
            }.also(screen::addPreference)
        }

        // Gestion du Serveur
        val showServerPref = forceShowServerPreference ?: (supportedServers.size > 1)
        if (showServerPref) {
            ListPreference(screen.context).apply {
                key = PREF_SERVER_KEY
                title = "Serveur préféré"
                entries = supportedServers.toTypedArray()
                entryValues = supportedServers.toTypedArray()
                setDefaultValue(defaultServer ?: "")
                summary = "%s"
                setOnPreferenceChangeListener { _, _ -> true }
            }.also(screen::addPreference)
        }

        if (supportedServers.any { it.equals("Filemoon", ignoreCase = true) }) {
            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_DISABLE_FILEMOON_KEY
                title = "Désactiver le lecteur Filemoon"
                summary = "Filemoon utilise un système de sécurité (PoW) qui peut ralentir les appareils peu puissants. Désactivez-le si vous rencontrez des lags."
                setDefaultValue(false)
                setOnPreferenceChangeListener { _, _ -> true }
            }.also(screen::addPreference)
        }
    }

    companion object {
        const val PREF_URL_KEY = "preferred_baseUrl"
        const val PREF_VOICES_KEY = "preferred_voices"
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_SERVER_KEY = "preferred_server"
        const val PREF_DISABLE_FILEMOON_KEY = "disable_filemoon"
    }
}
