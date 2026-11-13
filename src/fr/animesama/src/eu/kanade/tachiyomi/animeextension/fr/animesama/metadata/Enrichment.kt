// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.metadata

import eu.kanade.tachiyomi.animeextension.fr.animesama.model.ContentType
import eu.kanade.tachiyomi.animeextension.fr.animesama.model.MediaUrl
import eu.kanade.tachiyomi.animeextension.fr.animesama.naming.SeasonTitles
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.HubInfo
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.SeasonRef
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.SitePaths
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.source.Source
import fr.bluecxt.core.tvdb.TvdbMetadata
import fr.bluecxt.core.tvdb.utils.fetchTvdbForPanel
import keiyoushi.utils.toJsonString

/**
 * Enriches a work or season entry with the work page metadata and TVDB.
 *
 * The site is the only source for the season list and for the French synopsis, while TVDB is
 * the only source for episode titles, the release date and HD artwork. Neither is complete, so
 * every field has an explicit fallback order rather than a single source.
 *
 * @param anime entry to update in place.
 * @param hub metadata read from the work page.
 * @param tvdb metadata of the season, or `null` when TVDB has no match.
 * @param seasonLabel season label of the entry, `null` for a hub entry.
 * @param isHub `true` when the entry lists several seasons.
 * @return the updated entry.
 */
internal fun SAnime.enrichFromHub(
    hub: HubInfo,
    tvdb: TvdbMetadata?,
    seasonLabel: String?,
    isHub: Boolean,
): SAnime = apply {
    val singleSeason = seasonLabel != null && !isHub
    fillFromSite(hub)
    fillFromTvdb(tvdb)
    applyArtwork(hub, tvdb, singleSeason)
    applySummary(hub, tvdb, singleSeason)
}

/**
 * Fills the fields the work page states, without overriding anything already set.
 *
 * @param hub metadata read from the work page.
 */
private fun SAnime.fillFromSite(hub: HubInfo) {
    if (author.isNullOrEmpty()) author = hub.studio.orEmpty()
    if (genre.isNullOrEmpty() && hub.genres.isNotEmpty()) genre = hub.genres.joinToString(", ")
    if (hub.status != SAnime.UNKNOWN) status = hub.status
}

/**
 * Fills the fields TVDB knows better, still without overriding a field that already has a value.
 *
 * @param tvdb metadata of the season, or `null`.
 */
private fun SAnime.fillFromTvdb(tvdb: TvdbMetadata?) {
    if (tvdb == null) return
    if (artist.isNullOrBlank()) artist = tvdb.artist
    if (author.isNullOrBlank()) author = tvdb.author
    if (genre.isNullOrBlank()) genre = tvdb.genre
    if (status == SAnime.UNKNOWN) status = tvdb.status
}

/**
 * Chooses the poster and the backdrop.
 *
 * A single-season entry prefers the artwork of its own season, which usually differs from the
 * poster of the whole work.
 *
 * @param hub metadata read from the work page.
 * @param tvdb metadata of the season, or `null`.
 * @param singleSeason whether the entry covers a single season.
 */
private fun SAnime.applyArtwork(hub: HubInfo, tvdb: TvdbMetadata?, singleSeason: Boolean) {
    val tvdbPoster = if (singleSeason) tvdb?.seasonPosterUrl ?: tvdb?.mainPosterUrl else tvdb?.mainPosterUrl
    val poster = tvdbPoster ?: hub.posterUrl
    val backdrop = tvdb?.backdropUrl ?: hub.backdropUrl ?: tvdb?.mainPosterUrl

    if (poster != null) thumbnail_url = poster
    if (backdrop != null) background_url = backdrop
}

/**
 * Chooses the description.
 *
 * TVDB wins for a single-season entry or when the site has no synopsis; the site wins otherwise,
 * because it is the only French source.
 *
 * @param hub metadata read from the work page.
 * @param tvdb metadata of the season, or `null`.
 * @param singleSeason whether the entry covers a single season.
 */
private fun SAnime.applySummary(hub: HubInfo, tvdb: TvdbMetadata?, singleSeason: Boolean) {
    val year = tvdb?.releaseDate ?: hub.year
    val tvdbSummary = tvdb?.summary
    val siteSummary = hub.synopsis
    val preferredTvdbSummary = tvdbSummary?.takeIf { (it.isNotBlank() && singleSeason) || siteSummary.isBlank() }
    val summary = preferredTvdbSummary ?: siteSummary
    val dedicatedSeasonSummary = singleSeason && !tvdbSummary.isNullOrBlank()

    if (description.isNullOrEmpty() || dedicatedSeasonSummary) {
        description = withReleaseDate(summary, year)
    }
}

/**
 * Builds the library entry of one season of a work.
 *
 * @param seriesTitle title of the work, used for the TVDB match and the display title.
 * @param series entry of the work, used as a fallback for the artwork.
 * @param hub metadata read from the work page.
 * @param season season declared by the work page.
 * @param titles every title known for the work, used for the TVDB match.
 */
internal suspend fun Source.buildSeasonAnime(
    seriesTitle: String,
    series: SAnime,
    hub: HubInfo,
    season: SeasonRef,
    titles: Set<String>,
): SAnime {
    val fullTitle = SeasonTitles.format(seriesTitle, season.label, titles)
    val contentType = ContentType.from(season.label.ifBlank { fullTitle }, season.path)
    val isMovie = contentType == ContentType.MOVIE
    val isSpecial = contentType == ContentType.SPECIAL || contentType == ContentType.OAV
    val tmdbId = hub.tmdb?.takeIf { it.isMovie == isMovie }?.id

    val tvdb = fetchTvdbForPanel(
        seriesTitle = seriesTitle,
        rawSeasonName = season.label,
        fullTitle = fullTitle,
        titles = titles,
        isMovie = isMovie,
        isSpecial = isSpecial,
        externalTmdbId = tmdbId,
    )

    return SAnime.create().apply {
        title = fullTitle
        url = MediaUrl(
            url = season.path,
            titles = titles,
            season = season.label,
            lang = SitePaths.language(season.path),
        ).toJsonString(json)
        thumbnail_url = tvdb?.seasonPosterUrl ?: tvdb?.mainPosterUrl ?: hub.posterUrl ?: series.thumbnail_url
        description = withReleaseDate(tvdb?.summary ?: hub.synopsis, tvdb?.releaseDate)
        author = tvdb?.author ?: hub.studio
        artist = tvdb?.artist ?: hub.studio
        genre = tvdb?.genre ?: hub.genres.joinToString(", ").takeIf { it.isNotBlank() }
        status = tvdb?.status?.takeIf { it != SAnime.UNKNOWN } ?: hub.status
        initialized = true
        fetch_type = FetchType.Episodes
        season_number = HUB_SEASON_NUMBER
        background_url = tvdb?.backdropUrl ?: hub.backdropUrl
    }
}

/**
 * Prefixes a summary with its release date, when known.
 *
 * @param summary description to prefix.
 * @param year release date or year, or `null`.
 */
internal fun withReleaseDate(summary: String, year: String?): String {
    if (year.isNullOrBlank()) return summary
    return "Date de sortie : $year\n\n$summary"
}
