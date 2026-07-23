package fr.bluecxt.core.tmdb.utils

import fr.bluecxt.core.Source
import fr.bluecxt.core.tmdb.TmdbMetadata
import fr.bluecxt.core.tmdb.fetchTmdbMetadata
import fr.bluecxt.core.tmdb.fetchTmdbMovieMetadata

enum class PanelMediaType {
    MOVIE,
    SPIN_OFF,
    SEASON,
}

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
    val seasonNumber = rawSeasonName?.let { extractSeasonNumber(it) } ?: 1
    val isMovie = rawSeasonName?.contains("Film", ignoreCase = true) == true || rawSeasonName?.contains("Movie", ignoreCase = true) == true
    val isSpinOff = rawSeasonName != null && extractSeasonNumber(rawSeasonName) == null && !isMovie

    val mediaType = when {
        isMovie -> PanelMediaType.MOVIE
        isSpinOff -> PanelMediaType.SPIN_OFF
        else -> PanelMediaType.SEASON
    }

    return when (mediaType) {
        PanelMediaType.MOVIE -> {
            fetchTmdbMovieMetadata(seriesTitle)
                ?: fetchTmdbMovieMetadata(fullTitle)
                ?: titles.firstNotNullOfOrNull { fetchTmdbMovieMetadata(it) }
        }

        PanelMediaType.SPIN_OFF -> {
            val spinOffName = rawSeasonName.orEmpty().trim()
            fetchTmdbMetadata(spinOffName, 1)
                ?: fetchTmdbMetadata(fullTitle, 1)
                ?: titles.firstNotNullOfOrNull { fetchTmdbMetadata(it, seasonNumber) }
                ?: fetchTmdbMetadata(seriesTitle, seasonNumber)
        }

        PanelMediaType.SEASON -> {
            titles.firstNotNullOfOrNull { fetchTmdbMetadata(it, seasonNumber) }
                ?: fetchTmdbMetadata(seriesTitle, seasonNumber)
        }
    }
}
