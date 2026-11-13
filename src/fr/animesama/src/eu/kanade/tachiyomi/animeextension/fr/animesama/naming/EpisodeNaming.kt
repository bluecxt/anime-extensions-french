// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.naming

import eu.kanade.tachiyomi.animeextension.fr.animesama.model.ContentType
import fr.bluecxt.core.tvdb.TvdbEpisode
import fr.bluecxt.core.tvdb.TvdbMetadata

/**
 * Name of one episode, split so the prefix can be chosen independently of the label.
 *
 * @property prefix prefix such as `[Film] ` or `[OAV] `.
 * @property base label itself, for example `Épisode 3 - Le retour`.
 */
internal data class EpisodeName(
    val prefix: String,
    val base: String,
) {
    /** Prefix and label joined, without surrounding blanks. */
    val value: String get() = (prefix + base).trim()
}

/**
 * Builds episode labels from the data the site provides and the metadata TVDB provides.
 *
 * The site only numbers episodes and never names them, so the title always comes from TVDB when
 * it is available. A movie is a season page holding a single entry, which is why the prefix is
 * decided per episode instead of per season.
 */
internal object EpisodeNaming {
    private val episodeBasicRegex = Regex("""(?i)^(?:Épisode|Episode)\s*\d+$""")
    private val episodeWithTitleRegex = Regex("""(?i)^(?:Épisode|Episode)\s*\d+\s*[-:]\s*(.+)""")
    private val moviePrefixCleanRegex = Regex("""(?i)^(?:\[Films?]\s*|Films?\s*\d*\s*[:-]?\s*)""")
    private const val MOVIE_SEASON_SEPARATOR = "Film -"

    /**
     * Chooses the TVDB entry backing an episode.
     *
     * When the site lists more episodes than TVDB knows for a season, the extra ones belong to
     * the season zero (OAV) of the same work: this is the only case where an episode is
     * prefixed as an OAV.
     *
     * @param episodeNumber number as listed by the site, starting at 1.
     * @param contentType kind of the current season.
     * @param tvdb metadata of the current season, or `null`.
     * @param seasonZero metadata of the season zero, or `null`.
     * @return the prefix to force, if any, and the TVDB episode to use.
     */
    fun resolveTvdbEpisode(
        episodeNumber: Int,
        contentType: ContentType,
        tvdb: TvdbMetadata?,
        seasonZero: TvdbMetadata?,
    ): Pair<String?, TvdbEpisode?> {
        val offset = tvdb?.episodeOffset ?: 0
        val seasonEpisodeCount = tvdb?.episodeSummaries?.size ?: 0
        val episode = tvdb?.episodeSummaries?.get(episodeNumber + offset)

        val overflow = episode == null &&
            contentType == ContentType.SEASON &&
            seasonEpisodeCount > 0 &&
            episodeNumber > seasonEpisodeCount &&
            seasonZero != null
        if (!overflow) return null to episode

        val seasonZeroOffset = seasonZero.episodeOffset
        val seasonZeroEpisode = seasonZero.episodeSummaries[episodeNumber - seasonEpisodeCount + seasonZeroOffset]
        return if (seasonZeroEpisode != null) "[OAV] " to seasonZeroEpisode else null to null
    }

    /**
     * Builds the name of one episode.
     *
     * @param episodeNumber number as listed by the site, starting at 1.
     * @param contentType kind of the current season.
     * @param totalEpisodes number of episodes the site lists for the season.
     * @param animeTitle title of the work, last resort for a movie without any other title.
     * @param seasonLabel season label as written by the site.
     * @param seriesTitle series title returned by TVDB.
     * @param episodeTitle episode title returned by TVDB.
     * @param prefixOverride prefix chosen by [resolveTvdbEpisode], when it applies.
     */
    fun build(
        episodeNumber: Int,
        contentType: ContentType,
        totalEpisodes: Int,
        animeTitle: String,
        seasonLabel: String?,
        seriesTitle: String?,
        episodeTitle: String?,
        prefixOverride: String? = null,
    ): EpisodeName {
        if (contentType == ContentType.MOVIE) {
            val isCollection = totalEpisodes > 1
            val title = movieTitle(seriesTitle, seasonLabel, animeTitle)
            val base = if (isCollection) "$title $episodeNumber" else title
            return EpisodeName(prefix = if (isCollection) "[Film] " else "", base = base)
        }

        val base = when {
            episodeTitle.isNullOrBlank() -> "Épisode $episodeNumber"

            contentType == ContentType.SPECIAL -> "Épisode $episodeNumber"

            episodeTitle.matches(episodeBasicRegex) -> "Épisode $episodeNumber"

            else -> {
                val realTitle = episodeWithTitleRegex.find(episodeTitle)?.groupValues?.get(1)?.trim()
                when {
                    realTitle.isNullOrBlank() -> "Épisode $episodeNumber - $episodeTitle"
                    else -> "Épisode $episodeNumber - $realTitle"
                }
            }
        }

        return EpisodeName(prefix = prefixOverride ?: contentType.prefix, base = base)
    }

    /**
     * Picks the title of a movie, which the site never states as an episode title.
     *
     * Sources, in order: the TVDB series title, the label after `Film -` when the site writes
     * one, then the work title.
     */
    private fun movieTitle(seriesTitle: String?, seasonLabel: String?, animeTitle: String): String {
        val fromLabel = seasonLabel
            ?.substringAfter(MOVIE_SEASON_SEPARATOR, "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("Film", ignoreCase = true) }
        val raw = seriesTitle?.takeIf { it.isNotBlank() } ?: fromLabel ?: animeTitle
        return raw.replace(moviePrefixCleanRegex, "").trim().ifBlank { "Film" }
    }
}
