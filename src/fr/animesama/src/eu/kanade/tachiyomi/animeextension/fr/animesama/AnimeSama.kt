// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.animesama.legacy.getLegacyAnimeDetails
import eu.kanade.tachiyomi.animeextension.fr.animesama.legacy.getLegacyEpisodeList
import eu.kanade.tachiyomi.animeextension.fr.animesama.legacy.getLegacyHosterList
import eu.kanade.tachiyomi.animeextension.fr.animesama.legacy.getLegacySeasonList
import eu.kanade.tachiyomi.animeextension.fr.animesama.legacy.getLegacyVideoList
import eu.kanade.tachiyomi.animeextension.fr.animesama.metadata.buildSeasonAnime
import eu.kanade.tachiyomi.animeextension.fr.animesama.metadata.enrichFromHub
import eu.kanade.tachiyomi.animeextension.fr.animesama.model.ContentType
import eu.kanade.tachiyomi.animeextension.fr.animesama.model.LinkSet
import eu.kanade.tachiyomi.animeextension.fr.animesama.model.MediaUrl
import eu.kanade.tachiyomi.animeextension.fr.animesama.model.PlayerLink
import eu.kanade.tachiyomi.animeextension.fr.animesama.model.StoredPlayer
import eu.kanade.tachiyomi.animeextension.fr.animesama.naming.EpisodeNaming
import eu.kanade.tachiyomi.animeextension.fr.animesama.naming.SeasonTitles
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.BddScript
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.BddScriptParser
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.CatalogueCard
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.CataloguePage
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.HubInfo
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.HubPage
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.LanguageRef
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.LinkResolver
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.PageLoader
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.SeasonPage
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.SeasonRef
import eu.kanade.tachiyomi.animeextension.fr.animesama.site.SitePaths
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.HttpException
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.extractors.router.getVideoServer
import fr.bluecxt.core.filters.FilterSpec
import fr.bluecxt.core.model.ContentUnavailableException
import fr.bluecxt.core.model.VoiceLanguage.VA
import fr.bluecxt.core.model.VoiceLanguage.VF
import fr.bluecxt.core.model.VoiceLanguage.VOSTFR
import fr.bluecxt.core.model.VoiceLanguage.VQF
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportEpisodeIssues
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportHosterIssues
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportIncompleteness
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportSeasonIssues
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportVideoIssues
import fr.bluecxt.core.source.ConfigurableSource
import fr.bluecxt.core.tvdb.TvdbMetadata
import fr.bluecxt.core.tvdb.fetchTvdbMetadata
import fr.bluecxt.core.tvdb.utils.fetchTvdbForPanel
import fr.bluecxt.core.utils.JsoupExtensions
import fr.bluecxt.core.utils.safeHttpCode
import keiyoushi.core.R
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.get
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parallelMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Anime-Sama source.
 *
 * The site exposes four page shapes, each handled by a dedicated parser in `site`:
 *  - the catalogue and the "latest episodes" feed, which list works;
 *  - the work page ("hub"), which holds the metadata and the seasons the site declares;
 *  - a season page, which holds the languages the season is available in;
 *  - a season page in one language, which holds the episode script (`bdd`).
 *
 * Since November 2026 a season page no longer carries embed URLs: it stores `<id>` references,
 * resolved on demand by `/api/lien.php`. Links are therefore resolved when a video is started,
 * never while listing episodes, which keeps a season at one request per language instead of one
 * request per link.
 *
 * Entries stored before the JSON URL format are still readable through `LegacyAnimeSama`.
 */
class AnimeSama :
    ConfigurableSource(),
    JsoupExtensions {

    override val name = "Anime-Sama"

    override val defaultBaseUrl = "https://anime-sama.to"

    override val lang = "fr"

    override val supportsLatest = true

    override val defaultServer = "Vidmoly"

    /**
     * Servers the site is known to serve.
     *
     * Routing is done on the resolved embed URL, so a server missing from this list is simply
     * never extracted; the names must match the shared router keys.
     */
    override val supportedServers = listOf(
        "Vidmoly",
        "Embed4me",
        "Minochinos",
        "Sibnet",
        "Sendvid",
        "Uqload",
        "Voe",
        "Vidoza",
        "Lulu",
        "Filemoon",
        "Okru",
        "Dood",
    )

    /** Languages the site can expose; a season rarely offers more than three. */
    override val supportedVoices = setOf(VOSTFR, VF, VA, VQF)

    /**
     * Adds the "Early Fetch" switch to the extension settings.
     *
     * Some clients (Anikku among them) only read `fetch_type` while listing and never refresh it
     * afterwards, so a work whose hub is only detected in [getAnimeDetails] shows no season at
     * all. The switch resolves the fetch type from the catalogue for those clients.
     *
     * @param screen settings screen provided by the client.
     */
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        screen.addSwitchPreference(
            key = PREF_EARLY_FETCH_TYPE_KEY,
            default = PREF_EARLY_FETCH_TYPE_DEFAULT,
            title = getString(R.string.pref_early_fetch_type_title),
            summary = getString(R.string.pref_early_fetch_type_summary),
        )
    }

    /**
     * Whether the fetch type must be resolved while listing.
     *
     * Costs one work page per catalogue card, which is why it stays opt-in.
     */
    private val useEarlyFetch: Boolean
        get() = preferences.getBoolean(PREF_EARLY_FETCH_TYPE_KEY, PREF_EARLY_FETCH_TYPE_DEFAULT)

    /** Pages already loaded during this session, shared by every override. */
    private val pages = PageLoader(::fetchDocument)

    /** Resolves stored references into embed URLs, renewing an expired token once. */
    private val linkResolver = LinkResolver(::callLinkApi, ::freshToken)

    /** Router name of each hostname already seen, avoiding a lookup per link. */
    private val serverNames = ConcurrentHashMap<String, String>()

    // ============================== Catalogue ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = cataloguePage(pages.load("/catalogue/?page=$page"), page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = cataloguePage(pages.load("/nouveautes/?type=episodes&page=$page"), page)

    override val customFilters: List<FilterSpec>
        get() = listOf(
            select("Tri", "tri", AnimeSamaFilters.TRI_OPTIONS),
            group("Type", "type[]", AnimeSamaFilters.TYPE_OPTIONS),
            group("Langage", "langue[]", AnimeSamaFilters.LANGUAGE_OPTIONS),
            group("Statut", "current[]", AnimeSamaFilters.STATUT_OPTIONS),
            fold("Année") {
                text("De", "annee_min")
                text("À", "annee_max")
            },
            fold("Épisodes") {
                text("Min", "episodes_min")
                text("Max", "episodes_max")
            },
            group("Genre", "genre[]", AnimeSamaFilters.GENRE_OPTIONS),
        )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val term = query.trim()
        if (term.startsWith(PREFIX_SEARCH)) return openWork(term.removePrefix(PREFIX_SEARCH).trim())

        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder().apply {
            if (term.isNotBlank()) addQueryParameter("search", term)
            addQueryParameter("page", "$page")
            applyFilters(filters)
        }.build()

        return cataloguePage(pages.load(url.toString()), page)
    }

    /**
     * Opens a work directly, used by the deep link activity.
     *
     * The work page itself does not list the work as a card, so the entry is built from the page
     * metadata instead of being read from the catalogue cards.
     *
     * @param slug slug of the work, as found in `/catalogue/<slug>`.
     * @return a single result, or an empty page when the work page cannot be loaded.
     */
    private suspend fun openWork(slug: String): AnimesPage {
        val hubPath = SitePaths.hubPath("/catalogue/$slug")
        val document = pages.load(hubPath) ?: return AnimesPage(emptyList(), false)
        val hub = HubPage.parse(document, hubPath)
        val anime = SAnime.create().apply {
            title = hub.title?.takeIf { it.isNotBlank() } ?: slug
            thumbnail_url = hub.posterUrl
            url = MediaUrl(url = hubPath, titles = setOfNotNull(hub.title)).toJsonString(json)
            // The work page is already loaded, so the fetch type costs nothing here.
            if (hub.seasons.size > 1) {
                fetch_type = FetchType.Seasons
                season_number = HUB_SEASON_NUMBER
            }
        }
        return AnimesPage(listOf(anime), false)
    }

    /**
     * Rebuilds the absolute URL of an entry.
     *
     * Legacy entries do not hold JSON: their anchor is stripped so the URL still points at a
     * real page, which matters for the web search and the error reports.
     */
    override fun getAnimeUrl(anime: SAnime): String {
        val path = anime.storedMedia()?.url ?: anime.url.substringBefore("#").takeIf { it.startsWith("/") }
        return "$baseUrl${path.orEmpty()}"
    }

    // =========================== Anime details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val stored = anime.storedMedia() ?: return getLegacyAnimeDetails(anime)
        val hub = loadHub(stored.url)
        val target = resolveTarget(stored, hub)
        val seasonLabel = target?.label ?: stored.season
        val titles = knownTitles(stored, hub)
        val seriesTitle = hub.title?.takeIf { it.isNotBlank() } ?: anime.title
        val isHub = target == null && hub.seasons.size > 1

        anime.fetch_type = if (isHub) FetchType.Seasons else FetchType.Episodes
        if (isHub) anime.season_number = HUB_SEASON_NUMBER

        val tvdb = tvdbFor(
            seriesTitle = seriesTitle,
            seasonLabel = seasonLabel,
            fullTitle = SeasonTitles.format(seriesTitle, seasonLabel.orEmpty(), titles),
            titles = titles,
            path = stored.url,
            tmdbId = hub.tmdb?.takeIf { it.isMovie == isMovie(stored.url, seasonLabel) }?.id,
        )

        return anime.enrichFromHub(hub, tvdb, seasonLabel, isHub)
            .checkAndReportIncompleteness(baseUrl, ::getAnimeUrl, currentName)
    }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val stored = anime.storedMedia() ?: return emptyList()
        val document = pages.load(SitePaths.hubPath(stored.url)) ?: return emptyList()
        val earlyFetch = useEarlyFetch
        return CataloguePage.entries(document).parallelMap { card -> card.toAnime(earlyFetch) }
    }

    // ============================== Seasons ===============================

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val stored = anime.storedMedia() ?: return getLegacySeasonList(anime)
        val hub = loadHub(stored.url)
        if (hub.seasons.isEmpty()) return emptyList()

        val seriesTitle = hub.title?.takeIf { it.isNotBlank() } ?: anime.title
        val titles = knownTitles(stored, hub)

        return hub.seasons
            .parallelMap { season -> buildSeasonAnime(seriesTitle, anime, hub, season, titles) }
            .checkAndReportSeasonIssues(baseUrl, anime.title, ::getAnimeUrl)
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val stored = anime.storedMedia() ?: return getLegacyEpisodeList(anime)
        val hub = loadHub(stored.url)
        // A work with several seasons has no episode of its own until a season is chosen. Some
        // clients call this before (or while) getAnimeDetails runs, so the fetch type is not set
        // yet and the season list is never requested: answering with an empty list makes them
        // show an error, so a single explanatory episode is returned instead.
        val target = resolveTarget(stored, hub)
            ?: return if (hub.seasons.size > 1) listOf(hubPlaceholder()) else emptyList()

        val seasonPath = SitePaths.seasonPath(target.path)
        val primaryLanguage = SitePaths.language(target.path) ?: stored.lang ?: DEFAULT_LANGUAGE
        val primaryDocument = pages.load(SitePaths.seasonUrl(seasonPath, primaryLanguage)) ?: return emptyList()
        val primaryInfo = SeasonPage.parse(primaryDocument)

        // A season that is not out yet answers `200` with no `bdd` script: there is simply
        // nothing to list, which is not a network error.
        if (primaryInfo.bdd == null) return emptyList()

        val languages = primaryInfo.languages.ifEmpty {
            listOf(LanguageRef(primaryLanguage, primaryLanguage.uppercase()))
        }
        val tracks = languages.parallelMap { language -> language to loadScript(seasonPath, language.slug) }
        val episodeCount = tracks.maxOfOrNull { (_, script) -> script?.episodeCount ?: 0 } ?: 0
        if (episodeCount == 0) return emptyList()

        val seasonLabel = target.label.ifBlank { primaryInfo.seasonLabel.orEmpty() }
        val seriesTitle = hub.title?.takeIf { it.isNotBlank() } ?: anime.title
        val titles = knownTitles(stored, hub)
        val tvdb = tvdbFor(
            seriesTitle = seriesTitle,
            seasonLabel = seasonLabel,
            fullTitle = SeasonTitles.format(seriesTitle, seasonLabel, titles),
            titles = titles,
            path = target.path,
            tmdbId = hub.tmdb?.takeIf { it.isMovie == isMovie(target.path, seasonLabel) }?.id,
        )
        val seasonZero = loadSeasonZero(seriesTitle, tvdb, episodeCount)

        return buildEpisodes(
            animeTitle = seriesTitle,
            seasonPath = seasonPath,
            seasonLabel = seasonLabel,
            targetPath = target.path,
            tracks = tracks,
            episodeCount = episodeCount,
            tvdb = tvdb,
            seasonZero = seasonZero,
        )
            .reversed()
            .checkAndReportEpisodeIssues(baseUrl, seasonPath, anime.title)
    }

    // ============================== Hosters ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val stored = episode.storedLinks() ?: return getLegacyHosterList(episode)

        // One hoster per language, as everywhere else in the repo: picking a server is the
        // video list's job, and the router already labels each resolved video with its server.
        val hosters = stored.links
            .groupBy { it.lang }
            .map { (language, links) ->
                Hoster(
                    hosterName = language.uppercase(),
                    internalData = LinkSet(stored.seasonPath, links).toJsonString(json),
                )
            }
            .sortHosters()

        return hosters.checkAndReportHosterIssues(baseUrl, stored.seasonPath, episode.name)
    }

    // ============================== Videos ===============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val stored = hoster.internalData.toLinkSet(json)
            ?: hoster.internalData.toStoredPlayers(json)
            ?: return getLegacyVideoList(hoster)

        val videos = stored.links
            .parallelMap { link ->
                val url = linkResolver.resolve(stored.seasonPath, link) ?: return@parallelMap emptyList()
                extractVideos(url, link.lang.uppercase(), supportedServers)
            }
            .flatten()
            .sortVideos()

        return videos.checkAndReportVideoIssues(
            baseUrl = baseUrl,
            urlPath = stored.seasonPath,
            hosterName = hoster.hosterName,
            extensionName = currentName,
            extensionVersion = currentVersion,
        )
    }

    // =============================== Utils ===============================

    /**
     * Loads a page, either relative to the current base URL or absolute.
     *
     * @param location path such as `/catalogue/07-ghost`, or a full URL for a filtered search.
     * @return the parsed page, or `null` on a network or HTTP failure.
     */
    private suspend fun fetchDocument(location: String): Document? {
        val url = if (location.startsWith("http")) location else "$baseUrl$location"
        return try {
            client.get(url, headers).useAsJsoup()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Wraps a loaded catalogue page into the answer expected by Aniyomi.
     *
     * When the "Early Fetch" switch is on, each card also gets its fetch type, at the cost of one
     * work page per card.
     *
     * @param document loaded page, `null` when it could not be fetched.
     * @param page index that was requested, used to decide whether another page exists.
     */
    private suspend fun cataloguePage(document: Document?, page: Int): AnimesPage {
        if (document == null) return AnimesPage(emptyList(), false)
        val earlyFetch = useEarlyFetch
        return AnimesPage(
            animes = CataloguePage.entries(document).parallelMap { card -> card.toAnime(earlyFetch) },
            hasNextPage = CataloguePage.hasNextPage(document, page),
        )
    }

    /**
     * Loads and parses the work page of an entry.
     *
     * @param path any path inside the work.
     * @throws Exception when the work page cannot be fetched: it is the only page carrying
     *   metadata, so there is nothing useful to display without it.
     */
    private suspend fun loadHub(path: String): HubInfo {
        val hubPath = SitePaths.hubPath(path)
        val document = pages.load(hubPath) ?: throw ContentUnavailableException(WORK_UNAVAILABLE)
        return HubPage.parse(document, hubPath)
    }

    /**
     * Builds the episode shown while a work still points at its seasons.
     *
     * It carries no link on purpose: it is only there to explain what to do, so that clients
     * that call [getEpisodeList] on a hub show a readable line instead of an empty, error-looking
     * list. The text follows [useEarlyFetch], so it never asks for a switch that is already on.
     *
     * @return the explanatory episode.
     */
    private fun hubPlaceholder(): SEpisode = SEpisode.create().apply {
        url = ""
        if (useEarlyFetch) {
            // The switch is already on: the season list is simply not resolved yet.
            name = "⚠ Fiche en cours de résolution"
            summary = "Les saisons de cette œuvre sont en cours de chargement. Rouvrez la fiche dans un instant pour les voir apparaître."
        } else {
            name = "⚠ Activez Early Fetch"
            summary = "Ouvrez les paramètres de cette extension, activez « Early Fetch », puis videz la base locale de cette extension."
        }
    }

    /**
     * Chooses the season an entry actually points at.
     *
     * An entry opened from the catalogue points at a work: when that work declares a single
     * season, the season is used directly so the user does not have to pick it.
     */
    private fun resolveTarget(stored: MediaUrl, hub: HubInfo): SeasonRef? {
        val pointsAtSeason = stored.season != null || SitePaths.language(stored.url) != null
        if (pointsAtSeason) return SeasonRef(stored.season.orEmpty(), stored.url)
        return hub.seasons.singleOrNull()
    }

    /**
     * Collects every title known for a work.
     *
     * Alternate titles and the work title both improve the TVDB match, which is the only source
     * of episode titles.
     */
    private fun knownTitles(stored: MediaUrl, hub: HubInfo): Set<String> = (stored.titles + hub.altTitles + listOfNotNull(hub.title))
        .filter { it.isNotBlank() }
        .toSet()

    /**
     * Tells whether a season is a movie.
     *
     * @param path season path.
     * @param seasonLabel season label, or `null` for a work.
     */
    private fun isMovie(path: String, seasonLabel: String?): Boolean = ContentType.from(seasonLabel.orEmpty(), path) == ContentType.MOVIE

    /**
     * Fetches the TVDB metadata of a season.
     *
     * @param seriesTitle title of the work.
     * @param seasonLabel label of the season, or `null` for a work.
     * @param fullTitle full title Aniyomi displays, used for the title match.
     * @param titles alternate titles, used for the title match.
     * @param path season path, used to detect the content kind.
     * @param tmdbId TheMovieDB id advertised by the work page, when it matches the content kind.
     */
    private suspend fun tvdbFor(
        seriesTitle: String,
        seasonLabel: String?,
        fullTitle: String,
        titles: Set<String>,
        path: String,
        tmdbId: Long?,
    ): TvdbMetadata? {
        val contentType = ContentType.from(seasonLabel ?: seriesTitle, path)
        return fetchTvdbForPanel(
            seriesTitle = seriesTitle,
            rawSeasonName = seasonLabel,
            fullTitle = fullTitle,
            titles = titles,
            isMovie = contentType == ContentType.MOVIE,
            isSpecial = contentType == ContentType.SPECIAL || contentType == ContentType.OAV,
            externalTmdbId = tmdbId,
        )
    }

    /**
     * Fetches the season zero metadata, only when the site lists more episodes than TVDB knows.
     *
     * @param seriesTitle title of the work.
     * @param tvdb metadata of the current season.
     * @param siteEpisodeCount number of episodes the site lists.
     */
    private suspend fun loadSeasonZero(
        seriesTitle: String,
        tvdb: TvdbMetadata?,
        siteEpisodeCount: Int,
    ): TvdbMetadata? {
        val knownEpisodes = tvdb?.episodeSummaries?.size ?: 0
        if (knownEpisodes <= 0 || siteEpisodeCount <= knownEpisodes) return null
        return try {
            fetchTvdbMetadata(seriesTitle, season = 0, targetTvdbId = tvdb?.seriesId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Loads the bdd script of one language, or `null` when the page has none. */
    private suspend fun loadScript(seasonPath: String, lang: String): BddScript? {
        val document = pages.load(SitePaths.seasonUrl(seasonPath, lang)) ?: return null
        return BddScriptParser.parse(document)
    }

    /**
     * Loads a season page again to obtain a token the link API will accept.
     *
     * The token is embedded in the page and expires, so the cached copy is dropped first.
     */
    private suspend fun freshToken(seasonPath: String, lang: String): String? {
        pages.invalidate(SitePaths.seasonUrl(seasonPath, lang))
        return loadScript(seasonPath, lang)?.token
    }

    /**
     * Resolves one reference through the link API.
     *
     * The API answers `403` for an expired page token, `404` for an unknown reference and `400`
     * for a malformed request; the HTTP client turns every non-2xx answer into an exception.
     *
     * @param token page token the reference was read with.
     * @param id reference to resolve.
     */
    private suspend fun callLinkApi(token: String, id: String): LinkResolver.ApiResult {
        val response = try {
            client.get("$baseUrl/api/lien.php?j=$token&l=$id", headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            return apiFailure(e)
        } catch (e: IOException) {
            return apiFailure(e)
        }

        val resolved = try {
            val body = response.use { it.body.string() }
            jsonInstance.parseToJsonElement(body).jsonObject["u"]?.jsonPrimitive?.contentOrNull
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        return if (resolved.isNullOrBlank()) {
            LinkResolver.ApiResult.Unavailable
        } else {
            LinkResolver.ApiResult.Url(resolved)
        }
    }

    /**
     * Maps a failed link API call to a resolver outcome.
     *
     * @param error failure raised by the HTTP client.
     */
    private fun apiFailure(error: Throwable): LinkResolver.ApiResult = when (error.safeHttpCode) {
        HTTP_FORBIDDEN -> LinkResolver.ApiResult.TokenRejected
        HTTP_BAD_REQUEST, HTTP_NOT_FOUND -> LinkResolver.ApiResult.Unavailable
        else -> LinkResolver.ApiResult.Failed
    }

    /**
     * Returns the hostname of a URL.
     *
     * @param url URL to inspect.
     * @return the hostname, or an empty string when the URL cannot be parsed.
     */
    private fun hostOf(url: String): String = try {
        url.toHttpUrl().host
    } catch (_: IllegalArgumentException) {
        ""
    }

    /** Returns the router name of a hostname, or the hostname itself when unknown. */
    private fun serverNameFor(host: String): String {
        if (host.isBlank()) return ""
        return serverNames.getOrPut(host) {
            supportedServers.firstOrNull { name ->
                getVideoServer(this, name)?.matches("https://$host/") == true
            } ?: host
        }
    }

    /**
     * Builds the playable link of one raw reference.
     *
     * @param lang language the reference belongs to.
     * @param raw raw reference, either `lien:<id>` or a full embed URL.
     * @param script script the reference was read from, giving its host and state.
     * @return the link, or `null` when the site flagged it as removed.
     */
    private fun playerLink(lang: String, raw: String, script: BddScript): PlayerLink? {
        if (raw.startsWith(REFERENCE_PREFIX)) {
            val id = raw.removePrefix(REFERENCE_PREFIX).takeIf { it.isNotBlank() } ?: return null
            val host = script.hostOf(id).orEmpty()
            if (script.isHostDead(host) || !script.isAlive(id)) return null
            return PlayerLink(lang = lang, host = host, server = serverNameFor(host), id = id, token = script.token)
        }

        if (!raw.startsWith("http")) return null
        val host = hostOf(raw).takeIf { it.isNotBlank() } ?: return null
        if (script.isHostDead(host)) return null
        return PlayerLink(lang = lang, host = host, server = serverNameFor(host), directUrl = raw)
    }

    /** Collects every playable link of one episode, across languages and servers. */
    private fun collectLinks(
        episodeNumber: Int,
        tracks: List<Pair<LanguageRef, BddScript?>>,
    ): List<PlayerLink> {
        val links = mutableListOf<PlayerLink>()
        for ((language, script) in tracks) {
            if (script == null) continue
            for (server in script.servers) {
                val raw = server.refs.getOrNull(episodeNumber - 1) ?: continue
                playerLink(language.slug, raw, script)?.let { links += it }
            }
        }
        return links.distinctBy { Triple(it.lang, it.id, it.directUrl) }
    }

    /**
     * Builds the episode list of a season.
     *
     * Episodes are merged by index across languages: episode 3 exists as soon as one language
     * lists it, and an episode whose every link was removed is dropped.
     */
    private fun buildEpisodes(
        animeTitle: String,
        seasonPath: String,
        seasonLabel: String,
        targetPath: String,
        tracks: List<Pair<LanguageRef, BddScript?>>,
        episodeCount: Int,
        tvdb: TvdbMetadata?,
        seasonZero: TvdbMetadata?,
    ): List<SEpisode> {
        val contentType = ContentType.from(seasonLabel.ifBlank { animeTitle }, targetPath)

        return (1..episodeCount).map { number ->
            // An episode whose every link was flagged dead by the site is kept: dropping it
            // would leave a hole in the numbering, and the client can still show it as
            // unavailable.
            val links = collectLinks(number, tracks)

            val (prefixOverride, tvdbEpisode) = EpisodeNaming.resolveTvdbEpisode(number, contentType, tvdb, seasonZero)
            val name = EpisodeNaming.build(
                episodeNumber = number,
                contentType = contentType,
                totalEpisodes = episodeCount,
                animeTitle = animeTitle,
                seasonLabel = seasonLabel,
                seriesTitle = tvdb?.title,
                episodeTitle = tvdbEpisode?.title,
                prefixOverride = prefixOverride,
            )

            SEpisode.create().apply {
                episode_number = number.toFloat()
                this.name = name.value
                scanlator = links.map { it.lang.uppercase() }.distinct().joinToString(", ")
                url = LinkSet(seasonPath, links).toJsonString(json)
                summary = tvdbEpisode?.summary ?: tvdb?.summary
                // TVDB has no screenshot for every episode: fall back to the season artwork so
                // that the episode list never shows an empty thumbnail.
                preview_url = tvdbEpisode?.thumbnail
                    ?: tvdb?.backdropUrl
                    ?: tvdb?.seasonPosterUrl
                    ?: tvdb?.mainPosterUrl
            }
        }
    }

    /**
     * Converts a parsed catalogue card into a library entry.
     *
     * @param earlyFetch whether the fetch type must be resolved now, for clients that never
     *   refresh it later.
     */
    private suspend fun CatalogueCard.toAnime(earlyFetch: Boolean): SAnime = SAnime.create().apply {
        title = this@toAnime.title
        thumbnail_url = this@toAnime.posterUrl
        url = this@toAnime.media.toJsonString(json)
        if (earlyFetch && isHub(this@toAnime.media.url)) {
            fetch_type = FetchType.Seasons
            season_number = HUB_SEASON_NUMBER
        }
    }

    /**
     * Tells whether a work declares several seasons.
     *
     * Only the work page knows it: the catalogue does not expose the season count.
     *
     * @param path path of a work or of one of its seasons.
     */
    private suspend fun isHub(path: String): Boolean {
        val hubPath = SitePaths.hubPath(path)
        val document = pages.load(hubPath) ?: return false
        return HubPage.seasons(document, hubPath).size > 1
    }

    /**
     * Reads the stored payload of a library entry.
     *
     * Three formats can be found in a library: the current JSON, the bare path written by the
     * first releases, and the anchored path handled by the legacy parser.
     */
    private fun SAnime.storedMedia(): MediaUrl? = url.toMediaUrl(json) ?: url.toBarePathMediaUrl()

    /**
     * Reads the bare-path format, used before the JSON migration.
     *
     * Entries added by the first releases hold `/catalogue/<work>` or
     * `/catalogue/<work>/<season>` with no JSON. The path alone is exactly what the current
     * pipeline needs, so those entries keep working instead of falling back to the old HTML
     * parser, which no longer matches the site.
     *
     * @return the decoded entry, or `null` for an anchored URL that only the legacy parser reads.
     */
    private fun String.toBarePathMediaUrl(): MediaUrl? {
        if (!startsWith("/") || contains("#")) return null

        val path = SitePaths.normalize(this)
        val segments = SitePaths.segments(path)
        if (segments.size < 2) return null

        return MediaUrl(
            url = path,
            season = if (segments.size >= 3) seasonLabelOf(segments[2]) else null,
        )
    }

    /**
     * Derives a season label from a season slug.
     *
     * Old entries do not store the label the site displayed. The slug is the only hint, and an
     * empty label is harmless: the season page states its own label when needed.
     *
     * @param slug season slug, for example `saison1`, `film` or `oav`.
     */
    private fun seasonLabelOf(slug: String): String = when {
        slug.contains("film", ignoreCase = true) -> "Film"
        slug.contains("oav", ignoreCase = true) || slug.contains("ova", ignoreCase = true) -> "OAV"
        else -> SEASON_SLUG_REGEX.find(slug)?.let { "Saison ${it.groupValues[1]}" }.orEmpty()
    }

    /**
     * Reads the stored links of an episode.
     *
     * Episodes listed by the previous version hold resolved URLs instead of page references;
     * they are converted so that an existing library keeps playing without a refresh.
     */
    private fun SEpisode.storedLinks(): LinkSet? = url.toLinkSet(json) ?: url.toStoredPlayers(json)

    /**
     * Converts the links written by the previous version of the extension.
     *
     * @param serializer JSON instance of the source.
     */
    private fun String.toStoredPlayers(serializer: Json): LinkSet? = try {
        val players = parseAs<List<StoredPlayer>>(serializer)
        if (players.isEmpty()) {
            null
        } else {
            LinkSet(
                seasonPath = "",
                links = players.map { player ->
                    val host = hostOf(player.url)
                    PlayerLink(
                        lang = player.lang,
                        host = host,
                        server = serverNameFor(host),
                        directUrl = player.url,
                    )
                },
            )
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Decodes a stored URL, returning `null` for the pre-JSON format. */
    private fun String.toMediaUrl(serializer: Json): MediaUrl? = try {
        parseAs<MediaUrl>(serializer)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Decodes stored links, returning `null` for an unknown or older format. */
    private fun String.toLinkSet(serializer: Json): LinkSet? = try {
        parseAs<LinkSet>(serializer)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        /** Prefix a deep link uses to designate a work instead of a search term. */
        const val PREFIX_SEARCH = "id:"

        /** Matches the season number in a season slug, for the pre-JSON entries. */
        private val SEASON_SLUG_REGEX = Regex("""(?i)^saison(\d+)""")

        /** Prefix the site uses for a link that must be resolved by the API. */
        private const val REFERENCE_PREFIX = "lien:"

        /** Language used when an entry carries none. */
        private const val DEFAULT_LANGUAGE = "vostfr"

        /** Preference key of the "Early Fetch" switch. */
        private const val PREF_EARLY_FETCH_TYPE_KEY = "early_fetch"

        /** Default state of the "Early Fetch" switch: off, since it costs one request per card. */
        private const val PREF_EARLY_FETCH_TYPE_DEFAULT = false

        /** Status returned by the link API for an expired page token. */
        private const val HTTP_FORBIDDEN = 403

        /** Status returned by the link API for a malformed request. */
        private const val HTTP_BAD_REQUEST = 400

        /** Status returned by the link API for an unknown reference. */
        private const val HTTP_NOT_FOUND = 404

        /** Message shown when the work page cannot be loaded. */
        private const val WORK_UNAVAILABLE =
            "La fiche de cette œuvre est introuvable sur Anime-Sama. Réessayez plus tard."
    }
}
