// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.tvdb.utils

import fr.bluecxt.core.Source
import fr.bluecxt.core.tmdb.utils.extractSeasonNumber
import fr.bluecxt.core.tmdb.utils.sanitizeTitle
import fr.bluecxt.core.tvdb.MediaPreference
import fr.bluecxt.core.tvdb.TvdbMetadata
import fr.bluecxt.core.tvdb.fetchTvdbMetadata
import fr.bluecxt.core.utils.normalize
import kotlinx.serialization.Serializable

@Serializable
data class TvdbOverrideEntry(
    val tvdbSeason: Int? = null,
    val episodeOffset: Int = 0,
    /**
     * TVDB season order type for poster/artwork lookup.
     * Maps to the season `type.altName` in TVDB API v4 `/series/{id}/extended`.
     * Values: "official" (Aired), "alternate" (Story Order), "alttwo" (Arc-based complete), "dvd", "absolute".
     */
    val seasonOrderType: String = "official",
    /**
     * When true, TVDB fetch is entirely skipped for this panel.
     * The source's own metadata (thumbnail, description) is used as-is.
     */
    val skipTvdb: Boolean = false,
    /**
     * Specific TVDB series or movie ID to fetch directly, bypassing fuzzy search.
     */
    val seriesId: Long? = null,
    /**
     * Explicit movie flag override.
     */
    val isMovie: Boolean? = null,
)

@Serializable
data class TvdbSeriesOverride(
    val seriesId: Long? = null,
    val seasons: Map<String, TvdbOverrideEntry> = emptyMap(),
)

private val demonSlayerOverride = TvdbSeriesOverride(
    seriesId = 348545L,
    seasons = mapOf(
        "saison 1" to TvdbOverrideEntry(tvdbSeason = 1),
        "film - train de l'infini" to TvdbOverrideEntry(seriesId = 131963L, isMovie = true),
        "episode - train de l'infini" to TvdbOverrideEntry(tvdbSeason = 2, seasonOrderType = "official"),
        "train de l'infini" to TvdbOverrideEntry(tvdbSeason = 2, seasonOrderType = "official"),
        "saison 2" to TvdbOverrideEntry(tvdbSeason = 3),
        "saison 3" to TvdbOverrideEntry(tvdbSeason = 4),
        "saison 4" to TvdbOverrideEntry(tvdbSeason = 5),
        "film - la forteresse infinie" to TvdbOverrideEntry(seriesId = 357928L, isMovie = true),
        "la forteresse infinie" to TvdbOverrideEntry(seriesId = 357928L, isMovie = true),
        "forteresse" to TvdbOverrideEntry(seriesId = 357928L, isMovie = true),
        "film" to TvdbOverrideEntry(skipTvdb = true),
    ),
)

private val tvdbOverridesMap: Map<String, TvdbSeriesOverride> = mapOf(
    "hanaori" to TvdbSeriesOverride(
        seasons = mapOf(
            "" to TvdbOverrideEntry(skipTvdb = true),
        ),
    ),
    "assassination classroom" to TvdbSeriesOverride(
        seriesId = 283947L,
        seasons = mapOf(
            "koro sensei quest" to TvdbOverrideEntry(tvdbSeason = 0),
            "film" to TvdbOverrideEntry(skipTvdb = true),
        ),
    ),
    "demon slayer" to demonSlayerOverride,
    "kimetsu no yaiba" to demonSlayerOverride,
    "one piece" to TvdbSeriesOverride(
        seriesId = 81797L,
        seasons = mapOf(
            // === Sagas 1-12 — Ordre DVD officiel TVDB (avec artwork dédié pour chaque saga) ===
            "saga 1" to TvdbOverrideEntry(tvdbSeason = 1, seasonOrderType = "dvd"),
            "saga 2" to TvdbOverrideEntry(tvdbSeason = 2, seasonOrderType = "dvd"),
            "saga 3" to TvdbOverrideEntry(tvdbSeason = 3, seasonOrderType = "dvd"),
            "saga 4" to TvdbOverrideEntry(tvdbSeason = 4, seasonOrderType = "dvd"),
            "saga 5" to TvdbOverrideEntry(tvdbSeason = 5, seasonOrderType = "dvd"),
            "saga 6" to TvdbOverrideEntry(tvdbSeason = 6, seasonOrderType = "dvd"),
            "saga 7" to TvdbOverrideEntry(tvdbSeason = 7, seasonOrderType = "dvd"),
            "saga 8" to TvdbOverrideEntry(tvdbSeason = 8, seasonOrderType = "dvd"),
            "saga 9" to TvdbOverrideEntry(tvdbSeason = 9, seasonOrderType = "dvd"),
            "saga 10" to TvdbOverrideEntry(tvdbSeason = 10, seasonOrderType = "dvd"),
            "saga 11" to TvdbOverrideEntry(tvdbSeason = 11, seasonOrderType = "dvd"),
            "saga 12" to TvdbOverrideEntry(tvdbSeason = 12, seasonOrderType = "dvd"),

            // === Kai (mêmes ordres DVD que les sagas correspondantes) ===
            "kai - saga 1" to TvdbOverrideEntry(tvdbSeason = 1, seasonOrderType = "dvd"),
            "kai - saga 2" to TvdbOverrideEntry(tvdbSeason = 2, seasonOrderType = "dvd"),
            "kai - saga 3" to TvdbOverrideEntry(tvdbSeason = 3, seasonOrderType = "dvd"),
            "kai - saga 4" to TvdbOverrideEntry(tvdbSeason = 4, seasonOrderType = "dvd"),
            "kai - saga 5" to TvdbOverrideEntry(tvdbSeason = 5, seasonOrderType = "dvd"),
            "kai - saga 6" to TvdbOverrideEntry(tvdbSeason = 6, seasonOrderType = "dvd"),
            "kai - saga 7" to TvdbOverrideEntry(tvdbSeason = 7, seasonOrderType = "dvd"),
            "kai - saga 8" to TvdbOverrideEntry(tvdbSeason = 8, seasonOrderType = "dvd"),
            "kai - saga 9" to TvdbOverrideEntry(tvdbSeason = 9, seasonOrderType = "dvd"),
            "kai - saga 10" to TvdbOverrideEntry(tvdbSeason = 10, seasonOrderType = "dvd"),
            "kai - saga 11" to TvdbOverrideEntry(tvdbSeason = 11, seasonOrderType = "dvd"),

            // === Spéciaux & Spin-offs One Piece ===
            "log" to TvdbOverrideEntry(tvdbSeason = 15, seasonOrderType = "official"),
            "heroines" to TvdbOverrideEntry(seriesId = 466748L, tvdbSeason = 1, seasonOrderType = "official"),
            "heroine" to TvdbOverrideEntry(seriesId = 466748L, tvdbSeason = 1, seasonOrderType = "official"),
            "fan letter" to TvdbOverrideEntry(seriesId = 361195L, isMovie = true),

            // === Films → skipTvdb : conserver le thumbnail de la série ===
            "films" to TvdbOverrideEntry(skipTvdb = true),
        ),
    ),
)

/**
 * High-level helper function for extensions to fetch TVDB metadata for any panel/tab.
 */
suspend fun Source.fetchTvdbForPanel(
    seriesTitle: String,
    rawSeasonName: String?,
    fullTitle: String,
    titles: Set<String> = emptySet(),
    isMovie: Boolean = false,
    isSpecial: Boolean = false,
    preference: MediaPreference = MediaPreference.ANIME,
): TvdbMetadata? {
    val allLookupTitles = (listOf(seriesTitle, fullTitle) + titles).map { it.normalize() }
    val seriesOverride = tvdbOverridesMap.entries.find { entry ->
        val normKey = entry.key.normalize()
        allLookupTitles.any { it.contains(normKey) }
    }?.value

    val allSeasonLookups = listOfNotNull(rawSeasonName, fullTitle, seriesTitle).map { it.normalize() }
    // Match override keys longest-first and ensure digit-boundary check to avoid "saga 1" matching "saga 10/11/12"
    val overrideEntry = seriesOverride?.seasons?.entries
        ?.sortedByDescending { it.key.length }
        ?.find { entry ->
            val normKey = entry.key.normalize()
            allSeasonLookups.any { lookup ->
                val idx = lookup.indexOf(normKey)
                if (idx >= 0) {
                    val afterIdx = idx + normKey.length
                    afterIdx >= lookup.length || !lookup[afterIdx].isDigit()
                } else {
                    false
                }
            }
        }?.value

    // skipTvdb: panel explicitement marqué pour conserver les métadonnées de la source
    if (overrideEntry?.skipTvdb == true) return null

    val calculatedSeasonNumber = if (isSpecial) 0 else (rawSeasonName?.let { extractSeasonNumber(it) } ?: 1)
    val seasonNumber = overrideEntry?.tvdbSeason ?: calculatedSeasonNumber
    val episodeOffset = overrideEntry?.episodeOffset ?: 0
    val seasonOrderType = overrideEntry?.seasonOrderType ?: "official"
    val targetTvdbId = overrideEntry?.seriesId ?: seriesOverride?.seriesId
    val isMovieResolved = overrideEntry?.isMovie ?: isMovie

    // 1. Try Primary Titles first (seriesTitle & fullTitle)
    val primaryTitles = listOf(sanitizeTitle(seriesTitle), sanitizeTitle(fullTitle))
        .filter { it.isNotBlank() }
        .distinct()

    val primaryResults = primaryTitles.mapNotNull { title ->
        fetchTvdbMetadata(
            title,
            seasonNumber,
            isMovie = isMovieResolved,
            preference = preference,
            seasonOrderType = seasonOrderType,
            targetTvdbId = targetTvdbId,
        )
    }

    val bestPrimary = primaryResults.maxByOrNull { it.matchScore }
    if (targetTvdbId != null || (bestPrimary != null && bestPrimary.matchScore >= 60)) {
        return bestPrimary?.copy(episodeOffset = episodeOffset)
    }

    // 2. Fallback to Secondary Titles only if primary score is < 60 and no explicit targetTvdbId
    val minLen = if (seriesTitle.length <= 4) seriesTitle.length else 4
    val secondaryTitles = titles.map { sanitizeTitle(it) }
        .filter { it.isNotBlank() && it.length >= minLen && !primaryTitles.contains(it) }
        .distinct()

    val secondaryResults = secondaryTitles.take(5).mapNotNull { title ->
        fetchTvdbMetadata(
            title,
            seasonNumber,
            isMovie = isMovieResolved,
            preference = preference,
            seasonOrderType = seasonOrderType,
            targetTvdbId = targetTvdbId,
        )
    }

    val allCandidates = primaryResults + secondaryResults
    val result = allCandidates.maxByOrNull { it.matchScore } ?: bestPrimary

    return result?.copy(episodeOffset = episodeOffset)
}
