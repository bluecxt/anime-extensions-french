package eu.kanade.tachiyomi.animeextension.fr.dessinanime

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto.SearchItemDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DESSINANIME_LOG
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.Source
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.safeRelativePath
import fr.bluecxt.core.utils.PlaylistUtils
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup.parse
import java.net.URLEncoder

class DessinAnime :
    Source(),
    CommonPreferences {

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")

    override val supportedServers = listOf("Minochinos", "Abysse", "Uqload")
    override val lang: String = "fr"
    override val supportsLatest: Boolean = true
    override val name: String = "Dessin Anime"
    override val defaultBaseUrl: String = "https://dessinanime.cc"

    // =============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = parseAnimePage("$baseUrl/catalogue?sortField=popularity&page=$page".toHttpUrl())

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = parseAnimePage("$baseUrl/catalogue?sortField=releaseDate&page=$page".toHttpUrl())

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
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
        Log.d(DESSINANIME_LOG, "Filters list size: ${filters.size}")
        filters.forEach { filter ->
            Log.d(DESSINANIME_LOG, "Filter: name='${filter.name}', class='${filter.javaClass.simpleName}', state='${filter.state}'")
        }

        val url = "$baseUrl/catalogue".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is SortFieldFilter -> addQueryParameter("sortField", getOptions("SORT_FIELDS")[filter.state].second)
                    is SortOrderFilter -> addQueryParameter("sortOrder", getOptions("SORT_ORDERS")[filter.state].second)
                    is MediaTypeFilter -> addQueryParameter("mediaType", getOptions("MEDIA_TYPES")[filter.state].second)
                    is GenreFilter -> addQueryParameter("genreId", getOptions("GENRES")[filter.state].second)
                    is CategoryFilter -> addQueryParameter("category", getOptions("CATEGORIES")[filter.state].second)
                    is StatusFilter -> addQueryParameter("status", getOptions("STATUSES")[filter.state].second)
                    is CountryFilter -> if (filter.state.isNotBlank()) addQueryParameter("country", filter.state.trim())
                    is YearFilter -> if (filter.state.isNotBlank()) addQueryParameter("releaseYear", filter.state.trim())
                    is MinRatingFilter -> if (filter.state.isNotBlank()) addQueryParameter("minRating", filter.state.trim())
                    is MaxRatingFilter -> if (filter.state.isNotBlank()) addQueryParameter("maxRating", filter.state.trim())
                    else -> {}
                }
            }
        }.build()

        Log.d(DESSINANIME_LOG, "Catalogue URL: $url")

        return parseAnimePage(url)
    }

    // =============================== Utils ===============================

    private suspend fun parseAnimePage(pageUrl: HttpUrl): AnimesPage {
        val response = client.newCall(GET(pageUrl, headers)).awaitSuccess()
        val document = response.asJsoup().apply { resolveSuspense() }

        val animes = document.select("div.group").mapNotNull { element ->
            val thumbnail = element.selectFirst("img")?.attr("abs:src") ?: POSTER_PLACEHOLDER
            SAnime.create().apply {
                title = element.selectFirst("a")?.text() ?: ""
                thumbnail_url = thumbnail
                url = element.selectFirst("a")?.safeRelativePath()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            }
        }

        // le mieux serait un "a:has(svg.lucide-chevron-right)" mais le site est stupide et a pleins de page vide
        val minPageSize = 10
        val hasNextPage = animes.size >= minPageSize

        return AnimesPage(animes, hasNextPage)
    }

    // ============================ Details =============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess()
        var document = response.body.string()
        var soup = parse(document, "$baseUrl${anime.url}").apply { resolveSuspense() }

        val seasons = soup.select("a.bg-card")
        val isHub = if (seasons.size > 1) true else false
        val isSeason = if (seasons.size == 0 && !document.contains("Film")) true else false

        if (!isSeason) {
            anime.apply {
                description = soup.selectFirst("meta[name=description]")?.attr("content") ?: ""
                thumbnail_url = soup.selectFirst("img[alt=${anime.title}], amg[alt=Season poster]")?.attr("abs:src") ?: anime.title
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
        val soup = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess().asJsoup().apply { resolveSuspense() }

        val siteSeasons = soup.select("a.bg-card").mapNotNull { element ->
            val saisonNum = element.selectFirst("p.line-clamp-1")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
            val path = element.safeRelativePath().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val seasonTitle = if (saisonNum == 1) anime.title else "${anime.title} Saison $saisonNum"
            Triple(seasonTitle, path, saisonNum)
        }.sortedBy { it.third }

        return siteSeasons.mapIndexed { index, (sTitle, sUrl, siteSNum) ->
            SAnime.create().apply {
                title = sTitle
                url = sUrl
                val poster = soup.selectFirst("a[href='$sUrl'] img")?.attr("abs:src") ?: ""
                thumbnail_url = if (poster.isNotEmpty() && !poster.contains("null")) poster else anime.thumbnail_url
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
        // serie
        val episodes = if (episodeList.isNotEmpty()) {
            episodeList.mapNotNull { element ->
                val epName = element.selectFirst("p.text-sm")?.text()?.substringBefore("(")?.trim()
                Log.d(DESSINANIME_LOG, "epname = $epName")
                val link = element.safeRelativePath().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Log.d(DESSINANIME_LOG, "link = $link")
                val sNum = "$baseUrl$link".toHttpUrl().pathSegments.getOrNull(2)?.toIntOrNull() ?: 1
                Log.d(DESSINANIME_LOG, "epname = $epName")
                SEpisode.create().apply {
                    episode_number = link.removeSuffix("/").substringAfterLast("/").toFloatOrNull() ?: 1f
                    Log.d(DESSINANIME_LOG, "ep number = $episode_number, url = $link")
                    name = buildString {
                        if (sNum > 1) append("[S$sNum] ")
                        append("Episode ${episode_number.toInt()}")
                        if (epName != null && !epName.contains("Episode")) append(" - $epName")
                    }
                    url = link
                    summary = element.selectFirst("p.text-muted-foreground")?.text() ?: ""
                    preview_url = element.selectFirst("img")?.attr("src") ?: ""
                }
            }
        } else {
            Log.d(DESSINANIME_LOG, "is movie")
            val posterRegex = Regex("""\\"posterPath\\":\\"(.*?)\\"""")
            listOf(
                SEpisode.create().apply {
                    episode_number = 1F
                    name = "[Movie] ${anime.title}"
                    preview_url = posterRegex.find(document)?.groupValues?.get(1)
                    url = anime.url
                },
            )
        }
        return episodes.sortedWith(compareBy { it.episode_number }).asReversed()
    }

    // ============================ Hosters =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.newCall(GET("$baseUrl${episode.url}", headers)).awaitSuccess()
        val html = response.body.string()

        val hosterList = mutableListOf<Hoster>()

        val playerBlockRegex = Regex("""\{\\"type\\":\\"(?<type>[^"\\]+)\\",\\"sources\\":\[(?<sources>.*?)\],\\"host\\":\\"(?<host>[^"\\]+)\\",\\"slug\\":\\"(?<slug>[^"\\]+)\\",\\"iframe_url\\":\\"(?<iframeUrl>[^"\\]+)\\"\}""")
        val sourceRegex = Regex("""\\"label\\":\\"(?<label>[^"\\]+)\\",\\"source\\":\\"(?<url>https://extractor\.nmlnode\.cc/proxy/[^"\\]+)\\"""")

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
            val iframeRegex = Regex("""\\"iframe_url\\":\\"(?<url>https://[^"\\]+)\\"""")
            val iframeUrls = iframeRegex.findAll(html)
                .map { it.groups["url"]?.value?.replace("\\/", "/") }
                .filterNotNull()
                .distinct()
                .toList()

            iframeUrls.forEach { iframeUrl ->
                val serverName = getServerName(iframeUrl, supportedServers) ?: return@forEach
                hosterList.add(
                    Hoster(hosterUrl = iframeUrl, hosterName = serverName, internalData = "#$serverName"),
                )
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
                    runCatching {
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
                runCatching {
                    extractVideos(url, lang, supportedServers)
                }.getOrElse { emptyList() }
            }
        }
    }

    // =============================== Filters ===============================
    override fun getFilterList() = FILTER_LIST

    class SortFieldFilter : AnimeFilter.Select<String>("Tri", getOptions("SORT_FIELDS").map { it.first }.toTypedArray(), 0)
    class SortOrderFilter : AnimeFilter.Select<String>("Ordre", getOptions("SORT_ORDERS").map { it.first }.toTypedArray(), 0)
    class MediaTypeFilter : AnimeFilter.Select<String>("Média", getOptions("MEDIA_TYPES").map { it.first }.toTypedArray(), 0)
    class GenreFilter : AnimeFilter.Select<String>("Genre", getOptions("GENRES").map { it.first }.toTypedArray(), 0)
    class CategoryFilter : AnimeFilter.Select<String>("Style", getOptions("CATEGORIES").map { it.first }.toTypedArray(), 0)
    class StatusFilter : AnimeFilter.Select<String>("Statut", getOptions("STATUSES").map { it.first }.toTypedArray(), 0)

    class CountryFilter : AnimeFilter.Text("Pays (ex: FR, US)", "")
    class YearFilter : AnimeFilter.Text("Année (ex: 2024)", "")
    class MinRatingFilter : AnimeFilter.Text("Note min (0-10)", "")
    class MaxRatingFilter : AnimeFilter.Text("Note max (0-10)", "")

    val FILTER_LIST get() = AnimeFilterList(
        SortFieldFilter(),
        SortOrderFilter(),
        MediaTypeFilter(),
        GenreFilter(),
        CategoryFilter(),
        StatusFilter(),
        AnimeFilter.Separator(),
        CountryFilter(),
        YearFilter(),
        MinRatingFilter(),
        MaxRatingFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_FALLBACK_KEY
            title = "Desactiver le proxy"
            summary = "Plus lent, a utiliser si aucun serveur n'est trouvée"
            setDefaultValue(PREF_USE_FALLBACK_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(PREF_USE_FALLBACK_KEY, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)
    }

    private fun org.jsoup.nodes.Document.resolveSuspense() {
        val rsRegex = Regex("""\${'$'}RS\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)""")
        this.select("script").forEach { script ->
            val content = script.data()
            rsRegex.findAll(content).forEach { match ->
                val sourceId = match.groupValues.getOrNull(1) ?: return@forEach
                val targetId = match.groupValues.getOrNull(2) ?: return@forEach
                val sourceEl = this.getElementById(sourceId)
                val targetEl = this.getElementById(targetId)
                if (sourceEl != null && targetEl != null) {
                    val nodes = sourceEl.childNodes().toList()
                    nodes.forEach { node ->
                        targetEl.appendChild(node)
                    }
                }
            }
        }
    }

    companion object {
        private const val PREF_USE_FALLBACK_KEY = "use_fallback_servers"
        private const val PREF_USE_FALLBACK_DEFAULT = false
        private const val POSTER_PLACEHOLDER = "https://placehold.co/300x450/262626/f59e0b.png?text=DessinAnime.cc%5CnPas%20d%27affiche"

        private val filterData by lazy {
            val jsonStream = DessinAnime::class.java.getResourceAsStream("filters.json")
            val jsonString = jsonStream?.bufferedReader()?.use { it.readText() } ?: "{}"
            try {
                Json.decodeFromString<Map<String, List<List<String>>>>(jsonString)
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun getOptions(key: String): List<Pair<String, String>> = filterData[key]?.map { it[0] to it[1] } ?: emptyList()
    }
}
