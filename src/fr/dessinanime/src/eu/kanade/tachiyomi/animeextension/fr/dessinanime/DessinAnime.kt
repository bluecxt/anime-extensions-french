// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.dessinanime

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto.CatalogueDto
import eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto.SearchItemDto
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
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
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

private val posterRegex = Regex("""\\"posterPath\\":\\"(.*?)\\"""")
private val rsRegex = Regex($$"""\$RS\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)""")
private val iframeRegex = Regex("""\\"iframe_url\\":\\"(?<url>https://[^"\\]+)\\"""")
private val playerBlockRegex = Regex("""\{\\"type\\":\\"(?<type>[^"\\]+)\\",\\"sources\\":\[(?<sources>.*?)\],\\"host\\":\\"(?<host>[^"\\]+)\\",\\"slug\\":\\"(?<slug>[^"\\]+)\\",\\"iframe_url\\":\\"(?<iframeUrl>[^"\\]+)\\"\}""")
private val sourceRegex = Regex("""\\"label\\":\\"(?<label>[^"\\]+)\\",\\"source\\":\\"(?<url>https://extractor\.nmlnode\.cc/proxy/[^"\\]+)\\"""")

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

        val response = client.newCall(GET(urlBuilder.build(), headers)).awaitSuccess()

        val jsonString = response.body.string()
        val data: List<CatalogueDto> = jsonString.parseAs(json)

        val animes = data.map { element ->
            val animeUrl = "/${element.mediaType.lowercase()}/${element.slug}"
            Log.d(DESSINANIME_LOG, "url = $animeUrl")
            SAnime.create().apply {
                title = element.title
                url = animeUrl
                thumbnail_url = element.posterPath
            }
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
        val response = client.newCall(GET(baseUrl, headers)).awaitSuccess()
        val document = response.use { it.useAsJsoup() }.apply { resolveSuspense() }

        val animes = document.select("div[data-slot=carousel] a.group").mapNotNull { element ->
            SAnime.create().apply {
                url = element.safeRelativePath() ?: return@mapNotNull null

                val rawImgSrc = element.selectFirst("img")?.attr("src")
                thumbnail_url = rawImgSrc?.nextJsToDirectUrl() ?: POSTER_PLACEHOLDER

                title = element.selectFirst("div.truncate")?.text() ?: ""
            }
        }

        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.length == 1) return AnimesPage(emptyList(), false)
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$baseUrl/api/search?q=$encodedQuery"
            val response = client.newCall(GET(searchUrl, headers)).awaitSuccess()
            val jsonString = response.body.string()
            val searchItems = json.decodeFromString<List<SearchItemDto>>(jsonString)
            val animes = searchItems.map { dto ->
                SAnime.create().apply {
                    title = dto.title
                    thumbnail_url = if (!dto.posterPath.isNullOrEmpty() && dto.posterPath != "null") dto.posterPath else POSTER_PLACEHOLDER
                    this.url = "/${dto.mediaType.lowercase()}/${dto.slug}"
                }
            }
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
        val response = client.newCall(GET(pageUrl, headers)).awaitSuccess()
        val document = response.useAsJsoup().apply { resolveSuspense() }

        val animes = document.select("div.group").mapNotNull { element ->
            val thumbnail = element.selectFirst("img")?.attr("abs:src") ?: POSTER_PLACEHOLDER
            SAnime.create().apply {
                title = element.selectFirst("a")?.text() ?: ""
                thumbnail_url = thumbnail
                url = element.selectFirst("a")?.safeRelativePath()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            }
        }

        // le mieux serait un "a:has(svg.lucide-chevron-right)" mais le site est stupide et a pleins de page vide
        val hasNextPage = animes.size >= MIN_PAGE_SIZE_HTML

        return AnimesPage(animes, hasNextPage)
    }

    // ============================ Details =============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess()
        var document = response.body.string()
        var soup = parse(document, "$baseUrl${anime.url}").apply { resolveSuspense() }

        val seasons = soup.select("a.bg-card")
        val isHub = (seasons.size > 1)
        val isSeason = if (seasons.isEmpty() && !document.contains("Film")) true else false
        Log.d(DESSINANIME_LOG, "season numbers = ${seasons.size}, isHub = $isHub, isSeason = $isSeason")

        if (!isSeason) {
            val images = soup.select("img[alt]")
            val thumbnail: String = images.firstOrNull { img ->
                val alt = img.attr("alt")
                alt == anime.title || alt == "Season poster"
            }?.attr("abs:src") ?: POSTER_PLACEHOLDER
            anime.apply {
                description = soup.selectFirst("meta[name=description]")?.attr("content") ?: ""
                thumbnail_url = thumbnail
                genre = soup.select("span.text-foreground.rounded-full:not(:has(svg))").map { it.text() }.joinToString()
                initialized = true
            }
        }

        if (isHub) {
            anime.coreSetFetchType(Seasons)
        } else if (soup.selectFirst("a.group.rounded-xl") == null) {
            anime.status = SAnime.COMPLETED
        }
        return anime
    }

    // ============================ Seasons =============================

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val soup = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess().useAsJsoup().apply { resolveSuspense() }

        val siteSeasons = soup.select("a.bg-card").mapNotNull { element ->
            val saisonNum = element.selectFirst("p.line-clamp-1")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
            val path = element.safeRelativePath() ?: return@mapNotNull null
            val seasonTitle = if (saisonNum == 1) anime.title else "${anime.title} Saison $saisonNum"
            Triple(seasonTitle, path, saisonNum)
        }.sortedBy { it.third }

        return siteSeasons.mapIndexed { index, (sTitle, sUrl, siteSNum) ->
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
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess()
        var document = response.body.string()
        var soup = parse(document, "$baseUrl${anime.url}").apply { resolveSuspense() }

        var episodeList = soup.select("a.group.rounded-xl")

        // only one season
        if (soup.selectFirst("a.bg-card") != null) {
            document = client.newCall(GET("$baseUrl${anime.url}/1/1", headers)).awaitSuccess().body.string()
            soup = parse(document, "$baseUrl${anime.url}/1/1").apply { resolveSuspense() }
            episodeList = soup.select("a.group.rounded-xl")
        }

        val episodes = if (episodeList.isNotEmpty()) { // serie
            parseSerieEpisodes(episodeList)
        } else { // movie
            buildMovie(anime, document)
        }
        return episodes.sortedWith(compareBy { it.episode_number }).asReversed()
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

    private fun buildMovie(anime: SAnime, document: String): List<SEpisode> = listOf(
        SEpisode.create().apply {
            episode_number = 1F
            name = "[Movie] ${anime.title}"
            preview_url = posterRegex.find(document)?.groupValues?.get(1)?.nextJsToDirectUrl()
            url = anime.url
        },
    )
    // ============================ Hosters =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.newCall(GET("$baseUrl${episode.url}", headers)).awaitSuccess()
        val html = response.body.string()

        val hosterList = mutableListOf<Hoster>()

        playerBlockRegex.findAll(html).forEach { match ->
            val host = match.groups["host"]?.value ?: "unknown"
            val sources = match.groups["sources"]?.value ?: ""
            sourceRegex.findAll(sources).forEach { srcMatch ->
                val label = srcMatch.groups["label"]?.value ?: "MULTI"
                val url = srcMatch.groups["url"]?.value?.replace("\\/", "/") ?: return@forEach
                val isQuality = label.contains(Regex("""\d+p"""))
                val hostName = if (isQuality) "VF" else label
                hosterList.add(
                    Hoster(
                        hosterUrl = url,
                        hosterName = hostName,
                        internalData = "$label#$host",
                    ),
                )
            }
        }

        val useFallback = preferences.getBoolean(PREF_USE_FALLBACK_KEY, PREF_USE_FALLBACK_DEFAULT)
        if (useFallback) {
            hosterList += iframeRegex.findAll(html)
                .mapNotNull { it.groups["url"]?.value?.replace("\\/", "/") }
                .distinct()
                .mapNotNull { url ->
                    val server = getServerName(url, supportedServers) ?: return@mapNotNull null
                    Hoster(hosterUrl = url, hosterName = server, internalData = "#$server")
                }
        }

        return hosterList.groupBy { it.hosterName }.map { (name, list) ->
            Hoster(
                hosterName = name,
                hosterUrl = list.joinToString("|") { it.hosterUrl },
                internalData = list.joinToString("|") { it.internalData },
            )
        }
    }

    // =============================== Video list ===============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val urls = hoster.hosterUrl.split("|")
        val metadata = hoster.internalData.split("|")

        return urls.zip(metadata).flatMap { (url, meta) ->
            val metaParts = meta.split("#")
            val qualityLabel = metaParts.getOrNull(0) ?: ""
            val originalHost = metaParts.getOrNull(1) ?: ""
            val capitalizedHost = originalHost.replaceFirstChar { it.uppercase() }

            if (url.contains("extractor.nmlnode.cc")) {
                val proxyHeaders = headers.newBuilder()
                    .set("Referer", "$baseUrl/")
                    .build()

                if (url.contains("/proxy/hls")) {
                    runCatchingCancellable {
                        val playlistUtils = PlaylistUtils(client, headers)
                        val extracted = playlistUtils.extractFromHls(
                            playlistUrl = url,
                            referer = "$baseUrl/",
                            masterHeaders = proxyHeaders,
                            videoHeaders = proxyHeaders,
                        )
                        extracted.map {
                            val quality = if (it.quality.isNullOrBlank()) {
                                qualityLabel.takeIf { q -> q.isNotEmpty() && q != "MULTI" && q != "Default" }
                            } else {
                                it.quality
                            }
                            val video = it.copy(quality = quality).buildFromSource(lang = null, name = capitalizedHost)
                            video.copy(videoTitle = "${video.videoTitle} (Proxy)")
                        }
                    }.getOrElse { emptyList() }
                } else {
                    val quality = qualityLabel.takeIf { q -> q.isNotEmpty() && q != "MULTI" && q != "Default" }
                    val extSource = ExtractedSource(
                        url = url,
                        quality = quality,
                        headers = proxyHeaders,
                    )
                    val video = extSource.buildFromSource(lang = null, name = capitalizedHost)
                    listOf(video.copy(videoTitle = "${video.videoTitle} (Proxy)"))
                }
            } else {
                // It's a fallback iframe URL
                runCatchingCancellable {
                    extractVideos(url, lang, supportedServers)
                }.getOrElse { emptyList() }
            }
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
