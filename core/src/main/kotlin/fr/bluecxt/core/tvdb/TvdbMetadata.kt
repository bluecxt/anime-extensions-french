package fr.bluecxt.core.tvdb

/**
 * Cleaned metadata model consumed by extensions for TVDB.
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
    val episodeSummaries: Map<Int, Triple<String?, String?, String?>>,
    val episodeOffset: Int = 0,
    val matchScore: Int = 0,
)
