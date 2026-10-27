// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.dessinanime

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto.CatalogueDto
import eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto.DessinAnimeMediaDto
import eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto.DessinAnimePosterDto
import eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto.HosterItemDto
import eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto.HosterPayloadDto
import eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto.PlayerEmbedDto
import eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto.SearchItemDto
import eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto.SeriesDataDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType.Episodes
import eu.kanade.tachiyomi.animesource.model.FetchType.Seasons
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DESSINANIME_LOG
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.Source
import fr.bluecxt.core.filters.FilterSpec
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import fr.bluecxt.core.utils.runCatchingCancellable
import fr.bluecxt.core.utils.safeRelativePath
import keiyoushi.core.R
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.get
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelMapNotNullBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup.parse
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

private const val MIN_PAGE_SIZE_JSON = 36
private const val MIN_PAGE_SIZE_HTML = 10

private val rsRegex = Regex("""\${'$'}RS\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)""")
private val iframeRegex = Regex("""\"iframe_url\":\"(?<url>https://[^"\\]+)\"""")
private val playerBlockRegex = Regex("""{\"type\":\"(?<type>[^"\\]+)\",\"sources\":\[(?<sources>.*?)\],\"host\":\"(?<host>[^"\\]+)\",\"slug\":\"(?<slug>[^"\\]+)\",\"iframe_url\":\"(?<iframeUrl>[^"\\]+)\"\}""")
private val sourceRegex = Regex("""\"label\":\"(?<label>[^"\\]+)\",\"source\":\"(?<url>https://extractor\.nmlnode\.cc/proxy/[^"\\]+)\"""")
private val qualityRegex = Regex("""\d+p""")

class DessinAnime :
    Source(),
    CommonPreferences {

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    override val supportedServers = listOf("Minochinos", "Abysse", "Uqload")
    override val lang: String = "fr"
    override val supportsLatest: Boolean = true
    override val name: String = "Dessin Anime"
    override val defaultBaseUrl: String = "https://dessinanime.cc"

    // =============================== Popular ===============================

    private val paginationMapPopular = ConcurrentHashMap<Int, String>()
    private val paginationMutex = Mutex()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/catalogue")

        paginationMutex.withLock {
            if (page == 1) {
                paginationMapPopular.clear()
            } else {
                val cursor = paginationMapPopular[page]
                if (cursor != null) {
                    urlBuilder.addQueryParameter("cursor", cursor)
                }
            }
        }

        val data: List<CatalogueDto> = client.get(urlBuilder.build(), headers).parseAs(json)

        val localUseEarlyFetch = this.useEarlyFetch
        val animes = data.map { item ->
            val anime = item.toSAnime()
            if (localUseEarlyFetch && !anime.url.contains("/movie/")) {
                val isHub = runCatchingCancellable {
                    val doc = client.get("$baseUrl${anime.url}", headers).useAsJsoup().apply { resolveSuspense() }
                    val seriesData = doc.extractNextJs<SeriesDataDto> { it is JsonObject && "seasons" in it.jsonObject }
                    val count = seriesData?.seasons?.size ?: doc.select("a.bg-card").size
                    count > 1
                }.getOrDefault(false)
                if (isHub) {
                    anime.coreSetFetchType(Seasons)
                }
            }
            anime
        }

        val hasNextPage = data.size >= MIN_PAGE_SIZE_JSON

        if (hasNextPage) {
            val lastElementId = data.last().id
            paginationMutex.withLock {
                paginationMapPopular[page + 1] = lastElementId.toString()
            }
            Log.d(DESSINANIME_LOG, "lastId = $lastElementId")
        }
        Log.d(DESSINANIME_LOG, "page = $page hasNextPage=$hasNextPage")
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val document = client.newCall(GET(baseUrl, headers)).awaitSuccess().use { it.useAsJsoup() }.apply { resolveSuspense() }

        val animes = document.select("div[data-slot=carousel] a.group")
            .asSequence()
            .mapNotNull { element ->
                val rawPath = element.safeRelativePath() ?: return@mapNotNull null
                val pathSegments = rawPath.removePrefix("/").split("/").filter { it.isNotEmpty() }
                val canonicalUrl = if (pathSegments.size >= 2) "/${pathSegments[0]}/${pathSegments[1]}" else rawPath

                SAnime.create().apply {
                    url = canonicalUrl

                    val rawImgSrc = element.selectFirst("img")?.attr("src")
                    thumbnail_url = rawImgSrc?.nextJsToDirectUrl() ?: POSTER_PLACEHOLDER

                    title = element.selectFirst("div.truncate")?.text() ?: ""
                }
            }
            .distinctBy { it.url }
            .toList()

        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.length == 1) return AnimesPage(emptyList(), false)
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$baseUrl/api/search?q=$encodedQuery"
            val searchItems: List<SearchItemDto> = client.get(searchUrl, headers).parseAs(json)
            val animes = searchItems.map { it.toSAnime() }
            return AnimesPage(animes, false)
        }
        val url = "$baseUrl/catalogue".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            applyFilters(filters)
        }.build()

        Log.d(DESSINANIME_LOG, "Catalogue URL: $url")

        return parseAnimePage(url)
    }

    // =============================== Utils ===============================

    private fun String.nextJsToDirectUrl(): String = if (!this.contains("url=")) {
        this
    } else {
        this.substringAfter("url=")
            .replace("%3A", ":", ignoreCase = true)
            .replace("%2F", "/", ignoreCase = true)
            .substringBefore("&")
    }

    private suspend fun parseAnimePage(pageUrl: HttpUrl): AnimesPage {
        val document = client.newCall(GET(pageUrl, headers)).awaitSuccess().use { it.useAsJsoup() }.apply { resolveSuspense() }

        val animes = document.select("div.group")
            .asSequence()
            .mapNotNull { element ->
                val thumbnail = element.selectFirst("img")?.attr("abs:src")
                    ?: element.selectFirst("img")?.attr("src")?.nextJsToDirectUrl()
                    ?: POSTER_PLACEHOLDER
                SAnime.create().apply {
                    title = element.selectFirst("a")?.text() ?: ""
                    thumbnail_url = thumbnail
                    url = element.selectFirst("a")?.safeRelativePath()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                }
            }
            .toList()

        val hasNextPage = animes.size >= MIN_PAGE_SIZE_HTML

        return AnimesPage(animes, hasNextPage)
    }

    // ============================ Details =============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val pathSegments = anime.url.removePrefix("/").split("/").filter { it.isNotEmpty() }
        val targetSeasonNum = pathSegments.getOrNull(2)?.toIntOrNull()
        val isSeasonPage = targetSeasonNum != null

        val url = "$baseUrl${anime.url}"
        val document = client.newCall(GET(url, headers)).awaitSuccess().use { it.body.string() }
        val soup = parse(document, url).apply { resolveSuspense() }

        val posterDto = soup.extractNextJs<DessinAnimePosterDto> {
            it is JsonObject && it.jsonObject["variant"]?.jsonPrimitive?.contentOrNull == "poster"
        }
        val mediaDto = soup.extractNextJs<DessinAnimeMediaDto>()
        val seriesData = soup.extractNextJs<SeriesDataDto> { it is JsonObject && "seasons" in it.jsonObject }

        val isMovie = anime.url.contains("/movie/") || mediaDto?.mediaType.equals("MOVIE", true)
        val seasonCount = seriesData?.seasons?.size ?: soup.select("a.bg-card").size
        val isHub = !isSeasonPage && seasonCount > 1

        val seasonSpecificPoster = if (targetSeasonNum != null && seriesData != null) {
            seriesData.seasons.firstOrNull { it.seasonNumber == targetSeasonNum }?.posterPath
        } else {
            null
        }

        anime.apply {
            title = mediaDto?.title?.takeIf { it.isNotBlank() } ?: title
            thumbnail_url = seasonSpecificPoster ?: anime.thumbnail_url?.takeIf { it.isNotBlank() && it != POSTER_PLACEHOLDER } ?: resolveThumbnail(soup, posterDto)
            description = soup.selectFirst("meta[name=description]")?.attr("content") ?: ""
            genre = extractGenres(soup)
            initialized = true
        }

        if (isHub) {
            anime.coreSetFetchType(Seasons)
        } else if (isMovie || soup.selectFirst("a.group.rounded-xl") == null) {
            anime.status = SAnime.COMPLETED
        }
        return anime
    }

    private fun resolveThumbnail(soup: Document, posterDto: DessinAnimePosterDto?): String = posterDto?.src
        ?: soup.selectFirst("link[rel=preload][as=image][href*='image.tmdb.org/t/p/w500']")?.attr("abs:href")
        ?: soup.selectFirst("meta[property='og:image']")?.attr("content")
        ?: POSTER_PLACEHOLDER

    private fun extractGenres(soup: Document): String = soup.select("span.text-sand:not(:has(svg)), span.text-foreground.rounded-full:not(:has(svg))")
        .asSequence()
        .map { it.text().trim() }
        .filter { it.isNotBlank() && !it.contains("·") && !it.contains("Série") && !it.contains("Film") }
        .distinct()
        .joinToString(", ")

    // ============================ Seasons =============================

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val soup = client.get("$baseUrl${anime.url}", headers).useAsJsoup().apply { resolveSuspense() }
        val seriesData = soup.extractNextJs<SeriesDataDto> { it is JsonObject && "seasons" in it.jsonObject }

        if (seriesData != null && seriesData.seasons.size > 1) {
            return seriesData.seasons.mapIndexed { index, season ->
                buildSeasonAnime(anime, season, index, seriesData.seasons.size)
            }
        }

        return parseHtmlSeasons(soup, anime)
    }

    private fun buildSeasonAnime(anime: SAnime, season: eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto.SeasonItemDto, index: Int, totalSeasons: Int): SAnime {
        val sNum = season.seasonNumber
        val sTitle = season.customTitle?.takeIf { it.isNotBlank() }
            ?: season.title?.takeIf { it.isNotBlank() }
            ?: if (sNum == 1) anime.title else "${anime.title} Saison $sNum"

        return SAnime.create().apply {
            title = sTitle
            url = "${anime.url}/$sNum/1"
            thumbnail_url = season.posterPath ?: anime.thumbnail_url
            status = if (index < totalSeasons - 1) SAnime.COMPLETED else anime.status
            coreSetFetchType(Episodes)
            coreSetSeasonNumber(HUB_SEASON_NUMBER)
            initialized = true
        }
    }

    private fun parseHtmlSeasons(soup: Document, anime: SAnime): List<SAnime> {
        val siteSeasons = soup.select("a.bg-card").mapNotNull { element ->
            val saisonNum = element.selectFirst("p.line-clamp-1")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
            val path = element.safeRelativePath() ?: return@mapNotNull null
            val seasonTitle = if (saisonNum == 1) anime.title else "${anime.title} Saison $saisonNum"
            Triple(seasonTitle, path, saisonNum)
        }.sortedBy { it.third }

        return siteSeasons.mapIndexed { index, (sTitle, sUrl, _) ->
            SAnime.create().apply {
                title = sTitle
                url = sUrl
                val poster = soup.selectFirst("a[href='$sUrl'] img")?.attr("abs:src") ?: ""
                thumbnail_url = anime.thumbnail_url ?: poster
                status = if (index < siteSeasons.size - 1) SAnime.COMPLETED else anime.status
                coreSetFetchType(Episodes)
                coreSetSeasonNumber(HUB_SEASON_NUMBER)
                initialized = true
            }
        }
    }

    // ============================ Episodes =============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess().use { it.body.string() }
        val soup = parse(document, "$baseUrl${anime.url}").apply { resolveSuspense() }

        if (anime.url.contains("/movie/") || document.contains("\"mediaType\":\"MOVIE\"")) {
            return buildMovie(anime)
        }

        val seriesData = soup.extractNextJs<SeriesDataDto> { it is JsonObject && "seasons" in it.jsonObject }
        val pathSegments = anime.url.removePrefix("/").split("/").filter { it.isNotEmpty() }
        val isTargetingSpecificSeason = pathSegments.size >= 3

        val seasonCount = seriesData?.seasons?.size ?: soup.select("a.bg-card").size
        val isHub = !isTargetingSpecificSeason && seasonCount > 1
        if (isHub) {
            Log.d(DESSINANIME_LOG, "getEpisodeList: isHub=true for '${anime.url}'")
            return listOf(
                SEpisode.create().apply {
                    url = ""
                    name = "⚠ Activez Early Fetch"
                    summary = "Ouvrez les paramètres de cette extension, activez « Early Fetch », puis videz la base locale de cette extension."
                },
            )
        }

        if (seriesData != null && seriesData.seasons.isNotEmpty()) {
            val baseAnimeUrl = if (pathSegments.size >= 2) "/${pathSegments[0]}/${pathSegments[1]}" else anime.url
            val sNum = pathSegments.getOrNull(2)?.toIntOrNull()
            val episodes = seriesData.toEpisodeList(baseAnimeUrl, targetSeason = sNum)
            if (episodes.isNotEmpty()) {
                return episodes
            }
        }

        return parseHtmlEpisodes(soup, anime)
    }

    private suspend fun parseHtmlEpisodes(soup: Document, anime: SAnime): List<SEpisode> {
        var episodeList = soup.select("a.group.rounded-xl")
        if (episodeList.isEmpty() && soup.selectFirst("a.bg-card") != null) {
            val doc1 = client.newCall(GET("$baseUrl${anime.url}/1/1", headers)).awaitSuccess().use { it.body.string() }
            val soup1 = parse(doc1, "$baseUrl${anime.url}/1/1").apply { resolveSuspense() }
            episodeList = soup1.select("a.group.rounded-xl")
        }

        return if (episodeList.isNotEmpty()) {
            parseSerieEpisodes(episodeList).sortedWith(compareBy { it.episode_number }).asReversed()
        } else {
            buildMovie(anime)
        }
    }

    private fun parseSerieEpisodes(episodeList: Elements): List<SEpisode> = episodeList.mapNotNull { element ->
        val epName = element.selectFirst("p.text-sm")?.text()?.substringBefore("(")?.trim()
        val link = element.safeRelativePath() ?: return@mapNotNull null
        val sNum = "$baseUrl$link".toHttpUrl().pathSegments.getOrNull(2)?.toIntOrNull() ?: 1
        SEpisode.create().apply {
            episode_number = link.removeSuffix("/").substringAfterLast("/").toFloatOrNull() ?: 1f
            name = buildString {
                if (sNum > 1) append("[S$sNum] ")
                append("Episode ${episode_number.toInt()}")
                if (epName != null && !epName.contains("Episode")) append(" - $epName")
            }
            url = link
            summary = element.selectFirst("p.text-muted-foreground")?.text() ?: ""
            preview_url = element.selectFirst("img")?.attr("src")?.nextJsToDirectUrl() ?: ""
        }
    }

    private fun buildMovie(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            episode_number = 1F
            name = "[Movie] ${anime.title}"
            preview_url = anime.thumbnail_url
            url = anime.url
        },
    )

    // ============================ Hosters =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val html = client.newCall(GET("$baseUrl${episode.url}", headers)).awaitSuccess().use { it.body.string() }
        val soup = parse(html, "$baseUrl${episode.url}").apply { resolveSuspense() }

        val playerEmbed = soup.extractNextJs<PlayerEmbedDto> { it is JsonObject && "players" in it.jsonObject }
        val nextJsHosters = playerEmbed?.toHosterList().orEmpty()
        if (nextJsHosters.isNotEmpty()) {
            return nextJsHosters
        }

        val collectedItems = mutableListOf<HosterItemDto>()

        val relayUrl = soup.selectFirst("video.art-video")?.attr("src")?.takeIf { it.isNotBlank() }
        if (relayUrl != null) {
            collectedItems.add(HosterItemDto(serverName = "Direct", url = relayUrl, language = "VF"))
        }

        collectedItems += extractLegacyHosters(html)

        val useFallback = preferences.getBoolean(PREF_USE_FALLBACK_KEY, PREF_USE_FALLBACK_DEFAULT)
        if (useFallback) {
            collectedItems += extractIframeFallback(html)
        }

        return collectedItems.groupBy { "${it.serverName} (${it.language})" }.map { (displayName, items) ->
            Hoster(
                hosterName = displayName,
                hosterUrl = items.first().url,
                internalData = HosterPayloadDto(items).toJsonString(),
            )
        }
    }

    private fun extractLegacyHosters(html: String): List<HosterItemDto> {
        val items = mutableListOf<HosterItemDto>()
        playerBlockRegex.findAll(html).forEach { match ->
            val host = match.groups["host"]?.value ?: "unknown"
            val sources = match.groups["sources"]?.value ?: ""
            sourceRegex.findAll(sources).forEach { srcMatch ->
                val label = srcMatch.groups["label"]?.value ?: "MULTI"
                val url = srcMatch.groups["url"]?.value?.replace("\\/", "/") ?: return@forEach
                val isQuality = label.contains(qualityRegex)
                val lang = if (isQuality) "VF" else label
                items.add(HosterItemDto(serverName = host, url = url, language = lang))
            }
        }
        return items
    }

    private fun extractIframeFallback(html: String): List<HosterItemDto> = iframeRegex.findAll(html)
        .mapNotNull { it.groups["url"]?.value?.replace("\\/", "/") }
        .distinct()
        .mapNotNull { url ->
            val server = getServerName(url, supportedServers) ?: return@mapNotNull null
            HosterItemDto(serverName = server, url = url, language = "VF")
        }
        .toList()

    // =============================== Video list ===============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val items = runCatching {
            hoster.internalData.parseAs<HosterPayloadDto>().items
        }.getOrElse {
            if (hoster.internalData.contains("#")) {
                hoster.internalData.split("|").map { part ->
                    val p = part.split("#")
                    HosterItemDto(serverName = p.getOrNull(0) ?: "Server", language = p.getOrNull(1) ?: "VF", url = p.getOrNull(2) ?: hoster.hosterUrl)
                }
            } else {
                listOf(HosterItemDto(serverName = hoster.hosterName, url = hoster.hosterUrl, language = "VF"))
            }
        }

        return items.parallelCatchingFlatMap { item ->
            extractServerVideos(item.url.trim(), item.serverName, item.language)
        }
    }

    private suspend fun extractServerVideos(cleanUrl: String, serverName: String, lang: String): List<Video> = when {
        cleanUrl.contains("relay.dessinanime.cc") || serverName.equals("Direct", true) -> {
            listOf(Video(videoUrl = cleanUrl, videoTitle = "Direct ($lang)", headers = headers))
        }

        cleanUrl.contains("extractor.nmlnode.cc") -> {
            extractProxyVideo(cleanUrl, serverName)
        }

        else -> {
            extractVideos(cleanUrl, lang, supportedServers)
        }
    }

    private suspend fun extractProxyVideo(url: String, serverName: String): List<Video> {
        val proxyHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        return if (url.contains("/proxy/hls")) {
            runCatchingCancellable {
                val playlistUtils = PlaylistUtils(client, headers)
                val extracted = playlistUtils.extractFromHls(
                    playlistUrl = url,
                    referer = "$baseUrl/",
                    masterHeaders = proxyHeaders,
                    videoHeaders = proxyHeaders,
                )
                extracted.map {
                    val video = it.buildFromSource(lang = null, name = serverName)
                    video.copy(videoTitle = "${video.videoTitle} (Proxy)")
                }
            }.getOrElse { emptyList() }
        } else {
            val extSource = ExtractedSource(
                url = url,
                quality = null,
                headers = proxyHeaders,
            )
            val video = extSource.buildFromSource(lang = null, name = serverName)
            listOf(video.copy(videoTitle = "${video.videoTitle} (Proxy)"))
        }
    }

    // =============================== Filters ===============================
    override val customFilters: List<FilterSpec>
        get() = listOf(
            select("Tri", "sortField", SORT_FIELDS_OPTIONS),
            select("Ordre", "sortOrder", SORT_ORDERS_OPTIONS),
            select("Média", "mediaType", MEDIA_TYPES_OPTIONS),
            select("Genre", "genreId", GENRES_OPTIONS),
            select("Style", "category", CATEGORIES_OPTIONS),
            select("Statut", "status", STATUSES_OPTIONS),
            separator,
            text("Pays (ex: FR, US)", "country"),
            text("Année (ex: 2024)", "releaseYear"),
            text("Note min (0-10)", "minRating"),
            text("Note max (0-10)", "maxRating"),
        )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        screen.addSwitchPreference(
            key = PREF_EARLY_FETCH_TYPE_KEY,
            default = PREF_EARLY_FETCH_TYPE_DEFAULT,
            title = getString(R.string.pref_early_fetch_type_title),
            summary = getString(R.string.pref_early_fetch_type_summary),
        )

        val context = screen.context
        androidx.preference.SwitchPreferenceCompat(context).apply {
            key = PREF_USE_FALLBACK_KEY
            title = getString(R.string.pref_disable_proxy_title)
            summary = getString(R.string.pref_disable_proxy_summary)
            setDefaultValue(PREF_USE_FALLBACK_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(PREF_USE_FALLBACK_KEY, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)
    }

    private val useEarlyFetch: Boolean
        get() = preferences.getBoolean(PREF_EARLY_FETCH_TYPE_KEY, PREF_EARLY_FETCH_TYPE_DEFAULT)

    private fun Document.resolveSuspense() {
        for (script in select("script")) {
            val content = script.data()
            if ($$"RS(" !in content) continue

            for (match in rsRegex.findAll(content)) {
                val (sourceId, targetId) = match.destructured
                val sourceEl = getElementById(sourceId) ?: continue
                val targetEl = getElementById(targetId) ?: continue

                sourceEl.childNodes().toList().forEach(targetEl::appendChild)
            }
        }
    }

    companion object {
        private const val PREF_EARLY_FETCH_TYPE_KEY = "early_fetch"
        private const val PREF_EARLY_FETCH_TYPE_DEFAULT = false
        private const val PREF_USE_FALLBACK_KEY = "use_fallback_servers"
        private const val PREF_USE_FALLBACK_DEFAULT = false
        private const val POSTER_PLACEHOLDER = "https://placehold.co/300x450/262626/f59e0b.png?text=DessinAnime.cc%5CnPas%20d%27affiche"

        private val SORT_FIELDS_OPTIONS = arrayOf(
            "Popularité" to "popularity",
            "Note" to "rating",
            "Date de sortie" to "releaseDate",
            "Date d'ajout" to "createdAt",
        )

        private val SORT_ORDERS_OPTIONS = arrayOf(
            "Décroissant" to "desc",
            "Croissant" to "asc",
        )

        private val MEDIA_TYPES_OPTIONS = arrayOf(
            "Tous" to "",
            "Films" to "MOVIE",
            "Séries TV" to "TV",
        )

        private val CATEGORIES_OPTIONS = arrayOf(
            "Tous" to "",
            "Anime" to "ANIME",
            "Cartoon" to "CARTOON",
            "Non animé" to "NOT_ANIMATED",
            "Inconnu" to "UNKNOWN",
        )

        private val STATUSES_OPTIONS = arrayOf(
            "Tous" to "",
            "Sorti" to "Released",
            "Annulé" to "Canceled",
            "Série en cours" to "Returning Series",
            "Terminé" to "Ended",
        )

        private val GENRES_OPTIONS = arrayOf(
            "Tous" to "",
            "Action & Adventure (Série TV)" to "20",
            "Science-Fiction & Fantastique (Série TV)" to "31",
            "Kids (Série TV)" to "27",
            "Familial (Film)" to "8",
            "Animation (Film)" to "3",
            "Comédie (Film)" to "4",
            "Familial (Série TV)" to "26",
            "Action (Film)" to "1",
            "Animation (Série TV)" to "21",
            "Comédie (Série TV)" to "22",
            "Drame (Série TV)" to "25",
            "Crime (Film)" to "5",
            "Documentaire (Film)" to "6",
            "Drame (Film)" to "7",
            "Histoire (Film)" to "10",
            "Horreur (Film)" to "11",
            "Musique (Film)" to "12",
            "Mystère (Film)" to "13",
            "Romance (Film)" to "14",
            "Science-Fiction (Film)" to "15",
            "Téléfilm (Film)" to "16",
            "Thriller (Film)" to "17",
            "Guerre (Film)" to "18",
            "Western (Film)" to "19",
            "Documentaire (Série TV)" to "24",
            "Mystère (Série TV)" to "28",
            "News (Série TV)" to "29",
            "Reality (Série TV)" to "30",
            "Soap (Série TV)" to "32",
            "Talk (Série TV)" to "33",
            "War & Politics (Série TV)" to "34",
            "Western (Série TV)" to "35",
            "Crime (Série TV)" to "23",
            "Aventure (Film)" to "2",
            "Fantastique (Film)" to "9",
        )
    }
}
