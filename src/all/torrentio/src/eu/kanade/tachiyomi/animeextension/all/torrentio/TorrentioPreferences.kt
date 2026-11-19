// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.all.torrentio

import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import keiyoushi.core.R

internal fun Torrentio.addTorrentioPreferences(screen: PreferenceScreen) {
    addDebridPreferences(screen)
    addProviderPreferences(screen)
    addLanguageAndSortPreferences(screen)
    addVideoPriorityPreferences(screen)
    addJustWatchPreferences(screen)
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

        setOnPreferenceChangeListener { _, newValue ->
            val selected = newValue as String
            val index = findIndexOfValue(selected)
            val entry = entryValues[index] as String
            preferences.edit().putString(key, entry).commit()
        }
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

        setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            preferences.edit().putStringSet(key, newValue as Set<String>).commit()
        }
    }.also(screen::addPreference)

    // Exclude Qualities
    MultiSelectListPreference(screen.context).apply {
        key = PREF_QUALITY_KEY
        title = "Exclude Qualities/Resolutions"
        entries = PREF_QUALITY
        entryValues = PREF_QUALITY_VALUE
        setDefaultValue(PREF_QUALITY_DEFAULT)

        setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            preferences.edit().putStringSet(key, newValue as Set<String>).commit()
        }
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

        setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            preferences.edit().putStringSet(key, newValue as Set<String>).commit()
        }
    }.also(screen::addPreference)

    // Sorting
    ListPreference(screen.context).apply {
        key = PREF_SORT_KEY
        title = "Sorting"
        entries = PREF_SORT_ENTRIES
        entryValues = PREF_SORT_VALUES
        setDefaultValue("quality")
        summary = "%s"

        setOnPreferenceChangeListener { _, newValue ->
            val selected = newValue as String
            val index = findIndexOfValue(selected)
            val entry = entryValues[index] as String
            preferences.edit().putString(key, entry).commit()
        }
    }.also(screen::addPreference)
}

internal fun Torrentio.addVideoPriorityPreferences(screen: PreferenceScreen) {
    SwitchPreferenceCompat(screen.context).apply {
        key = UPCOMING_EP_KEY
        title = "Show Upcoming Episodes"
        setDefaultValue(UPCOMING_EP_DEFAULT)
        setOnPreferenceChangeListener { _, newValue ->
            preferences.edit().putBoolean(key, newValue as Boolean).commit()
        }
    }.also(screen::addPreference)

    SwitchPreferenceCompat(screen.context).apply {
        key = IS_DUB_KEY
        title = "Dubbed Video Priority"
        setDefaultValue(IS_DUB_DEFAULT)
        setOnPreferenceChangeListener { _, newValue ->
            preferences.edit().putBoolean(key, newValue as Boolean).commit()
        }
    }.also(screen::addPreference)

    SwitchPreferenceCompat(screen.context).apply {
        key = IS_EFFICIENT_KEY
        title = "Efficient Video Priority"
        setDefaultValue(IS_EFFICIENT_DEFAULT)
        setOnPreferenceChangeListener { _, newValue ->
            preferences.edit().putBoolean(key, newValue as Boolean).commit()
        }
        summary = "Codec: (HEVC / x265)  & AV1. High-quality video with less data usage."
    }.also(screen::addPreference)
}

internal fun Torrentio.addJustWatchPreferences(screen: PreferenceScreen) {
    // JustWatch Settings

    // Region
    ListPreference(screen.context).apply {
        key = PREF_REGION_KEY
        title = "Catalogue Region"
        entries = PREF_REGION_ENTRIES
        entryValues = PREF_REGION_VALUES
        setDefaultValue(PREF_REGION_DEFAULT)
        summary = "Region based catalogue recommendation."

        setOnPreferenceChangeListener { _, newValue ->
            val selected = newValue as String
            val index = findIndexOfValue(selected)
            val entry = entryValues[index] as String
            preferences.edit().putString(key, entry).commit()
        }
    }.also(screen::addPreference)

    // Poster and Titles Language
    ListPreference(screen.context).apply {
        key = PREF_JW_LANG_KEY
        title = "Poster and Titles Language"
        entries = PREF_JW_LANG_ENTRIES
        entryValues = PREF_JW_LANG_VALUES
        setDefaultValue(PREF_JW_LANG_DEFAULT)

        setOnPreferenceChangeListener { _, newValue ->
            val selected = newValue as String
            val index = findIndexOfValue(selected)
            val entry = entryValues[index] as String
            preferences.edit().putString(key, entry).commit()
        }
    }.also(screen::addPreference)

    // TMDB Language
    ListPreference(screen.context).apply {
        key = PREF_TMDB_LANG_KEY
        title = "TMDB: Metadata Language (Episodes)"
        entries = PREF_TMDB_LANG_ENTRIES
        entryValues = PREF_TMDB_LANG_VALUES
        setDefaultValue(PREF_TMDB_LANG_DEFAULT)
        summary = "Language for episode descriptions and thumbnails."

        setOnPreferenceChangeListener { _, newValue ->
            val selected = newValue as String
            val index = findIndexOfValue(selected)
            val entry = entryValues[index] as String
            preferences.edit().putString(key, entry).commit()
        }
    }.also(screen::addPreference)
}

// Preference keys and entry/value arrays.
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

// / Qualities/Resolutions
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

internal const val UPCOMING_EP_KEY = "upcoming_ep"
internal const val UPCOMING_EP_DEFAULT = false

internal const val IS_DUB_KEY = "dubbed"
internal const val IS_DUB_DEFAULT = false

internal const val IS_EFFICIENT_KEY = "efficient"
internal const val IS_EFFICIENT_DEFAULT = false

// JustWatch settings
// Region
internal const val PREF_REGION_KEY = "jw_region"
internal val PREF_REGION_ENTRIES = arrayOf(
    "Albania", "Algeria", "Androrra", "Angola", "Antigua and Barbuda", "Argentina", "Australia", "Austria", "Azerbaijan", "Bahamas", "Bahrain", "Barbados", "Belarus", "Belgium", "Belize", "Bermuda", "Bolivia", "Bosnia and Herzegovina", "Brazil", "Bulgaria", "Burkina Faso", "Cameroon", "Canada", "Cape Verde", "Chad", "Chile", "Colombia", "Costa Rica", "Croatia", "Cuba", "Cyprus", "Czech Republic", "DR Congo", "Denmark", "Dominican Republic", "Ecuador", "Egypt", "El Salvador", "Equatorial Guinea", "Estonia", "Fiji", "Finland", "France", "French Guiana", "French Polynesia", "Germany", "Ghana", "Gibraltar", "Greece", "Guatemala", "Guernsey", "Guyana", "Honduras", "Hong Kong", "Hungary", "Iceland", "India", "Indonesia", "Iraq", "Ireland", "Israel", "Italy", "Ivory Coast", "Jamaica", "Japan", "Jordan", "Kenya", "Kosovo", "Kuwait", "Latvia", "Lebanon", "Libya", "Liechtenstein", "Lithuania", "Luxembourg", "Macedonia", "Madagascar", "Malawi", "Malaysia", "Mali", "Malta", "Mauritius", "Mexico", "Moldova", "Monaco", "Montenegro", "Morocco", "Mozambique", "Netherlands", "New Zealand", "Nicaragua", "Niger", "Nigeria", "Norway", "Oman", "Pakistan", "Palestine", "Panama", "Papua New Guinea", "Paraguay", "Peru", "Philippines", "Poland", "Portugal", "Qatar", "Romania", "Russia", "Saint Lucia", "San Marino", "Saudi Arabia", "Senegal", "Serbia", "Seychelles", "Singapore", "Slovakia", "Slovenia", "South Africa", "South Korea", "Spain", "Sweden", "Switzerland", "Taiwan", "Tanzania", "Thailand", "Trinidad and Tobago", "Tunisia", "Turkey", "Turks and Caicos Islands", "Uganda", "Ukraine", "United Arab Emirates", "United Kingdom", "United States", "Uruguay", "Vatican City", "Venezuela", "Yemen", "Zambia", "Zimbabwe",
)
internal val PREF_REGION_VALUES = arrayOf(
    "AL", "DZ", "AD", "AO", "AG", "AR", "AU", "AT", "AZ", "BS", "BH", "BB", "BY", "BE", "BZ", "BM", "BO", "BA", "BR", "BG", "BF", "CM", "CA", "CV", "TD", "CL", "CO", "CR", "HR", "CU", "CY", "CZ", "CD", "DK", "DO", "EC", "EG", "SV", "GQ", "EE", "FJ", "FI", "FR", "GF", "PF", "DE", "GH", "GI", "GR", "GT", "GG", "GY", "HN", "HK", "HU", "IS", "IN", "ID", "IQ", "IE", "IL", "IT", "CI", "JM", "JP", "JO", "KE", "XK", "KW", "LV", "LB", "LY", "LI", "LT", "LU", "MK", "MG", "MW", "MY", "ML", "MT", "MU", "MX", "MD", "MC", "ME", "MA", "MZ", "NL", "NZ", "NI", "NE", "NG", "NO", "OM", "PK", "PS", "PA", "PG", "PY", "PE", "PH", "PL", "PT", "QA", "RO", "RU", "LC", "SM", "SA", "SN", "RS", "SC", "SG", "SK", "SI", "ZA", "KR", "ES", "SE", "CH", "TW", "TZ", "TH", "TT", "TN", "TR", "TC", "UG", "UA", "AE", "UK", "US", "UY", "VA", "VE", "YE", "ZM", "ZW",
)
internal const val PREF_REGION_DEFAULT = "US"

// JustWatch language in Poster, Titles
internal const val PREF_JW_LANG_KEY = "jw_lang"
internal val PREF_JW_LANG_ENTRIES = arrayOf(
    "Arabic", "Azerbaijani", "Belarusian", "Bulgarian", "Bosnian", "Catalan", "Czech", "German", "Greek", "English", "English (U.S.A.)", "Spanish", "Spanish (Spain)", "Spanish (Latinamerican)", "Estonian", "Finnish", "French", "French (Canada)", "Hebrew", "Croatian", "Hungarian", "Icelandic", "Italian", "Japanese", "Korean", "Lithuanian", "Latvian", "Macedonian", "Maltese", "Polish", "Portuguese", "Portuguese (Portugal)", "Portuguese (Brazil)", "Romanian", "Russian", "Slovakian", "Slovenian", "Albanian", "Serbian", "Swedish", "Swahili", "Turkish", "Ukrainian", "Urdu", "Chinese",
)
internal val PREF_JW_LANG_VALUES = arrayOf(
    "ar", "az", "be", "bg", "bs", "ca", "cs", "de", "el", "en", "en-US", "es", "es-ES", "es-LA", "et", "fi", "fr", "fr-CA", "he", "hr", "hu", "is", "it", "ja", "ko", "lt", "lv", "mk", "mt", "pl", "pt", "pt-PT", "pt-BR", "ro", "ru", "sk", "sl", "sq", "sr", "sv", "sw", "tr", "uk", "ur", "zh",

)
internal const val PREF_JW_LANG_DEFAULT = "en"

// TMDB Language
internal const val PREF_TMDB_LANG_KEY = "tmdb_lang"
internal val PREF_TMDB_LANG_ENTRIES = arrayOf("English", "French")
internal val PREF_TMDB_LANG_VALUES = arrayOf("en-US", "fr-FR")
internal const val PREF_TMDB_LANG_DEFAULT = "en-US"
