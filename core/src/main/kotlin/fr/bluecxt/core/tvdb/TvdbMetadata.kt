// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.tvdb

/**
 * Cleaned metadata model consumed by extensions for TVDB.
 *
 * @property title Canonical title of the series or media.
 * @property summary Global synopsis/overview for the media or season.
 * @property releaseDate Initial release date (YYYY-MM-DD format).
 * @property mainPosterUrl High-resolution poster URL for the main media entry.
 * @property seasonPosterUrl Poster URL specific to the requested season (if available).
 * @property backdropUrl High-resolution backdrop/fanart URL for the media.
 * @property author Creators, mangakas, or writers.
 * @property artist Animation studios or production networks/companies.
 * @property status Airing/publication status (e.g. SAnime.COMPLETED, SAnime.ONGOING, SAnime.UNKNOWN).
 * @property genre Comma-separated list of associated genres.
 * @property episodeSummaries Map indexed by episode number (Int) containing [TvdbEpisode] metadata.
 * @property episodeOffset Episode numbering offset to chain consecutive seasons.
 * @property matchScore Fuzzy matching score used during series discovery.
 * @property seasonEpisodeCounts Map associating each season number to its total episode count.
 */
data class TvdbMetadata(
    val title: String? = null,
    val summary: String?,
    val releaseDate: String?,
    val mainPosterUrl: String?,
    val seasonPosterUrl: String?,
    val backdropUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: Int = 0,
    val genre: String? = null,
    val episodeSummaries: Map<Int, TvdbEpisode>,
    val episodeOffset: Int = 0,
    val matchScore: Int = 0,
    val seasonEpisodeCounts: Map<Int, Int> = emptyMap(),
)

/**
 * Detailed episode metadata returned by TVDB.
 *
 * @property title Name or title of the episode.
 * @property thumbnail URL pointing to the episode's screenshot/still image.
 * @property summary Synopsis/overview describing the episode.
 */
data class TvdbEpisode(
    val title: String? = null,
    val thumbnail: String? = null,
    val summary: String? = null,
) {
    @Deprecated("Use title instead", ReplaceWith("title"))
    val first: String? get() = title

    @Deprecated("Use thumbnail instead", ReplaceWith("thumbnail"))
    val second: String? get() = thumbnail

    @Deprecated("Use summary instead", ReplaceWith("summary"))
    val third: String? get() = summary
}
