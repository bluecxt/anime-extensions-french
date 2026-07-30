package fr.bluecxt.core.tvdb.utils

import fr.bluecxt.core.Source
import fr.bluecxt.core.tmdb.utils.extractSeasonNumber
import fr.bluecxt.core.tmdb.utils.sanitizeTitle
import fr.bluecxt.core.tvdb.TvdbMetadata
import fr.bluecxt.core.tvdb.fetchTvdbMetadata
import fr.bluecxt.core.utils.normalize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TvdbOverrideEntry(
    val tvdbSeason: Int? = null,
    val episodeOffset: Int = 0,
)

@Serializable
data class TvdbSeriesOverride(
    val seasons: Map<String, TvdbOverrideEntry> = emptyMap(),
)

private val tvdbOverrideJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private val tvdbOverridesMap: Map<String, TvdbSeriesOverride> by lazy {
    try {
        val stream = Source::class.java.getResourceAsStream("/tvdb_overrides.json")
        val content = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        tvdbOverrideJson.decodeFromString<Map<String, TvdbSeriesOverride>>(content)
    } catch (_: Exception) {
        emptyMap()
    }
}

/**
 * High-level helper function for extensions to fetch TVDB metadata for any panel/tab.
 */
suspend fun Source.fetchTvdbForPanel(
    seriesTitle: String,
    rawSeasonName: String?,
    fullTitle: String,
    titles: Set<String> = emptySet(),
    isMovie: Boolean = false,
): TvdbMetadata? {
    val normTitle = seriesTitle.normalize()
    val normSeason = rawSeasonName.orEmpty().normalize()

    val seriesOverride = tvdbOverridesMap.entries.find { normTitle.contains(it.key.normalize()) }?.value
    val overrideEntry = seriesOverride?.seasons?.entries?.find { normSeason.contains(it.key.normalize()) }?.value

    val calculatedSeasonNumber = rawSeasonName?.let { extractSeasonNumber(it) } ?: 1
    val seasonNumber = overrideEntry?.tvdbSeason ?: calculatedSeasonNumber
    val episodeOffset = overrideEntry?.episodeOffset ?: 0

    // 1. Try Primary Titles first (seriesTitle & fullTitle)
    val primaryTitles = listOf(sanitizeTitle(seriesTitle), sanitizeTitle(fullTitle))
        .filter { it.isNotBlank() }
        .distinct()

    val primaryResults = primaryTitles.mapNotNull { title ->
        fetchTvdbMetadata(title, seasonNumber, isMovie = isMovie)
    }

    val bestPrimary = primaryResults.maxByOrNull { it.matchScore }
    if (bestPrimary != null && bestPrimary.matchScore >= 60) {
        return bestPrimary.copy(episodeOffset = episodeOffset)
    }

    // 2. Fallback to Secondary Titles only if primary score is < 60
    val minLen = if (seriesTitle.length <= 4) seriesTitle.length else 4
    val secondaryTitles = titles.map { sanitizeTitle(it) }
        .filter { it.isNotBlank() && it.length >= minLen && !primaryTitles.contains(it) }
        .distinct()

    val secondaryResults = secondaryTitles.take(5).mapNotNull { title ->
        fetchTvdbMetadata(title, seasonNumber, isMovie = isMovie)
    }

    val allCandidates = primaryResults + secondaryResults
    val result = allCandidates.maxByOrNull { it.matchScore } ?: bestPrimary

    return result?.copy(episodeOffset = episodeOffset)
}
