// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.voirdrama

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.madara.Madara

class VoirDrama : Madara("VoirDrama", "https://voirdrama.to", "fr") {

    override val supportedServers = listOf("Vidmoly", "Mymail", "Voe")

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = super.getFilterList() + listOf(
        select(
            "Format",
            "type",
            arrayOf(
                "Tous" to "",
                "TV" to "TV",
                "Movie" to "MOVIE",
                "TV Short" to "TV SHORT",
                "OVA" to "OVA",
                "ONA" to "ONA",
                "Special" to "SPECIAL",
            ),
        ),
        select(
            "Langue",
            "lang",
            arrayOf(
                "Tous" to "",
                "VF" to "vf",
                "VOSTFR" to "vostfr",
            ),
        ),
        select(
            "Pays",
            "country",
            arrayOf(
                "Tous" to "",
                "Chine" to "China",
                "Hong Kong" to "Hong Kong",
                "Indonésie" to "Indonesia",
                "Japon" to "Japan",
                "Philippines" to "Philippines",
                "Singapour" to "Singapore",
                "Corée du Sud" to "South Korea",
                "Taïwan" to "Taiwan",
                "Thaïlande" to "Thailand",
                "Vietnam" to "Vietnam",
            ),
        ),
        separator,
        group("Genres", "genre[]", GENRE_LIST),
    )

    companion object {
        private val GENRE_LIST = listOf(
            "Action" to "action", "Affaires" to "affaires", "Amitié" to "amitie", "Arts martiaux" to "arts-martiaux",
            "Aventure" to "aventure", "Comédie" to "comedie", "Contexte scolaire" to "contexte-scolaire", "Crime" to "crime",
            "Culinaire" to "culinaire", "Documentaire" to "documentaire", "Drame" to "drame", "Famille" to "famille",
            "Fantastique" to "fantastique", "Guerre" to "guerre", "Historique" to "historique", "Horreur" to "horreur",
            "Jeunesse" to "jeunesse", "Judiciaire" to "judiciaire", "Mature" to "mature", "Médical" to "medical",
            "Mélodrame" to "melodrame", "Militaire" to "militaire", "Musique" to "musique", "Mystère" to "mystere",
            "Politique" to "politique", "Psychologique" to "psychologique", "Romance" to "romance", "SF" to "sf",
            "Sitcom" to "sitcom", "Sport" to "sport", "Surnaturel" to "surnaturel", "Thriller" to "thriller",
            "Tokusatsu" to "tokusatsu", "Vie quotidienne" to "vie-quotidienne", "Wuxia" to "wuxia",
        )
    }
}
