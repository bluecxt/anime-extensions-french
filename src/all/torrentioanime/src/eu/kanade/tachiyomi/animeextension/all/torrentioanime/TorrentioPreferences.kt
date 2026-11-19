// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.all.torrentioanime

import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import keiyoushi.core.R

internal fun Torrentio.addTorrentioAnimePreferences(screen: PreferenceScreen) {
    addDebridPreferences(screen)
    addProviderPreferences(screen)
    addLanguageAndSortPreferences(screen)
    addTitlePreferences(screen)
    val efficientPref = addVideoPriorityPreferences(screen)
    addCodecPreferences(screen, efficientPref)
}

internal fun Torrentio.addDebridPreferences(screen: PreferenceScreen) {
    val context = screen.context

    // Debrid provider
    ListPreference(context).apply {
        key = PREF_DEBRID_KEY
        title = context.getString(R.string.pref_debrid_title)
        entries = PREF_DEBRID_ENTRIES
        entryValues = PREF_DEBRID_VALUES
        setDefaultValue("none")
        summary = context.getString(R.string.pref_debrid_summary)
    }.also(screen::addPreference)

    // Token
    EditTextPreference(context).apply {
        key = PREF_TOKEN_KEY
        title = context.getString(R.string.pref_token_title)
        setDefaultValue(PREF_TOKEN_DEFAULT)
        summary = PREF_TOKEN_SUMMARY

        setOnPreferenceChangeListener { _, newValue ->
            runCatching {
                val value = (newValue as String).trim().ifBlank { PREF_TOKEN_DEFAULT }
                Toast.makeText(context, context.getString(R.string.pref_restart_message), Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, value).commit()
            }.getOrDefault(false)
        }
    }.also(screen::addPreference)
}

internal fun Torrentio.addProviderPreferences(screen: PreferenceScreen) {
    val context = screen.context

    // Provider
    MultiSelectListPreference(context).apply {
        key = PREF_PROVIDER_KEY
        title = context.getString(R.string.pref_providers_title)
        entries = PREF_PROVIDERS
        entryValues = PREF_PROVIDERS_VALUE
        setDefaultValue(PREF_PROVIDERS_DEFAULT)
    }.also(screen::addPreference)

    // Exclude Qualities
    MultiSelectListPreference(screen.context).apply {
        key = PREF_QUALITY_KEY
        title = "Exclude Qualities/Resolutions"
        entries = PREF_QUALITY
        entryValues = PREF_QUALITY_VALUE
        setDefaultValue(PREF_QUALITY_DEFAULT)
    }.also(screen::addPreference)
}

internal fun Torrentio.addLanguageAndSortPreferences(screen: PreferenceScreen) {
    // Priority foreign language
    MultiSelectListPreference(screen.context).apply {
        key = PREF_LANG_KEY
        title = "Priority foreign language"
        entries = PREF_LANG
        entryValues = PREF_LANG_VALUE
        setDefaultValue(PREF_LANG_DEFAULT)
    }.also(screen::addPreference)

    // Sorting
    ListPreference(screen.context).apply {
        key = PREF_SORT_KEY
        title = "Sorting"
        entries = PREF_SORT_ENTRIES
        entryValues = PREF_SORT_VALUES
        setDefaultValue("quality")
        summary = "%s"
    }.also(screen::addPreference)
}

internal fun Torrentio.addTitlePreferences(screen: PreferenceScreen) {
    // Title handler
    ListPreference(screen.context).apply {
        key = PREF_TITLE_KEY
        title = "Preferred Title"
        entries = PREF_TITLE_ENTRIES
        entryValues = PREF_TITLE_VALUES
        setDefaultValue("romaji")
    }.also(screen::addPreference)
}

internal fun Torrentio.addVideoPriorityPreferences(screen: PreferenceScreen): SwitchPreferenceCompat {
    val codecPreferences = preferences.getStringSet(PREF_CODEC_KEY, PREF_CODEC_DEFAULT) ?: setOf()

    SwitchPreferenceCompat(screen.context).apply {
        key = UPCOMING_EP_KEY
        title = "Show Upcoming Episodes"
        setDefaultValue(UPCOMING_EP_DEFAULT)
    }.also(screen::addPreference)

    SwitchPreferenceCompat(screen.context).apply {
        key = IS_DUB_KEY
        title = "Dubbed Video Priority"
        setDefaultValue(IS_DUB_DEFAULT)
    }.also(screen::addPreference)

    return SwitchPreferenceCompat(screen.context).apply {
        key = IS_EFFICIENT_KEY
        title = "Efficient Video Priority"
        setDefaultValue(IS_EFFICIENT_DEFAULT)
        setVisible(codecPreferences.isEmpty())
        summary = "Codec: (HEVC / x265)  & AV1. High-quality video with less data usage."
    }.also(screen::addPreference)
}

internal fun Torrentio.addCodecPreferences(screen: PreferenceScreen, efficientPref: SwitchPreferenceCompat) {
    val codecPreferences = preferences.getStringSet(PREF_CODEC_KEY, PREF_CODEC_DEFAULT) ?: setOf()

    // Preferred Codecs
    MultiSelectListPreference(screen.context).apply {
        key = PREF_CODEC_KEY
        title = "Preferred Codecs"
        entries = PREF_CODEC
        entryValues = PREF_CODEC_VALUE
        setDefaultValue(PREF_CODEC_DEFAULT)
        summary = codecPreferences.joinToString()

        setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            val newSet = newValue as Set<String>
            preferences.edit().putStringSet(key, newSet).apply()
            summary = newSet.joinToString()
            efficientPref.setVisible(newSet.isEmpty())
            true
        }
    }.also(screen::addPreference)
}

// Token
internal const val PREF_TOKEN_KEY = "token"
internal const val PREF_TOKEN_DEFAULT = ""
internal const val PREF_TOKEN_SUMMARY = "Exclusive to Debrid providers; not intended for Torrents."

// Debrid
internal const val PREF_DEBRID_KEY = "debrid_provider"
internal val PREF_DEBRID_ENTRIES = arrayOf(
    "None",
    "RealDebrid",
    "Premiumize",
    "AllDebrid",
    "DebridLink",
    "Offcloud",
    "TorBox",
)
internal val PREF_DEBRID_VALUES = arrayOf(
    "none",
    "realdebrid",
    "premiumize",
    "alldebrid",
    "debridlink",
    "offcloud",
    "torbox",
)

// Sort
internal const val PREF_SORT_KEY = "sorting_link"
internal val PREF_SORT_ENTRIES = arrayOf(
    "By quality then seeders",
    "By quality then size",
    "By seeders",
    "By size",
)
internal val PREF_SORT_VALUES = arrayOf(
    "quality",
    "qualitysize",
    "seeders",
    "size",
)

// Provider
internal const val PREF_PROVIDER_KEY = "provider_selection"
internal val PREF_PROVIDERS = arrayOf(
    "YTS",
    "EZTV",
    "RARBG",
    "1337x",
    "ThePirateBay",
    "KickassTorrents",
    "TorrentGalaxy",
    "MagnetDL",
    "HorribleSubs",
    "NyaaSi",
    "TokyoTosho",
    "AniDex",
    "🇷🇺 Rutor",
    "🇷🇺 Rutracker",
    "🇵🇹 Comando",
    "🇵🇹 BluDV",
    "🇫🇷 Torrent9",
    "🇪🇸 MejorTorrent",
    "🇲🇽 Cinecalidad",
    "🇮🇹 ilCorsaroNero",
    "🇪🇸 Wolfmax4k",
)

internal val PREF_PROVIDERS_VALUE = arrayOf(
    "yts",
    "eztv",
    "rarbg",
    "1337x",
    "thepiratebay",
    "kickasstorrents",
    "torrentgalaxy",
    "magnetdl",
    "horriblesubs",
    "nyaasi",
    "tokyotosho",
    "anidex",
    "rutor",
    "rutracker",
    "comando",
    "bludv",
    "torrent9",
    "mejortorrent",
    "cinecalidad",
    "ilcorsaronero",
    "wolfmax4k",
)

internal val PREF_DEFAULT_PROVIDERS_VALUE = arrayOf(
    "yts",
    "eztv",
    "rarbg",
    "1337x",
    "thepiratebay",
    "kickasstorrents",
    "torrentgalaxy",
    "magnetdl",
    "horriblesubs",
    "nyaasi",
    "tokyotosho",
    "anidex",
)
internal val PREF_PROVIDERS_DEFAULT = PREF_DEFAULT_PROVIDERS_VALUE.toSet()

// Qualities/Resolutions
internal const val PREF_QUALITY_KEY = "quality_selection"
internal val PREF_QUALITY = arrayOf(
    "BluRay REMUX",
    "HDR/HDR10+/Dolby Vision",
    "Dolby Vision",
    "Dolby Vision + HDR",
    "3D",
    "Non 3D (DO NOT SELECT IF NOT SURE)",
    "4k",
    "1080p",
    "720p",
    "480p",
    "Other (DVDRip/HDRip/BDRip...)",
    "Screener",
    "Cam",
    "Unknown",
)

internal val PREF_QUALITY_VALUE = arrayOf(
    "brremux",
    "hdrall",
    "dolbyvision",
    "dolbyvisionwithhdr",
    "threed",
    "nonthreed",
    "4k",
    "1080p",
    "720p",
    "480p",
    "other",
    "scr",
    "cam",
    "unknown",
)

internal val PREF_DEFAULT_QUALITY_VALUE = arrayOf(
    "720p",
    "480p",
    "other",
    "scr",
    "cam",
    "unknown",
)

internal val PREF_QUALITY_DEFAULT = PREF_DEFAULT_QUALITY_VALUE.toSet()

// Qualities/Resolutions
internal const val PREF_LANG_KEY = "lang_selection"
internal val PREF_LANG = arrayOf(
    "🇯🇵 Japanese",
    "🇷🇺 Russian",
    "🇮🇹 Italian",
    "🇵🇹 Portuguese",
    "🇪🇸 Spanish",
    "🇲🇽 Latino",
    "🇰🇷 Korean",
    "🇨🇳 Chinese",
    "🇹🇼 Taiwanese",
    "🇫🇷 French",

    "🇩🇪 German",
    "🇳🇱 Dutch",
    "🇮🇳 Hindi",
    "🇮🇳 Telugu",
    "🇮🇳 Tamil",
    "🇵🇱 Polish",
    "🇱🇹 Lithuanian",
    "🇱🇻 Latvian",
    "🇪🇪 Estonian",
    "🇨🇿 Czech",

    "🇸🇰 Slovakian",
    "🇸🇮 Slovenian",
    "🇭🇺 Hungarian",
    "🇷🇴 Romanian",
    "🇧🇬 Bulgarian",
    "🇷🇸 Serbian",
    "🇭🇷 Croatian",
    "🇺🇦 Ukrainian",
    "🇬🇷 Greek",
    "🇩🇰 Danish",

    "🇫🇮 Finnish",
    "🇸🇪 Swedish",
    "🇳🇴 Norwegian",
    "🇹🇷 Turkish",
    "🇸🇦 Arabic",
    "🇮🇷 Persian",
    "🇮🇱 Hebrew",
    "🇻🇳 Vietnamese",
    "🇮🇩 Indonesian",
    "🇲🇾 Malay",

    "🇹🇭 Thai",
)
internal val PREF_LANG_VALUE = arrayOf(
    "japanese",
    "russian",
    "italian",
    "portuguese",
    "spanish",
    "latino",
    "korean",
    "chinese",
    "taiwanese",
    "french",

    "german",
    "dutch",
    "hindi",
    "telugu",
    "tamil",
    "polish",
    "lithuanian",
    "latvian",
    "estonian",
    "czech",

    "slovakian",
    "slovenian",
    "hungarian",
    "romanian",
    "bulgarian",
    "serbian",
    "croatian",
    "ukrainian",
    "greek",
    "danish",

    "finnish",
    "swedish",
    "norwegian",
    "turkish",
    "arabic",
    "persian",
    "hebrew",
    "vietnamese",
    "indonesian",
    "malay",

    "thai",

)

internal val PREF_LANG_DEFAULT = setOf<String>()

// Title
internal const val PREF_TITLE_KEY = "pref_title"
internal val PREF_TITLE_ENTRIES = arrayOf(
    "Romaji",
    "English",
    "Native",
)
internal val PREF_TITLE_VALUES = arrayOf(
    "romaji",
    "english",
    "native",
)

internal const val UPCOMING_EP_KEY = "upcoming_ep"
internal const val UPCOMING_EP_DEFAULT = false

internal const val IS_DUB_KEY = "dubbed"
internal const val IS_DUB_DEFAULT = false

internal const val IS_EFFICIENT_KEY = "efficient"
internal const val IS_EFFICIENT_DEFAULT = false

internal const val PREF_CODEC_KEY = "codec_selection"
internal val PREF_CODEC = arrayOf(
    "x264",
    "x265/HEVC",
    "AV1",
    "VP9",
    "Other",
)
internal val PREF_CODEC_VALUE = arrayOf(
    "x264",
    "x265",
    "av1",
    "vp9",
    "other",
)
internal val PREF_CODEC_DEFAULT = setOf<String>() // Empty by default to show all
