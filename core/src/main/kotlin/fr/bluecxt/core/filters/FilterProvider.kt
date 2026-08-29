// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.filters

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import okhttp3.HttpUrl

// ============================== Declarative Filter Specifications (DSL) ==============================

sealed interface FilterSpec {
    object Separator : FilterSpec
    class Header(val title: String) : FilterSpec

    class Select(
        val name: String,
        val param: String,
        val options: Array<Pair<String, String>>,
        val defaultIndex: Int = 0,
    ) : FilterSpec

    class Text(
        val name: String,
        val param: String,
        val defaultText: String = "",
    ) : FilterSpec

    class CheckBox(
        val name: String,
        val param: String,
        val value: String = "1",
        val defaultState: Boolean = false,
    ) : FilterSpec

    class Group(
        val name: String,
        val param: String,
        val options: List<Pair<String, String>>,
    ) : FilterSpec

    class TriStateGroup(
        val name: String,
        val includeParam: String,
        val excludeParam: String,
        val options: List<Pair<String, String>>,
    ) : FilterSpec
}

// ============================== Concrete AnimeFilter Classes ==============================

open class DynamicSelectFilter(
    name: String,
    val param: String,
    private val vals: Array<Pair<String, String>>,
    state: Int = 0,
) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray(), state) {
    fun toQuery(): String = if (vals.isNotEmpty() && state in vals.indices) vals[state].second else ""
}

class DynamicTextFilter(
    name: String,
    val param: String,
    state: String = "",
) : AnimeFilter.Text(name, state)

class DynamicCheckBoxFilter(
    name: String,
    val param: String,
    val queryValue: String,
    state: Boolean = false,
) : AnimeFilter.CheckBox(name, state)

class UriOption(name: String, val value: String) : AnimeFilter.CheckBox(name)

class DynamicGroupFilter(
    name: String,
    val param: String,
    options: List<UriOption>,
) : AnimeFilter.Group<UriOption>(name, options) {
    fun toQuery(): List<String> = state.filter { it.state }.map { it.value }
}

class TriStateOption(name: String, val value: String) : AnimeFilter.TriState(name)

class DynamicTriStateGroupFilter(
    name: String,
    val includeParam: String,
    val excludeParam: String,
    options: List<TriStateOption>,
) : AnimeFilter.Group<TriStateOption>(name, options) {
    fun included(): List<String> = state.filter { it.isIncluded() }.map { it.value }
    fun excluded(): List<String> = state.filter { it.isExcluded() }.map { it.value }
}

// ============================== Filter Engine ==============================

object FilterEngine {

    fun buildFilterList(
        baseFilters: List<AnimeFilter<*>> = emptyList(),
        customSpecs: List<FilterSpec> = emptyList(),
    ): AnimeFilterList {
        val list = mutableListOf<AnimeFilter<*>>()
        list.addAll(baseFilters)

        for (spec in customSpecs) {
            when (spec) {
                is FilterSpec.Separator -> list.add(AnimeFilter.Separator())
                is FilterSpec.Header -> list.add(AnimeFilter.Header(spec.title))
                is FilterSpec.Select -> list.add(DynamicSelectFilter(spec.name, spec.param, spec.options, spec.defaultIndex))
                is FilterSpec.Text -> list.add(DynamicTextFilter(spec.name, spec.param, spec.defaultText))
                is FilterSpec.CheckBox -> list.add(DynamicCheckBoxFilter(spec.name, spec.param, spec.value, spec.defaultState))
                is FilterSpec.Group -> list.add(DynamicGroupFilter(spec.name, spec.param, spec.options.map { UriOption(it.first, it.second) }))
                is FilterSpec.TriStateGroup -> list.add(DynamicTriStateGroupFilter(spec.name, spec.includeParam, spec.excludeParam, spec.options.map { TriStateOption(it.first, it.second) }))
            }
        }

        return AnimeFilterList(list)
    }

    fun applyFilters(builder: HttpUrl.Builder, filters: AnimeFilterList): HttpUrl.Builder {
        for (filter in filters) {
            when (filter) {
                is DynamicSelectFilter -> {
                    val value = filter.toQuery()
                    if (value.isNotBlank()) builder.addQueryParameter(filter.param, value)
                }

                is DynamicTextFilter -> {
                    val value = filter.state.trim()
                    if (value.isNotBlank()) builder.addQueryParameter(filter.param, value)
                }

                is DynamicCheckBoxFilter -> {
                    if (filter.state) builder.addQueryParameter(filter.param, filter.queryValue)
                }

                is DynamicGroupFilter -> {
                    filter.toQuery().forEach { value ->
                        builder.addQueryParameter(filter.param, value)
                    }
                }

                is DynamicTriStateGroupFilter -> {
                    filter.included().forEach { value ->
                        builder.addQueryParameter(filter.includeParam, value)
                    }
                    filter.excluded().forEach { value ->
                        builder.addQueryParameter(filter.excludeParam, value)
                    }
                }

                else -> {}
            }
        }
        return builder
    }

    fun getQueryMap(filters: AnimeFilterList): Map<String, List<String>> {
        val map = mutableMapOf<String, MutableList<String>>()

        for (filter in filters) {
            when (filter) {
                is DynamicSelectFilter -> {
                    val value = filter.toQuery()
                    if (value.isNotBlank()) map.getOrPut(filter.param) { mutableListOf() }.add(value)
                }

                is DynamicTextFilter -> {
                    val value = filter.state.trim()
                    if (value.isNotBlank()) map.getOrPut(filter.param) { mutableListOf() }.add(value)
                }

                is DynamicCheckBoxFilter -> {
                    if (filter.state) map.getOrPut(filter.param) { mutableListOf() }.add(filter.queryValue)
                }

                is DynamicGroupFilter -> {
                    filter.toQuery().forEach { value ->
                        map.getOrPut(filter.param) { mutableListOf() }.add(value)
                    }
                }

                is DynamicTriStateGroupFilter -> {
                    filter.included().forEach { value ->
                        map.getOrPut(filter.includeParam) { mutableListOf() }.add(value)
                    }
                    filter.excluded().forEach { value ->
                        map.getOrPut(filter.excludeParam) { mutableListOf() }.add(value)
                    }
                }

                else -> {}
            }
        }

        return map
    }

    fun getParam(filters: AnimeFilterList, param: String): String? = getQueryMap(filters)[param]?.firstOrNull()

    fun getParamList(filters: AnimeFilterList, param: String): List<String> = getQueryMap(filters)[param] ?: emptyList()
}

// ============================== FilterProvider Interface ==============================

interface FilterProvider {
    val customFilters: List<FilterSpec> get() = emptyList()

    fun buildFilterList(baseFilters: List<AnimeFilter<*>> = emptyList()): AnimeFilterList = FilterEngine.buildFilterList(baseFilters, customFilters)

    fun HttpUrl.Builder.applyFilters(filters: AnimeFilterList): HttpUrl.Builder = FilterEngine.applyFilters(this, filters)

    fun AnimeFilterList.getQueryMap(): Map<String, List<String>> = FilterEngine.getQueryMap(this)

    fun AnimeFilterList.getParam(param: String): String? = FilterEngine.getParam(this, param)

    fun AnimeFilterList.getParamList(param: String): List<String> = FilterEngine.getParamList(this, param)

    /**
     * Appends declarative filter specifications to an existing AnimeFilterList via '+'
     */
    operator fun AnimeFilterList.plus(specs: List<FilterSpec>): AnimeFilterList = FilterEngine.buildFilterList(baseFilters = this.list, customSpecs = specs)

    /**
     * Combines two AnimeFilterList instances via '+'
     */
    operator fun AnimeFilterList.plus(other: AnimeFilterList): AnimeFilterList = AnimeFilterList(this.list + other.list)

    // DSL Factory Helpers for ultra concise declarations
    fun select(name: String, param: String, options: Array<Pair<String, String>>, defaultIndex: Int = 0) = FilterSpec.Select(name, param, options, defaultIndex)

    fun group(name: String, param: String, options: List<Pair<String, String>>) = FilterSpec.Group(name, param, options)

    fun triStateGroup(name: String, includeParam: String, excludeParam: String, options: List<Pair<String, String>>) = FilterSpec.TriStateGroup(name, includeParam, excludeParam, options)

    fun text(name: String, param: String, defaultText: String = "") = FilterSpec.Text(name, param, defaultText)

    fun checkBox(name: String, param: String, value: String = "1", defaultState: Boolean = false) = FilterSpec.CheckBox(name, param, value, defaultState)

    fun header(title: String) = FilterSpec.Header(title)
    val separator get() = FilterSpec.Separator
}
