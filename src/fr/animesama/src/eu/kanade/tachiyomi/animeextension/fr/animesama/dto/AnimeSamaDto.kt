// Copyright 2024 The Aniyomi Open Source Project
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.dto

import kotlinx.serialization.Serializable

@Serializable
data class UrlContent(
    val url: String,
    val titles: Set<String>,
    val season: String? = null,
)

@Serializable
data class Player(
    val url: String,
    val lang: String,
)

data class EpisodePlayers(
    val episodeNumber: Int,
    val players: List<Player>,
) {
    fun getAvailableLanguages(): List<String> = players.map { it.lang }.distinct()
    fun getScanlatorString(): String = getAvailableLanguages().joinToString(", ")
}

enum class ContentType {
    MOVIE,
    SPECIAL,
    OAV,
    SEASON,
    ;

    fun getPrefix(seasonNum: Int = 1): String = when (this) {
        MOVIE -> "[Movie] "
        SPECIAL -> "[Special] "
        OAV -> "[OAV] "
        SEASON -> if (seasonNum > 1) "[S$seasonNum] " else ""
    }

    companion object {
        private val kaiRegex by lazy { Regex("""(?i)\bKai(\s+saison\s+\d+)?$""") }

        fun from(title: String, urlPath: String = ""): ContentType = when {
            title.contains("Film", ignoreCase = true) ||
                title.contains("Movie", ignoreCase = true) ||
                urlPath.contains("film", ignoreCase = true) ||
                urlPath.contains("movie", ignoreCase = true) -> MOVIE

            title.contains("Special", ignoreCase = true) ||
                title.contains("Director's Cut", ignoreCase = true) ||
                kaiRegex.containsMatchIn(title) -> SPECIAL

            title.contains("OAV", ignoreCase = true) ||
                title.contains("OVA", ignoreCase = true) ||
                urlPath.contains("oav", ignoreCase = true) -> OAV

            else -> SEASON
        }
    }
}
