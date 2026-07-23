package fr.bluecxt.core.tmdb

/**
 * Cleaned metadata model consumed by extensions.
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
)
