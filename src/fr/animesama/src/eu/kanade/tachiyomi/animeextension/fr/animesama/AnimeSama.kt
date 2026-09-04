// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.util.Log
import eu.kanade.tachiyomi.animeextension.fr.animesama.dto.ContentType
import eu.kanade.tachiyomi.animeextension.fr.animesama.dto.EpisodePlayers
import eu.kanade.tachiyomi.animeextension.fr.animesama.dto.Player
import eu.kanade.tachiyomi.animeextension.fr.animesama.dto.UrlContent
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.ANIMESAMA_LOG
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.Source
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportEpisodeIssues
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportHosterIssues
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportIncompleteness
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportSeasonIssues
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportVideoIssues
import fr.bluecxt.core.tmdb.utils.extractSeasonNumber
import fr.bluecxt.core.tvdb.TvdbMetadata
import fr.bluecxt.core.tvdb.fetchTvdbMetadata
import fr.bluecxt.core.tvdb.utils.fetchTvdbForPanel
import fr.bluecxt.core.utils.JsoupExtensions
import fr.bluecxt.core.utils.normalize
import fr.bluecxt.core.utils.safeRelativePath
import keiyoushi.core.R
import keiyoushi.utils.get
import keiyoushi.utils.parallelMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.Collections.synchronizedMap
import java.util.concurrent.ConcurrentHashMap

class AnimeSama :
    Source(),
    CommonPreferences,
    JsoupExtensions {

    override val name = "Anime-Sama"

    override val defaultBaseUrl = "https://anime-sama.to"
    override val supportedServers = listOf("Sibnet", "Sendvid", "Vidmoly", "Embed4me", "Minochinos")
    override val supportedVoices = arrayOf("VOSTFR", "VF", "VA")
    override val lang = "fr"
    override val supportsLatest = true
    override val defaultServer = "Vidmoly"

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)
        .add("Referer", "$baseUrl/")

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${urlParser(anime.url).first.url}"

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$baseUrl/catalogue?page=$page"
        val document = client.get(url, headers).useAsJsoup()
        return parseCatalogue(document, page)
    }

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val document = client.get(baseUrl, headers).useAsJsoup()
        return parseMainPage(document)
    }

    // ============================== Search ===============================
    override fun getFilterList() = AnimeSamaFilters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder()
        val params = AnimeSamaFilters.getSearchFilters(filters)

        params.types.forEach { url.addQueryParameter("type[]", it) }
        params.language.forEach { url.addQueryParameter("langue[]", it) }
        params.genres.forEach { url.addQueryParameter("genre[]", it) }
        params.statut.forEach { url.addQueryParameter("current[]", it) }

        url.addQueryParameter("annee_min", params.yearMin)
        url.addQueryParameter("annee_max", params.yearMax)

        url.addQueryParameter("search", query.trim())
        url.addQueryParameter("page", "$page")

        val document = client.get(url.build(), headers).useAsJsoup()
        return parseCatalogue(document, page)
    }

    // ============================== Catalogue ===============================
    private fun parseCatalogue(
        document: Document,
        page: Int,
        animesSelector: String = "div.catalog-card",
    ): AnimesPage {
        val animes = document.select(animesSelector).mapNotNull { anime ->
            if (anime.select(".info-row:has(.info-label:contains(Types)) .info-value").text().trim().equals("Scans", true)) return@mapNotNull null

            val link = anime.selectFirstLog("a")?.safeRelativePath() ?: return@mapNotNull null
            val realLink = link.split("/").take(MAX_HUB_PATH_SEGMENTS).joinToString("/")
            var thumbnail = anime.select("img:not(.ak-cta-flag)").attr("abs:src")

            if (thumbnail.contains("thumb/") && thumbnail.contains(".webp")) {
                thumbnail = thumbnail.replace("thumb/", "").replace(".webp", ".jpg")
            }

            val name = anime.selectFirstLog(".card-title, h2")?.text() ?: "unknown title"
            val names: Set<String> = buildSet {
                add(name)
                add(realLink.substringAfterLast("/").replace("-", " "))
                anime.selectFirst("p.alternate-titles")?.text()?.split(",")
                    ?.map { it.trim() }
                    ?.let { addAll(it) }
            }

            val jsonUrl = UrlContent(url = realLink, titles = names, null).toJsonString()

            SAnime.create().apply {
                title = name
                thumbnail_url = thumbnail
                url = jsonUrl
            }
        }
        val lastPage = document.select("#list_pagination a:last-child").text().toIntOrNull() ?: 0
        val hasNextPage = lastPage != 0 && page < lastPage
        return AnimesPage(animes.distinctBy { it.url }, hasNextPage)
    }

    // =========================== Anime Details ============================

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val (parsedUrl, _) = urlParser(anime.url)
        val link = parsedUrl.url
        val document = client.get("$baseUrl$link", headers).useAsJsoup()
        return parseCatalogue(document, 0).animes
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = coroutineScope {
        Log.d(ANIMESAMA_LOG, "getAnimeDetails: input url = '${anime.url}'")
        val (parsedUrl, newUrl) = urlParser(anime.url)
        val link = parsedUrl.url
        val season = parsedUrl.season
        val titles = parsedUrl.titles
        Log.d(ANIMESAMA_LOG, "getAnimeDetails: parsed link = '$link', newUrl = $newUrl, season = $season")

        if (!newUrl) return@coroutineScope getLegacyAnimeDetails(anime)

        val documentDeferred = async { getOrFetchDocument(link) }
        val tvdbMetadataDeferred = async {
            fetchTvdbForPanel(anime.title, season, anime.title, titles)
        }

        val document = documentDeferred.await()
        val medias = parseMedias(link, document, titles)
        val isHub = (season == null && medias.size > 1)

        Log.d(ANIMESAMA_LOG, "media number = ${medias.size}")

        if (isHub) {
            anime.fetch_type = FetchType.Seasons
            anime.season_number = HUB_SEASON_NUMBER
        }

        anime.populateFromDocument(document)

        val (effectiveLink, effectiveSeason) = resolveEffectiveTarget(medias, link, season)
        val contentType = ContentType.from(anime.title, effectiveSeason ?: effectiveLink)
        val isMovie = isMovieContent(effectiveSeason, effectiveLink, contentType, medias)
        val isSpecial = isSpecialSeason(effectiveSeason)

        var tvdbMetadata = tvdbMetadataDeferred.await()
        if (isMovie || isSpecial || effectiveSeason != season) {
            tvdbMetadata = fetchTvdbForPanel(anime.title, effectiveSeason, anime.title, titles, isMovie = isMovie, isSpecial = isSpecial)
        }

        anime.enrichWithTvdb(tvdbMetadata, document, effectiveSeason, isHub)

        anime.checkAndReportIncompleteness(baseUrl, ::getAnimeUrl)
        anime
    }

    private fun isSpecialSeason(seasonName: String?): Boolean {
        if (seasonName.isNullOrBlank()) return false
        val lower = seasonName.lowercase()
        return lower.contains("oav") || lower.contains("ova") || lower.contains("special") || lower.contains("spécial")
    }

    private fun resolveEffectiveTarget(medias: List<UrlContent>, link: String, season: String?): Pair<String, String?> {
        val effectiveLink = if (medias.size == 1 && medias[0].url.isNotBlank()) medias[0].url else link
        val effectiveSeason = season ?: if (medias.isNotEmpty()) medias[0].season else null
        return Pair(effectiveLink, effectiveSeason)
    }

    private fun isMovieContent(season: String?, link: String, contentType: ContentType, medias: List<UrlContent> = emptyList()): Boolean = (season != null && season.startsWith("Film", ignoreCase = true)) ||
        link.contains("film", ignoreCase = true) ||
        contentType == ContentType.MOVIE ||
        (medias.size == 1 && medias[0].url.contains("film", ignoreCase = true))

    private fun SAnime.populateFromDocument(document: Document) {
        if (author.isNullOrEmpty()) author = document.selectFirst("div.info-grid > span:contains(Studio) + .info-val")?.text() ?: ""
        if (genre.isNullOrEmpty()) genre = document.select("div.genres-wrap > span").joinToString { it.text() }
        val statusText = document.selectFirst("div.info-grid > span:contains(État) + .info-val")?.text() ?: ""
        status = when (statusText) {
            "Terminé", "Sorti" -> SAnime.COMPLETED
            "En cours" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun SAnime.enrichWithTvdb(tvdbMetadata: TvdbMetadata?, document: Document, season: String?, isHub: Boolean) {
        val rawYear = document.selectFirst("div.info-grid > span:contains(Année) + .info-val")?.text()
        val descriptionText = document.selectFirst("p#synopsisText")?.text().orEmpty()

        tvdbMetadata?.let { metadata ->
            if (artist.isNullOrBlank()) artist = metadata.artist
            if (author.isNullOrBlank()) author = metadata.author
            if (genre.isNullOrBlank()) genre = metadata.genre
            if (status == SAnime.UNKNOWN) status = metadata.status

            val pageCover = document.getElementById("coverOeuvre")?.attr("abs:src")
            val targetPoster = if (season != null && !isHub) (metadata.seasonPosterUrl ?: metadata.mainPosterUrl) else metadata.mainPosterUrl
            thumbnail_url = targetPoster ?: pageCover ?: thumbnail_url
            background_url = metadata.backdropUrl ?: metadata.mainPosterUrl
        }

        val targetSummary = if (season != null && !isHub) {
            tvdbMetadata?.summary?.takeIf { it.isNotBlank() } ?: descriptionText
        } else {
            descriptionText.ifBlank { tvdbMetadata?.summary.orEmpty() }
        }

        val year = tvdbMetadata?.releaseDate ?: rawYear
        if (description.isNullOrEmpty() || (season != null && !isHub && !tvdbMetadata?.summary.isNullOrBlank())) {
            description = buildDescription(targetSummary, year)
        }
    }

    // ============================== Season ==============================
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        Log.d(ANIMESAMA_LOG, "getSeasonList: input url = '${anime.url}'")
        val (parsedUrl, newUrl) = urlParser(anime.url)
        Log.d(ANIMESAMA_LOG, "getSeasonList: parsedUrl = $parsedUrl, newUrl = $newUrl")
        if (!newUrl) return getLegacySeasonList(anime)

        val link = parsedUrl.url
        val document = getOrFetchDocument(link)

        val medias = parseMedias(link, document, parsedUrl.titles)
        return medias.parallelMap { media ->
            val rawSeason = media.season.orEmpty()
            val fullTitle = formatSeasonTitle(anime.title, rawSeason, media.titles)
            val contentType = ContentType.from(fullTitle, media.url)
            val isMovie = isMovieContent(rawSeason, media.url, contentType, medias)
            val isSpecial = isSpecialSeason(rawSeason)
            val tvdbMetadata = fetchTvdbForPanel(anime.title, rawSeason, fullTitle, media.titles, isMovie = isMovie, isSpecial = isSpecial)

            SAnime.create().apply {
                title = fullTitle
                url = UrlContent(
                    url = media.url,
                    titles = parsedUrl.titles,
                    season = rawSeason,
                ).toJsonString(json)
                thumbnail_url = tvdbMetadata?.seasonPosterUrl ?: tvdbMetadata?.mainPosterUrl ?: document.getElementById("coverOeuvre")?.attr("abs:src")
                Log.d(ANIMESAMA_LOG, "getSeasonList item: title='$fullTitle', rawSeason='$rawSeason', chosenThumbnail='$thumbnail_url', tvdbSeasonPoster='${tvdbMetadata?.seasonPosterUrl}', tvdbMainPoster='${tvdbMetadata?.mainPosterUrl}'")
                description = tvdbMetadata?.summary ?: document.selectFirstLog("p#synopsisText")?.text() ?: ""
                tvdbMetadata?.releaseDate?.let { date ->
                    description = buildDescription(description.orEmpty(), date)
                }
                author = tvdbMetadata?.author
                artist = tvdbMetadata?.artist
                genre = tvdbMetadata?.genre
                status = tvdbMetadata?.status ?: SAnime.UNKNOWN
                initialized = true
                fetch_type = FetchType.Episodes
                season_number = HUB_SEASON_NUMBER
                background_url = tvdbMetadata?.backdropUrl
            }
        }.checkAndReportSeasonIssues(baseUrl, anime.title, ::getAnimeUrl)
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (parsedUrl, newUrl) = urlParser(anime.url)
        Log.d(ANIMESAMA_LOG, "getEpisodeList: input url='${anime.url}', parsedUrl=$parsedUrl, newUrl=$newUrl")
        if (!newUrl) return getLegacyEpisodeList(anime)

        val initialLink = parsedUrl.url
        val initialSeason = parsedUrl.season

        val preDoc = getOrFetchDocument(initialLink)
        val medias = parseMedias(initialLink, preDoc, parsedUrl.titles)
        Log.d(ANIMESAMA_LOG, "getEpisodeList: found ${medias.size} medias for link='$initialLink'")

        val isHub = (initialSeason == null && medias.size > 1)
        if (isHub) {
            Log.d(ANIMESAMA_LOG, "getEpisodeList: isHub=true for '$initialLink'")
            return listOf(
                SEpisode.create().apply {
                    url = ""
                    name = ""
                },
            )
        }

        val (link, rawSeason) = resolveEffectiveTarget(medias, initialLink, initialSeason)
        if (link != initialLink) {
            getOrFetchDocument(link)
        }

        val episodes: List<EpisodePlayers> = langList.parallelMap { lang ->
            fetchPlayers(link, lang)
        }
            .flatten()
            .groupBy { it.episodeNumber }
            .map { (epNum, episodeList) ->
                EpisodePlayers(
                    episodeNumber = epNum,
                    players = episodeList.flatMap { it.players }.distinctBy { it.url },
                )
            }
            .ifEmpty {
                throw IllegalStateException(getString(R.string.error_no_episodes_found))
            }

        val titles = parsedUrl.titles
        val fullTitle = formatSeasonTitle(anime.title, rawSeason.orEmpty(), titles)
        val contentType = ContentType.from(anime.title, rawSeason ?: link)
        val isMovie = isMovieContent(rawSeason, link, contentType, medias)
        val tvdbMetadata = fetchTvdbForPanel(anime.title, rawSeason, fullTitle, titles, isMovie = isMovie)

        // 3. Gestion de l'overflow (Saisons avec OAV rajoutés en fin de liste)
        val tvdbEpCount = tvdbMetadata?.episodeSummaries?.size ?: 0
        var autoS0Offset = 0
        if (episodes.size > tvdbEpCount && tvdbEpCount > 0) {
            val currentMediaIndex = medias.indexOfFirst { it.url == link }.takeIf { it >= 0 } ?: medias.size
            for (i in 0 until currentMediaIndex) {
                val m = medias[i]
                val mSeasonName = m.season.orEmpty()
                val mSeasonNum = extractSeasonNumber(mSeasonName)
                if (mSeasonNum != null && mSeasonNum > 0) {
                    val mPlayers = fetchPlayers(m.url, "vostfr")
                    val mAnimeSamaCount = mPlayers.size
                    val mTvdbCount = tvdbMetadata?.seasonEpisodeCounts?.get(mSeasonNum) ?: 0
                    if (mAnimeSamaCount > mTvdbCount && mTvdbCount > 0) {
                        autoS0Offset += (mAnimeSamaCount - mTvdbCount)
                    }
                }
            }
        }

        val s0Metadata = if (episodes.size > tvdbEpCount && tvdbEpCount > 0) {
            fetchTvdbMetadata(anime.title, season = 0)
        } else {
            null
        }

        return episodes.episodesPlayersToSEpisodes(
            tvdbMetadata = tvdbMetadata,
            season = rawSeason,
            animeTitle = anime.title,
            s0Metadata = s0Metadata,
            autoS0Offset = autoS0Offset,
        ).reversed().checkAndReportEpisodeIssues(baseUrl, link, anime.title)
    }

    // ============================== Hosters ==============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val players: List<Player> = try {
            episode.url.parseAs(json)
        } catch (_: Exception) {
            return getLegacyHosterList(episode)
        }

        val langs = players.map { it.lang }.distinct()
        val hosters = langs.map { lang ->
            val langPlayers = players.filter { it.lang == lang }
            Hoster(
                hosterName = lang.uppercase(),
                internalData = json.encodeToString(langPlayers),
            )
        }.sortHosters()
        return hosters.checkAndReportHosterIssues(baseUrl, episode.url, episode.name)
    }

    // ============================== Videos ==============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val players: List<Player> = try {
            json.decodeFromString(hoster.internalData)
        } catch (_: Exception) {
            return getLegacyVideoList(hoster)
        }

        val videos = players.parallelMap { player ->
            extractVideos(player.url, player.lang, supportedServers)
        }.flatten().sortVideos()

        return videos.checkAndReportVideoIssues(baseUrl, hoster.hosterName, hoster.hosterName)
    }

    // ============================== Utils ===============================
    private fun List<EpisodePlayers>.episodesPlayersToSEpisodes(
        tvdbMetadata: TvdbMetadata?,
        season: String?,
        animeTitle: String = "",
        s0Metadata: TvdbMetadata? = null,
        autoS0Offset: Int = 0,
    ): List<SEpisode> {
        val contentType = ContentType.from(animeTitle, season ?: "")
        val seasonNum = season?.filter { it.isDigit() }?.toIntOrNull() ?: 1
        val defaultPrefix = contentType.getPrefix(seasonNum)
        val tvdbEpCount = tvdbMetadata?.episodeSummaries?.size ?: 0
        val episodeOffset = tvdbMetadata?.episodeOffset ?: 0

        return this.map { episode ->
            val epNum = episode.episodeNumber
            val (prefix, epMeta) = resolveEpisodeMetadata(epNum, tvdbMetadata, s0Metadata, contentType, defaultPrefix, autoS0Offset)
            val baseName = formatEpisodeBaseName(epNum, contentType, animeTitle, season, epMeta?.first, tvdbMetadata?.title, this.size)
            val finalName = "$prefix$baseName".trim()

            Log.d(ANIMESAMA_LOG, "episodesPlayersToSEpisodes: ep#$epNum -> name='$finalName', previewUrl='${epMeta?.second}', hasSummary=${epMeta?.third != null}")

            SEpisode.create().apply {
                name = finalName
                episode_number = epNum.toFloat()
                scanlator = episode.getScanlatorString()
                url = episode.players.toJsonString(json)
                summary = epMeta?.third ?: tvdbMetadata?.summary
                preview_url = epMeta?.second ?: tvdbMetadata?.backdropUrl ?: tvdbMetadata?.seasonPosterUrl ?: tvdbMetadata?.mainPosterUrl
            }
        }
    }

    private fun resolveEpisodeMetadata(
        epNum: Int,
        tvdbMetadata: TvdbMetadata?,
        s0Metadata: TvdbMetadata?,
        contentType: ContentType,
        defaultPrefix: String,
        autoS0Offset: Int = 0,
    ): Pair<String, Triple<String?, String?, String?>?> {
        val offset = tvdbMetadata?.episodeOffset ?: 0
        val tvdbEpCount = tvdbMetadata?.episodeSummaries?.size ?: 0

        val epMeta = tvdbMetadata?.episodeSummaries?.get(epNum + offset)
        if (isSeasonOverflow(epMeta, contentType, epNum, tvdbEpCount, s0Metadata)) {
            val s0EpIndex = (epNum - tvdbEpCount) + offset + autoS0Offset
            val s0Meta = s0Metadata?.episodeSummaries?.get(s0EpIndex)
            if (s0Meta != null) return Pair("[OAV] ", s0Meta)
        }
        return Pair(defaultPrefix, epMeta)
    }

    private fun formatEpisodeBaseName(
        epNum: Int,
        contentType: ContentType,
        animeTitle: String,
        season: String?,
        tvdbName: String?,
        tvdbTitle: String?,
        totalEpisodes: Int,
    ): String {
        val cleanTvdbName = tvdbName?.trim()
        return when {
            contentType == ContentType.MOVIE -> {
                val movieTitle = tvdbTitle?.takeIf { it.isNotBlank() }
                    ?: season?.takeIf { it.isNotBlank() && !it.equals("Film", ignoreCase = true) }
                    ?: animeTitle
                if (totalEpisodes == 1) movieTitle else "$movieTitle $epNum"
            }

            cleanTvdbName.isNullOrBlank() || contentType == ContentType.SPECIAL -> "Épisode $epNum"

            cleanTvdbName.matches(episodeBasicRegex) -> "Épisode $epNum"

            cleanTvdbName.matches(episodeWithTitleRegex) -> {
                val realTitle = episodeWithTitleRegex.find(cleanTvdbName)?.groupValues?.get(1)?.trim()
                if (!realTitle.isNullOrBlank()) "Épisode $epNum - $realTitle" else "Épisode $epNum"
            }

            else -> "Épisode $epNum - $cleanTvdbName"
        }
    }

    private suspend fun fetchPlayers(url: String, lang: String): List<EpisodePlayers> {
        val jsUrl = "$baseUrl$url/$lang/episodes.js"
        Log.d(ANIMESAMA_LOG, "fetchPlayers: lang=$lang, jsUrl='$jsUrl'")

        val doc = try {
            val response = client.get(jsUrl, headers)
            Log.d(ANIMESAMA_LOG, "fetchPlayers: HTTP status=${response.code} for $jsUrl")
            if (!response.isSuccessful) return emptyList()
            response.body.string()
        } catch (e: HttpException) {
            Log.d(ANIMESAMA_LOG, "fetchPlayers: HTTP error for $jsUrl: ${e.message}")
            return emptyList()
        } catch (e: IOException) {
            Log.e(ANIMESAMA_LOG, "fetchPlayers: network error for $jsUrl: ${e.message}")
            return emptyList()
        }

        val servers = epsArrayRegex.findAll(doc).map { match ->
            val arrayName = match.groupValues[1]
            val arrayContent = match.groupValues[2]
            val urls = urlInArrayRegex.findAll(arrayContent).map { it.groupValues[1].trim() }.toList()
            Log.d(ANIMESAMA_LOG, "fetchPlayers: found server array 'eps$arrayName' with ${urls.size} urls")
            urls
        }.toList()

        val maxEpisodes = servers.maxOfOrNull { it.size } ?: run {
            Log.w(ANIMESAMA_LOG, "fetchPlayers: 0 server arrays matched in $jsUrl")
            sendErrorWebhook(
                url = jsUrl,
                context = "Échec du parsing de $jsUrl (0 tableau de serveurs vidéo 'eps' trouvé)",
                exception = IllegalStateException("epsArrayRegex matched 0 server arrays in $jsUrl"),
            )
            return emptyList()
        }

        val langTag = lang.uppercase()

        val result = (0 until maxEpisodes).mapNotNull { index ->
            val urlsForEpisode = servers.mapNotNull { it.getOrNull(index) }
                .distinct()
                .map { playerUrl -> Player(url = playerUrl, lang = langTag) }

            if (urlsForEpisode.isNotEmpty()) {
                EpisodePlayers(
                    episodeNumber = index + 1,
                    players = urlsForEpisode,
                )
            } else {
                null
            }
        }

        Log.d(ANIMESAMA_LOG, "fetchPlayers: lang=$lang -> extracted ${result.size} episodes")
        return result
    }

    private fun formatSeasonTitle(seriesTitle: String, rawSeasonName: String, titles: Set<String>): String {
        val cleanSeason = rawSeasonName.trim()

        val normalizedSeason = cleanSeason.normalize()

        if (titles.any { it.normalize() in normalizedSeason }) return cleanSeason

        if (cleanSeason.equals("Saison 1", ignoreCase = true)) return seriesTitle

        if (cleanSeason.matches(seasonRegex)) {
            val shortSeason = cleanSeason
                .replace(seasonReplaceRegex, " ")
                .replace(partReplaceRegex, "Part $1")
                .trim()
            return "$seriesTitle $shortSeason"
        }

        val fullCombined = "$seriesTitle $cleanSeason"
        return if (fullCombined.length > MAX_COMBINED_TITLE_LENGTH && !cleanSeason.startsWith("Film") && !cleanSeason.contains("OAV", ignoreCase = true)) {
            cleanSeason
        } else {
            fullCombined
        }
    }

    private val documentMutexes = ConcurrentHashMap<String, Mutex>()

    private suspend fun getOrFetchDocument(link: String): Document {
        getCachedDocument(link)?.let { return it }

        val mutex = documentMutexes.computeIfAbsent(link) { Mutex() }
        return mutex.withLock {
            getCachedDocument(link)?.let { return it }

            val targetUrl = "$baseUrl$link"
            client.get(targetUrl, headers).useAsJsoup().also { doc ->
                putCachedDocument(link, doc)
                documentMutexes.remove(link)
            }
        }
    }

    private fun getCachedDocument(link: String): Document? {
        val cached = documentCache[link] ?: return null
        val (doc, timestamp) = cached
        if (System.currentTimeMillis() - timestamp > CACHE_LIFETIME) {
            documentCache.remove(link)
            return null
        }
        return doc
    }

    private fun putCachedDocument(link: String, doc: Document) {
        documentCache[link] = Pair(doc, System.currentTimeMillis())
    }

    private fun parseMedias(link: String, document: Document, titles: Set<String>): List<UrlContent> {
        val pathSegments = link.removePrefix("/").split("/")
        val hubLink = "/" + pathSegments.take(2).joinToString("/")
        Log.d(ANIMESAMA_LOG, "parseMedias: link = '$link', hubLink = '$hubLink'")

        val scriptContent = document.select("script").joinToString("\n") { it.html() }
        val uncommented = commentRegex.replace(scriptContent, "")
        val medias = uncommented.lines()
            .map { it.trim() }
            .mapNotNull { line ->
                panneauRegex.find(line)?.let { match ->
                    val name = match.groupValues[1]
                    val rawUrl = match.groupValues[2]
                    val rawFolder = rawUrl.substringBefore("/")
                    val subFolder = if (rawFolder.lowercase() in langList) "" else rawFolder
                    val urlClean = subFolder.safeRelativePath("$baseUrl$hubLink/")?.removeSuffix("/") ?: return@mapNotNull null
                    Log.d(ANIMESAMA_LOG, "parseMedias: found rawUrl = '$rawUrl' -> urlClean = '$urlClean'")
                    UrlContent(
                        url = urlClean,
                        titles = titles,
                        season = name,
                    )
                }
            }.distinctBy { it.url }

        if (medias.isEmpty() && !link.contains("404")) {
            sendErrorWebhook(
                url = "$baseUrl$link",
                context = "Échec du parsing des saisons (panneauAnime) : aucun média trouvé dans le script HTML",
                exception = IllegalStateException("panneauRegex matched 0 season panels for $link"),
            )
        }
        return medias
    }

    private fun urlParser(jsonUrl: String): Pair<UrlContent, Boolean> = try {
        val urlContent: UrlContent = jsonUrl.parseAs(json)
        val parsedUrl = UrlContent(
            urlContent.url,
            urlContent.titles,
            urlContent.season,
        )
        Pair(parsedUrl, true)
    } catch (_: SerializationException) { // legacy
        parseLegacyUrl(jsonUrl)
    } catch (_: IllegalArgumentException) { // legacy malformed json
        parseLegacyUrl(jsonUrl)
    }

    private fun parseLegacyUrl(jsonUrl: String): Pair<UrlContent, Boolean> {
        val link = jsonUrl.substringBefore("#")

        val titleFromUrl = jsonUrl.substringAfter("|", "").takeIf { it.isNotBlank() }
        val slugTitle = link.substringAfterLast("/").replace("-", " ")

        val titles: Set<String> = buildSet {
            titleFromUrl?.let { add(it) }
            add(slugTitle)
        }
        val parsedUrl = UrlContent(link, titles, null)
        return Pair(parsedUrl, false)
    }

    private fun parseMainPage(document: Document): AnimesPage = parseCatalogue(
        document,
        0,
        "#containerSorties > div, #containerAjoutsAnimes > div, #containerJeudi > div, #akTrack > div",
    )

    private fun buildDescription(description: String, year: String?): String = buildString {
        if (!year.isNullOrBlank()) append("Date de sortie : $year\n\n")
        append(description)
    }

    private fun isSeasonOverflow(
        epMeta: Triple<String?, String?, String?>?,
        contentType: ContentType,
        epNum: Int,
        tvdbEpCount: Int,
        s0Metadata: TvdbMetadata?,
    ): Boolean = epMeta == null && contentType == ContentType.SEASON && epNum > tvdbEpCount && s0Metadata != null

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val MAX_HUB_PATH_SEGMENTS = 3
        private const val MAX_COMBINED_TITLE_LENGTH = 35
        private val commentRegex = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val panneauRegex = Regex("""panneauAnime\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")
        private val epsArrayRegex = Regex("""var\s+eps([a-zA-Z0-9_]+)\s*=\s*\[(.*?)\];""", RegexOption.DOT_MATCHES_ALL)
        private val urlInArrayRegex = Regex("""['"]([^'"]+)['"]""")
        private val documentCache = synchronizedMap(
            mutableMapOf<String, Pair<Document, Long>>(),
        )
        const val CACHE_LIFETIME = 30000L
        private val langList = listOf("vostfr", "vf", "vj", "var", "vcn", "vqc", "vkr", "va", "vf1", "vf2")

        private val episodeBasicRegex = Regex("""(?i)^(?:Épisode|Episode)\s*\d+$""")
        private val episodeWithTitleRegex = Regex("""(?i)^(?:Épisode|Episode)\s*\d+\s*[-:]\s*(.+)""")
        private val seasonRegex = Regex("""(?i)^Saison\s*\d+.*""")
        private val seasonReplaceRegex = Regex("""(?i)\s*Saison\s*""")
        private val partReplaceRegex = Regex("""(?i)Partie\s*(\d+)""")
    }
}
