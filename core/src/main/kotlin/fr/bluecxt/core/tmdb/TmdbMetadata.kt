// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.tmdb

/**
 * Cleaned metadata model consumed by extensions.
 *
 * @property summary Global synopsis/overview for the media or season.
 * @property releaseDate Initial release date (YYYY-MM-DD format).
 * @property mainPosterUrl High-resolution poster URL for the main media entry (w500 format).
 * @property seasonPosterUrl Poster URL specific to the requested season (if available).
 * @property posterUrl Default poster URL (retained for backward compatibility).
 * @property author Creators, mangakas, or writers.
 * @property artist Animation studios or production companies.
 * @property status Airing/publication status (e.g. SAnime.COMPLETED, SAnime.ONGOING, SAnime.UNKNOWN).
 * @property genre Comma-separated list of associated genres.
 * @property episodeSummaries Map indexed by episode number (Int) containing a [Triple]:
 *   - `first` (String?): **Episode title** (`name` or `title`).
 *   - `second` (String?): **Episode thumbnail URL** (`stillPath` or `backdrop` in w500 format).
 *   - `third` (String?): **Episode synopsis/overview** (`overview` or `summary`).
 * @property episodeOffset Episode numbering offset to chain consecutive seasons.
 * @property tmdbId Unique numeric TMDB identifier.
 * @property type TMDB entry type ("tv" or "movie").
 */
data class TmdbMetadata(
    val summary: String?,
    val releaseDate: String?,
    val mainPosterUrl: String?,
    val seasonPosterUrl: String?,
    @Deprecated(
        message = "Use mainPosterUrl for an anime's main poster or seasonPosterUrl for season listings.",
        replaceWith = ReplaceWith("mainPosterUrl"),
    )
    val posterUrl: String? = seasonPosterUrl ?: mainPosterUrl,
    val author: String?,
    val artist: String?,
    val status: Int,
    val genre: String? = null,
    val episodeSummaries: Map<Int, Triple<String?, String?, String?>>,
    val episodeOffset: Int = 0,
    val tmdbId: Int? = null,
    val type: String? = null,
)
