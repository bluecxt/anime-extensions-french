// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.multisrc.madara

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import fr.bluecxt.core.filters.FilterEngine
import fr.bluecxt.core.filters.FilterSpec

object MadaraFilters {

    val ORDER_BY = FilterSpec.Select(
        name = "Trier par",
        param = "m_orderby",
        options = arrayOf(
            "Pertinence" to "",
            "Date" to "latest",
            "A-Z" to "alphabet",
            "Note" to "rating",
            "Populaire" to "trending",
            "Plus vues" to "views",
            "Nouveautés" to "new-manga",
        ),
    )

    val STATUS = FilterSpec.Group(
        name = "Statut",
        param = "status[]",
        options = listOf(
            "En cours" to "on-going",
            "Terminé" to "end",
            "Annulé" to "canceled",
            "En pause" to "on-hold",
        ),
    )

    val ADULT = FilterSpec.Select(
        name = "Contenu adulte",
        param = "adult",
        options = arrayOf(
            "Tous" to "",
            "Sans contenu adulte" to "0",
            "Uniquement contenu adulte" to "1",
        ),
    )

    val YEAR = FilterSpec.Text(
        name = "Année de sortie",
        param = "year",
    )

    val GENRE_OP = FilterSpec.Select(
        name = "Opérateur de genres",
        param = "op",
        options = arrayOf(
            "OU (au moins un genre)" to "",
            "ET (tous les genres)" to "1",
        ),
    )

    fun buildFilterList(additionalFilters: List<FilterSpec> = emptyList()): AnimeFilterList {
        val specs = mutableListOf<FilterSpec>(
            ORDER_BY,
            STATUS,
            ADULT,
            YEAR,
        )

        val hasGenres = additionalFilters.any { it is FilterSpec.Group && it.param.startsWith("genre") }
        if (hasGenres) {
            specs.add(GENRE_OP)
        }

        return FilterEngine.buildFilterList(
            customSpecs = specs + additionalFilters,
        )
    }
}
