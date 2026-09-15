// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.voiranime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.madara.Madara

class VoirAnime : Madara("VoirAnime", "https://voir-anime.to", "fr") {

    override val animeSubString = "anime"

    override val supportedServers = listOf("Vidmoly", "Voe", "Filemoon", "Streamtape")

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = super.getFilterList() + listOf(
        select(
            "Version",
            "filter",
            arrayOf(
                "Tous" to "",
                "VOSTFR" to "subbed",
                "VF" to "dubbed",
            ),
        ),
        separator,
        group("Genres", "genre[]", GENRE_LIST),
    )

    override fun resolveAnimeUrl(rawUrl: String): String {
        val path = if (rawUrl.startsWith("http")) rawUrl.substringAfter(baseUrl).substringAfter("voiranime.io") else rawUrl
        return if (path.startsWith("/series/")) {
            val slug = path.removePrefix("/series/").removeSuffix("/")
            "/$animeSubString/$slug/"
        } else {
            super.resolveAnimeUrl(rawUrl)
        }
    }

    companion object {
        private val GENRE_LIST = listOf(
            "Action" to "action",
            "Adventure" to "adventure",
            "Chinese" to "chinese",
            "Comedy" to "comedy",
            "Drama" to "drama",
            "Ecchi" to "ecchi",
            "Fantasy" to "fantasy",
            "Horror" to "horror",
            "Mahou Shoujo" to "mahou-shoujo",
            "Mecha" to "mecha",
            "Music" to "music",
            "Mystery" to "mystery",
            "Psychological" to "psychological",
            "Romance" to "romance",
            "Sci-Fi" to "sci-fi",
            "Slice of Life" to "slice-of-life",
            "Sports" to "sports",
            "Supernatural" to "supernatural",
            "Thriller" to "thriller",
        )
    }
}
