// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.voirdrama

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object VoirDramaFilters {

    open class UriPartFilter(
        name: String,
        private val vals: Array<Pair<String, String>>,
        state: Int = 0,
    ) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray(), state) {
        fun toQuery(): String = vals[state].second
    }

    class UriOption(name: String, val value: String) : AnimeFilter.CheckBox(name)

    open class UriGroupFilter(
        name: String,
        options: List<UriOption>,
    ) : AnimeFilter.Group<UriOption>(name, options) {
        fun toQuery(): List<String> = state.filter { it.state }.map { it.value }
    }

    class OrderByFilter :
        UriPartFilter(
            "Trier par",
            arrayOf(
                "Pertinence" to "",
                "Date" to "latest",
                "A-Z" to "alphabet",
                "Note" to "rating",
                "Populaire" to "trending",
                "Plus vues" to "views",
                "Nouveautés" to "new-manga",
            ),
        )

    class TypeFilter :
        UriPartFilter(
            "Format",
            arrayOf(
                "Tous" to "",
                "TV" to "TV",
                "Movie" to "MOVIE",
                "TV Short" to "TV SHORT",
                "OVA" to "OVA",
                "ONA" to "ONA",
                "Special" to "SPECIAL",
            ),
        )

    class LanguageFilter :
        UriPartFilter(
            "Langue",
            arrayOf(
                "Tous" to "",
                "VF" to "vf",
                "VOSTFR" to "vostfr",
            ),
        )

    class StatusFilter :
        UriGroupFilter(
            "Statut",
            listOf(
                UriOption("En cours", "on-going"),
                UriOption("Terminé", "end"),
                UriOption("Annulé", "canceled"),
                UriOption("En pause", "on-hold"),
            ),
        )

    class CountryFilter :
        UriPartFilter(
            "Pays",
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
        )

    class AdultFilter :
        UriPartFilter(
            "Contenu adulte",
            arrayOf(
                "Tous" to "",
                "Sans contenu adulte" to "0",
                "Uniquement contenu adulte" to "1",
            ),
        )

    class GenreOpFilter :
        UriPartFilter(
            "Opérateur de genres",
            arrayOf(
                "OU (au moins un genre)" to "",
                "ET (tous les genres)" to "1",
            ),
        )

    class YearFilter : AnimeFilter.Text("Année de sortie")

    class GenreFilter :
        UriGroupFilter(
            "Genres",
            listOf(
                UriOption("Action", "action"),
                UriOption("Affaires", "affaires"),
                UriOption("Amitié", "amitie"),
                UriOption("Arts martiaux", "arts-martiaux"),
                UriOption("Aventure", "aventure"),
                UriOption("Comédie", "comedie"),
                UriOption("Contexte scolaire", "contexte-scolaire"),
                UriOption("Crime", "crime"),
                UriOption("Culinaire", "culinaire"),
                UriOption("Documentaire", "documentaire"),
                UriOption("Drame", "drame"),
                UriOption("Famille", "famille"),
                UriOption("Fantastique", "fantastique"),
                UriOption("Guerre", "guerre"),
                UriOption("Historique", "historique"),
                UriOption("Horreur", "horreur"),
                UriOption("Jeunesse", "jeunesse"),
                UriOption("Judiciaire", "judiciaire"),
                UriOption("Mature", "mature"),
                UriOption("Médical", "medical"),
                UriOption("Mélodrame", "melodrame"),
                UriOption("Militaire", "militaire"),
                UriOption("Musique", "musique"),
                UriOption("Mystère", "mystere"),
                UriOption("Politique", "politique"),
                UriOption("Psychologique", "psychologique"),
                UriOption("Romance", "romance"),
                UriOption("SF", "sf"),
                UriOption("Sitcom", "sitcom"),
                UriOption("Sport", "sport"),
                UriOption("Surnaturel", "surnaturel"),
                UriOption("Thriller", "thriller"),
                UriOption("Tokusatsu", "tokusatsu"),
                UriOption("Vie quotidienne", "vie-quotidienne"),
                UriOption("Wuxia", "wuxia"),
            ),
        )

    data class SearchFilters(
        val order: String = "",
        val type: String = "",
        val language: String = "",
        val status: List<String> = emptyList(),
        val country: String = "",
        val adult: String = "",
        val genreOp: String = "",
        val year: String = "",
        val genres: List<String> = emptyList(),
    )

    fun getSearchFilters(filters: AnimeFilterList): SearchFilters {
        var order = ""
        var type = ""
        var language = ""
        var status = emptyList<String>()
        var country = ""
        var adult = ""
        var genreOp = ""
        var year = ""
        var genres = emptyList<String>()

        for (filter in filters) {
            when (filter) {
                is OrderByFilter -> order = filter.toQuery()
                is TypeFilter -> type = filter.toQuery()
                is LanguageFilter -> language = filter.toQuery()
                is StatusFilter -> status = filter.toQuery()
                is CountryFilter -> country = filter.toQuery()
                is AdultFilter -> adult = filter.toQuery()
                is GenreOpFilter -> genreOp = filter.toQuery()
                is YearFilter -> year = filter.state.trim()
                is GenreFilter -> genres = filter.toQuery()
                else -> {}
            }
        }

        return SearchFilters(
            order = order,
            type = type,
            language = language,
            status = status,
            country = country,
            adult = adult,
            genreOp = genreOp,
            year = year,
            genres = genres,
        )
    }

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        OrderByFilter(),
        TypeFilter(),
        LanguageFilter(),
        StatusFilter(),
        CountryFilter(),
        AdultFilter(),
        YearFilter(),
        GenreOpFilter(),
        GenreFilter(),
    )
}
