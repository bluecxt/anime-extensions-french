package fr.bluecxt.core

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource

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
     * true : Toujours afficher
     * false : Toujours masquer
     */
    val forceShowServerPreference: Boolean? get() = null

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

        screen.addBaseUrlPreference(source.preferences, defaultBaseUrl, key = PREF_URL_KEY)

        ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = "Préférence des voix"
            entries = arrayOf("Préférer VOSTFR", "Préférer VF")
            entryValues = arrayOf("VOSTFR", "VF")
            setDefaultValue("VOSTFR")
            summary = "%s"
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)

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
        const val PREF_SERVER_KEY = "preferred_server"
        const val PREF_DISABLE_FILEMOON_KEY = "disable_filemoon"
    }
}
