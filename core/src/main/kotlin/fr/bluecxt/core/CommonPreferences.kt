package fr.bluecxt.core

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource

interface CommonPreferences : ConfigurableAnimeSource {

    fun PreferenceScreen.setupCommonPreferences(
        baseUrlDefault: String,
        supportedServers: List<String>,
        defaultServer: String? = null,
    ) {
        val source = this@CommonPreferences as Source

        addBaseUrlPreference(source.preferences, baseUrlDefault, key = PREF_URL_KEY)

        ListPreference(context).apply {
            key = PREF_VOICES_KEY
            title = "Préférence des voix"
            entries = arrayOf("Préférer VOSTFR", "Préférer VF")
            entryValues = arrayOf("VOSTFR", "VF")
            setDefaultValue("VOSTFR")
            summary = "%s"
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(::addPreference)

        if (supportedServers.isNotEmpty()) {
            ListPreference(context).apply {
                key = PREF_SERVER_KEY
                title = "Serveur préféré"
                entries = supportedServers.toTypedArray()
                entryValues = supportedServers.toTypedArray()
                setDefaultValue(defaultServer ?: supportedServers.firstOrNull() ?: "")
                summary = "%s"
                setOnPreferenceChangeListener { _, _ -> true }
            }.also(::addPreference)
        }

        // Option spécifique pour Filemoon si présent dans la liste
        if (supportedServers.any { it.equals("Filemoon", ignoreCase = true) }) {
            SwitchPreferenceCompat(context).apply {
                key = PREF_DISABLE_FILEMOON_KEY
                title = "Désactiver le lecteur Filemoon"
                summary = "Filemoon utilise un système de sécurité (PoW) qui peut ralentir les appareils peu puissants. Désactivez-le si vous rencontrez des lags."
                setDefaultValue(false)
                setOnPreferenceChangeListener { _, _ -> true }
            }.also(::addPreference)
        }
    }

    companion object {
        const val PREF_URL_KEY = "preferred_baseUrl"
        const val PREF_VOICES_KEY = "preferred_voices"
        const val PREF_SERVER_KEY = "preferred_server"
        const val PREF_DISABLE_FILEMOON_KEY = "disable_filemoon"
    }
}
