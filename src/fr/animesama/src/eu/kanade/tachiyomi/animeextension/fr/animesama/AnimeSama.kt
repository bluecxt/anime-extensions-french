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
import fr.bluecxt.core.tvdb.TvdbMetadata
import fr.bluecxt.core.tvdb.fetchTvdbMetadata
import fr.bluecxt.core.tvdb.utils.fetchTvdbForPanel
import fr.bluecxt.core.utils.normalize
import fr.bluecxt.core.utils.safeRelativePath
import fr.bluecxt.core.utils.selectFirstLog
import fr.bluecxt.core.utils.selectLog
import keiyoushi.utils.get
import keiyoushi.utils.parallelMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
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
    CommonPreferences {

    override val name = "Anime-Sama"

    override val defaultBaseUrl = "https://anime-sama.to"
    override val supportedServers = listOf("Sibnet", "Sendvid", "Vidmoly", "Embed4me", "Minochinos")
    override val supportedVoices = arrayOf("VOSTFR", "VF", "VA")
    override val lang = "fr"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)
        .add("Referer", "$baseUrl/")

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${urlParser(anime.url).first.url}"

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$baseUrl/catalogue?page=$page"
        val document = client.get(url, headers).asJsoup()
        return parseCatalogue(document, page)
    }

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val document = client.get(baseUrl, headers).asJsoup()
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

        val document = client.get(url.build(), headers).asJsoup()
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
        val document = client.get("$baseUrl$link", headers).asJsoup()
        return parseCatalogue(document, 0).animes
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        Log.d(ANIMESAMA_LOG, "getAnimeDetails: input url = '${anime.url}'")
        val (parsedUrl, newUrl) = urlParser(anime.url)
        val link = parsedUrl.url
        val season = parsedUrl.season
        val titles = parsedUrl.titles
        Log.d(ANIMESAMA_LOG, "getAnimeDetails: parsed link = '$link', newUrl = $newUrl, season = $season")

        if (!newUrl) return getLegacyAnimeDetails(anime)

        val document = getOrFetchDocument(link)
        val medias = parseMedias(link, document, titles)
        val isHub = (season == null && medias.size > 1)

        Log.d(ANIMESAMA_LOG, "media number = ${medias.size}")

        if (isHub) {
            anime.fetch_type = FetchType.Seasons
            anime.season_number = HUB_SEASON_NUMBER
        }

        anime.populateFromDocument(document)

        val effectiveLink = if (medias.size == 1 && medias[0].url.isNotBlank()) medias[0].url else link
        val effectiveSeason = season ?: if (medias.isNotEmpty()) medias[0].season else null

        val contentType = ContentType.from(anime.title, effectiveSeason ?: effectiveLink)
        val isMovie = (effectiveSeason != null && effectiveSeason.startsWith("Film", ignoreCase = true)) ||
            effectiveLink.contains("film", ignoreCase = true) ||
            contentType == ContentType.MOVIE ||
            (medias.size == 1 && medias[0].url.contains("film", ignoreCase = true))

        val tvdbMetadata = fetchTvdbForPanel(anime.title, effectiveSeason, anime.title, titles, isMovie = isMovie)
        anime.enrichWithTvdb(tvdbMetadata, document, effectiveSeason, isHub)

        anime.checkAndReportIncompleteness(baseUrl, ::getAnimeUrl)
        return anime
    }

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

        val year = tvdbMetadata?.releaseDate ?: rawYear
        if (description.isNullOrEmpty()) description = buildDescription(descriptionText, year)
    }

    // ============================== Season ==============================
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        Log.d(ANIMESAMA_LOG, "getSeasonList: input url = '${anime.url}'")
        val (parsedUrl, newUrl) = urlParser(anime.url)
        Log.d(ANIMESAMA_LOG, "getSeasonList: parsedUrl = $parsedUrl, newUrl = $newUrl")
        if (!newUrl) return getLegacySeasonList(anime)

        val link = parsedUrl.url
        val document = getOrFetchDocument(link)

        return parseMedias(link, document, parsedUrl.titles).parallelMap { media ->
            val rawSeason = media.season.orEmpty()
            val fullTitle = formatSeasonTitle(anime.title, rawSeason, media.titles)
            val tvdbMetadata = fetchTvdbForPanel(anime.title, rawSeason, fullTitle, media.titles)

            SAnime.create().apply {
                title = fullTitle
                url = UrlContent(
                    url = media.url,
                    titles = parsedUrl.titles,
                    season = rawSeason,
                ).toJsonString(json)
                thumbnail_url = tvdbMetadata?.seasonPosterUrl ?: tvdbMetadata?.mainPosterUrl ?: document.getElementById("coverOeuvre")?.attr("abs:src")
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

        var link = parsedUrl.url
        var rawSeason = parsedUrl.season
        Log.d(ANIMESAMA_LOG, "getEpisodeList: starting with link='$link', rawSeason='$rawSeason'")

        val preDoc = getOrFetchDocument(link)
        val medias = parseMedias(link, preDoc, parsedUrl.titles)
        Log.d(ANIMESAMA_LOG, "getEpisodeList: found ${medias.size} medias for link='$link'")

        val isHub = (rawSeason == null && medias.size > 1)
        if (isHub) {
            Log.d(ANIMESAMA_LOG, "getEpisodeList: isHub=true for '$link'")
            return listOf(
                SEpisode.create().apply {
                    url = ""
                    name = ""
                },
            )
        }

        if (medias.size == 1 && medias[0].url != link && medias[0].url.isNotBlank()) {
            Log.d(ANIMESAMA_LOG, "getEpisodeList: medias.size==1, updating link from '$link' to '${medias[0].url}'")
            getOrFetchDocument(medias[0].url)
            link = medias[0].url
        }

        if (rawSeason == null && medias.isNotEmpty()) {
            rawSeason = medias[0].season
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
                throw IllegalStateException("Aucun épisode n'a pu être récupéré sur Anime-Sama. Vérifiez votre connexion Internet.")
            }

        val titles = parsedUrl.titles
        val fullTitle = formatSeasonTitle(anime.title, rawSeason.orEmpty(), titles)
        val contentType = ContentType.from(anime.title, rawSeason ?: link)
        val isMovie = (rawSeason != null && rawSeason.startsWith("Film", ignoreCase = true)) || link.contains("film", ignoreCase = true) || contentType == ContentType.MOVIE
        val tvdbMetadata = fetchTvdbForPanel(anime.title, rawSeason, fullTitle, titles, isMovie = isMovie)

        // 3. Gestion de l'overflow (Saisons avec OAV rajoutés en fin de liste)
        val tvdbEpCount = tvdbMetadata?.episodeSummaries?.size ?: 0
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
        }
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
        }.flatten()

        return videos.checkAndReportVideoIssues(baseUrl, hoster.hosterName, hoster.hosterName)
    }

    // ============================== Utils ===============================
    private fun List<EpisodePlayers>.episodesPlayersToSEpisodes(
        tvdbMetadata: TvdbMetadata?,
        season: String?,
        animeTitle: String = "",
        s0Metadata: TvdbMetadata? = null,
    ): List<SEpisode> {
        val contentType = ContentType.from(animeTitle, season ?: "")
        val defaultPrefix = contentType.getPrefix()
        val tvdbEpCount = tvdbMetadata?.episodeSummaries?.size ?: 0
        val episodeOffset = tvdbMetadata?.episodeOffset ?: 0

        return this.map { episode ->
            val epNum = episode.episodeNumber
            val (prefix, epMeta) = resolveEpisodeMetadata(epNum, tvdbMetadata, s0Metadata, contentType)
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
    ): Pair<String, Triple<String?, String?, String?>?> {
        val offset = tvdbMetadata?.episodeOffset ?: 0
        val tvdbEpCount = tvdbMetadata?.episodeSummaries?.size ?: 0
        val defaultPrefix = contentType.getPrefix()

        val epMeta = tvdbMetadata?.episodeSummaries?.get(epNum + offset)
        if (isSeasonOverflow(epMeta, contentType, epNum, tvdbEpCount, s0Metadata)) {
            val s0Meta = s0Metadata?.episodeSummaries?.get(epNum - tvdbEpCount)
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

            cleanTvdbName.matches(Regex("""(?i)^(?:Épisode|Episode)\s*\d+$""")) -> "Épisode $epNum"

            cleanTvdbName.matches(Regex("""(?i)^(?:Épisode|Episode)\s*\d+\s*[-:]\s*(.+)""")) -> {
                val realTitle = Regex("""(?i)^(?:Épisode|Episode)\s*\d+\s*[-:]\s*(.+)""").find(cleanTvdbName)?.groupValues?.get(1)?.trim()
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

        if (cleanSeason.matches(Regex("""(?i)^Saison\s*\d+.*"""))) {
            val shortSeason = cleanSeason
                .replace(Regex("""(?i)\s*Saison\s*"""), " ")
                .replace(Regex("""(?i)Partie\s*(\d+)"""), "Part $1")
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
            client.get(targetUrl, headers).asJsoup().also { doc ->
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
        return uncommented.lines()
            .map { it.trim() }
            .mapNotNull { line ->
                panneauRegex.find(line)?.let { match ->
                    val name = match.groupValues[1]
                    val rawUrl = match.groupValues[2]
                    val rawFolder = rawUrl.substringBefore("/")
                    val subFolder = if (rawFolder.lowercase() in langList) "" else rawFolder
                    val urlClean = subFolder.safeRelativePath("$baseUrl$hubLink/").removeSuffix("/")
                    Log.d(ANIMESAMA_LOG, "parseMedias: found rawUrl = '$rawUrl' -> urlClean = '$urlClean'")
                    UrlContent(
                        url = urlClean,
                        titles = titles,
                        season = name,
                    )
                }
            }.distinctBy { it.url }
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
        val link = jsonUrl.substringBefore("#")

        val titleFromUrl = jsonUrl.substringAfter("|", "").takeIf { it.isNotBlank() }
        val slugTitle = link.substringAfterLast("/").replace("-", " ")

        val titles: Set<String> = buildSet {
            titleFromUrl?.let { add(it) }
            add(slugTitle)
        }
        val parsedUrl = UrlContent(link, titles, null)
        Pair(parsedUrl, false)
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
    }
}
