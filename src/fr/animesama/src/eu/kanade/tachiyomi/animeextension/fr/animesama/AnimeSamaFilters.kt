package eu.kanade.tachiyomi.animeextension.fr.animesama

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.serialization.json.Json

object AnimeSamaFilters {

    private val filterData by lazy {
        val jsonStream = AnimeSamaFilters::class.java.getResourceAsStream("filters.json")
        val jsonString = jsonStream?.bufferedReader()?.use { it.readText() } ?: "{}"
        try {
            Json.decodeFromString<Map<String, List<List<String>>>>(jsonString)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun getOptions(key: String): Array<Pair<String, String>> = filterData[key]?.map { it[0] to it[1] }?.toTypedArray() ?: emptyArray()

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    open class SelectFilterList(name: String, options: Array<Pair<String, String>>) : AnimeFilter.

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.getFirst(): R = this.filterIsInstance<R>().first()

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> = (this.getFirst<R>() as CheckBoxFilterList).state
        .mapNotNull { checkbox ->
            if (checkbox.state) {
                options.find { it.first == checkbox.name }!!.second
            } else {
                null
            }
        }

    class TypesFilter :
        CheckBoxFilterList(
            "Type",
            getOptions("TYPES").map { CheckBoxVal(it.first, false) },
        )

    class LangFilter :
        CheckBoxFilterList(
            "Langage",
            getOptions("LANGUAGES").map { CheckBoxVal(it.first, false) },
        )

    class StatutFilter :
        CheckBoxFilterList(
            "Statut",
            getOptions("STATUT").map { CheckBoxVal(it.first, false) },
        )

    class GenresFilter :
        CheckBoxFilterList(
            "Genre",
            getOptions("GENRES").map { CheckBoxVal(it.first, false) },
        )

    val FILTER_LIST get() = AnimeFilterList(
        TypesFilter(),
        LangFilter(),
        StatutFilter(),
        GenresFilter(),
    )

    data class SearchFilters(
        val types: List<String> = emptyList(),
        val language: List<String> = emptyList(),
        val statut: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
    )

    fun getSearchFilters(filters: AnimeFilterList): SearchFilters {
        if (filters.isEmpty()) return SearchFilters()
        return SearchFilters(
            filters.parseCheckbox<TypesFilter>(getOptions("TYPES")),
            filters.parseCheckbox<LangFilter>(getOptions("LANGUAGES")),
            filters.parseCheckbox<StatutFilter>(getOptions("STATUT")),
            filters.parseCheckbox<GenresFilter>(getOptions("GENRES")),
        )
    }
}
