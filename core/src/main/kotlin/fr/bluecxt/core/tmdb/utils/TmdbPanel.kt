// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.tmdb.utils

import fr.bluecxt.core.source.Source
import fr.bluecxt.core.tmdb.TmdbMetadata
import fr.bluecxt.core.tmdb.fetchTmdbEpisodeGroupMetadata
import fr.bluecxt.core.tmdb.fetchTmdbMetadata
import fr.bluecxt.core.tmdb.fetchTmdbMovieMetadata
import fr.bluecxt.core.utils.normalize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class PanelMediaType {
    MOVIE,
    SPIN_OFF,
    SEASON,
}

@Serializable
data class TmdbOverrideEntry(
    val tmdbSeason: Int? = null,
    val episodeOffset: Int = 0,
)

@Serializable
data class TmdbSeriesOverride(
    val episodeGroupId: String? = null,
    val seasons: Map<String, TmdbOverrideEntry> = emptyMap(),
)

private val overrideJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private val tmdbOverridesMap: Map<String, TmdbSeriesOverride> by lazy {
    try {
        val stream = Source::class.java.getResourceAsStream("/tmdb_overrides.json")
        val content = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        overrideJson.decodeFromString<Map<String, TmdbSeriesOverride>>(content)
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun resolvePanelMediaType(rawSeasonName: String?): PanelMediaType {
    val isMovie = rawSeasonName?.contains("Film", ignoreCase = true) == true || rawSeasonName?.contains("Movie", ignoreCase = true) == true
    val isSpinOff = rawSeasonName != null && extractSeasonNumber(rawSeasonName) == null && !isMovie
    return when {
        isMovie -> PanelMediaType.MOVIE
        isSpinOff -> PanelMediaType.SPIN_OFF
        else -> PanelMediaType.SEASON
    }
}

private suspend fun Source.findMovieMetadata(seriesTitle: String, fullTitle: String, titles: Set<String>): TmdbMetadata? = fetchTmdbMovieMetadata(seriesTitle)
    ?: fetchTmdbMovieMetadata(fullTitle)
    ?: titles.firstNotNullOfOrNull { fetchTmdbMovieMetadata(it) }

private suspend fun Source.findSpinOffMetadata(
    seriesTitle: String,
    fullTitle: String,
    titles: Set<String>,
    rawSeasonName: String?,
    seasonNumber: Int,
): TmdbMetadata? {
    val spinOffName = rawSeasonName.orEmpty().trim()
    return fetchTmdbMetadata(spinOffName, 1)
        ?: fetchTmdbMetadata(fullTitle, 1)
        ?: titles.firstNotNullOfOrNull { fetchTmdbMetadata(it, seasonNumber) }
        ?: fetchTmdbMetadata(seriesTitle, seasonNumber)
}

private suspend fun Source.findSeasonMetadata(seriesTitle: String, titles: Set<String>, seasonNumber: Int): TmdbMetadata? = titles.firstNotNullOfOrNull { fetchTmdbMetadata(it, seasonNumber) }
    ?: fetchTmdbMetadata(seriesTitle, seasonNumber)

/**
 * High-level helper function for extensions to fetch TMDB metadata for any panel/tab
 * (Movies, Spin-offs like Steins;Gate 0, or standard Seasons).
 */
suspend fun Source.fetchTmdbForPanel(
    seriesTitle: String,
    rawSeasonName: String?,
    fullTitle: String,
    titles: Set<String> = emptySet(),
): TmdbMetadata? {
    val normTitle = seriesTitle.normalize()
    val normSeason = rawSeasonName.orEmpty().normalize()

    val seriesOverride = tmdbOverridesMap.entries.find { normTitle.contains(it.key.normalize()) }?.value
    val overrideEntry = seriesOverride?.seasons?.entries?.find { normSeason.contains(it.key.normalize()) }?.value

    val calculatedSeasonNumber = rawSeasonName?.let { extractSeasonNumber(it) } ?: 1
    val seasonNumber = overrideEntry?.tmdbSeason ?: calculatedSeasonNumber
    val episodeOffset = overrideEntry?.episodeOffset ?: 0

    if (seriesOverride?.episodeGroupId != null) {
        val groupResult = fetchTmdbEpisodeGroupMetadata(seriesOverride.episodeGroupId, seasonNumber)
        if (groupResult != null) return groupResult
    }

    val mediaType = resolvePanelMediaType(rawSeasonName)

    val result = when (mediaType) {
        PanelMediaType.MOVIE -> findMovieMetadata(seriesTitle, fullTitle, titles)
        PanelMediaType.SPIN_OFF -> findSpinOffMetadata(seriesTitle, fullTitle, titles, rawSeasonName, seasonNumber)
        PanelMediaType.SEASON -> findSeasonMetadata(seriesTitle, titles, seasonNumber)
    }

    return result?.copy(episodeOffset = episodeOffset)
}
