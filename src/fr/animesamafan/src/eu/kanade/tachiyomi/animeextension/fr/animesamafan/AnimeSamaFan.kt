package eu.kanade.tachiyomi.animeextension.fr.animesamafan

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.Source
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy

class AnimeSamaFan : Source() {
    override val name = "Anime-Sama-Fan"
    override val baseUrl = "https://anime-sama.fan"
    override val lang = "fr"
    override val supportsLatest = true

    override val json: Json by injectLazy()

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidoExtractor by lazy { VidoExtractor(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    private val seasonRegex = Regex("""saison-(\d+)""")
    private val epNumRegex = Regex("[^0-9.]")
    private val pQualityRegex = Regex("""(\d+)p""")

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        )

    // ================== Utils ==================
    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$baseUrl$url"

    private fun setFetchTypeSafe(anime: SAnime, type: eu.kanade.tachiyomi.animesource.model.FetchType) {
        try {
            val methods = anime.javaClass.methods
            val setter = methods.find { it.name == "setFetch_type" }
            if (setter != null) {
                val appFetchTypeClass = setter.parameterTypes[0]
                val enumValue = appFetchTypeClass.enumConstants.find { it.toString() == type.name }
                setter.invoke(anime, enumValue)
            }
        } catch (_: Exception) {}
    }

    private fun setSeasonNumberSafe(anime: SAnime, season: Double) {
        try {
            val methods = anime.javaClass.methods
            val setter = methods.find { it.name == "setSeason_number" }
            if (setter != null) {
                setter.invoke(anime, season)
            }
        } catch (_: Exception) {}
    }

    private fun parseAnimePage(document: Document, fallbackPage: Int): AnimesPage {
        val items = document.select(".anime-grid a.card-base").map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".card-title")?.text()?.trim().orEmpty()
                thumbnail_url = element.selectFirst("img.card-image")?.attr("abs:src")
                url = fixUrl(element.attr("href")).replace(baseUrl, "")
            }
        }

        val currentPage = document.selectFirst(".pagination a.pagination-item.active")?.text()?.trim()?.toIntOrNull() ?: fallbackPage
        val pagesInLinks = document.select(".pagination a.pagination-item[href]")
            .mapNotNull { it.attr("abs:href") }
            .mapNotNull { href ->
                try {
                    href.toHttpUrl().queryParameter("page")?.toIntOrNull()
                } catch (_: Exception) {
                    null
                }
            }
        val hasNextPage = pagesInLinks.any { it > currentPage }

        return AnimesPage(items, hasNextPage)
    }

    // ================== Catalogue (catalogue/) ===============
    private fun catalogueRequest(
        page: Int,
        query: String = "",
        langue: String = "",
        type: String = "",
        genre: String = "",
        sort: String = "recent",
    ): Request {
        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("search", query)
            .addQueryParameter("langue", langue)
            .addQueryParameter("type", type)
            .addQueryParameter("genre", genre)
            .addQueryParameter("sort", sort)
            .build()

        return GET(url.toString(), headers)
    }

    override fun getFilterList() = AnimeSamaFanCatalogueFilters.FILTER_LIST

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(catalogueRequest(page = page, sort = "views")).execute()
        return parseAnimePage(response.asJsoup(), page)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(catalogueRequest(page = page, sort = "recent")).execute()
        return parseAnimePage(response.asJsoup(), page)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val response = client.newCall(GET("$baseUrl/anime/$id", headers)).execute()
            return parseSearchPage(response.asJsoup())
        }

        if (query.isNotBlank()) {
            val response = client.newCall(ajaxSearchRequest(query)).execute()
            return parseAjaxSearchPage(response.asJsoup())
        }

        val searchFilters = AnimeSamaFanCatalogueFilters.getSearchFilters(filters)
        val response = client.newCall(
            catalogueRequest(
                page = page,
                query = query,
                langue = searchFilters.langue,
                type = searchFilters.type,
                genre = searchFilters.genre,
                sort = searchFilters.sort,
            ),
        ).execute()

        return parseAnimePage(response.asJsoup(), page)
    }

    private fun ajaxSearchRequest(query: String): Request {
        val body = FormBody.Builder()
            .add("query", query)
            .build()

        val ajaxHeaders = headers.newBuilder()
            .add("Accept", "text/html, */*; q=0.01")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .add("Origin", baseUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        return POST("$baseUrl/template-php/defaut/fetch.php", ajaxHeaders, body)
    }

    private fun parseAjaxSearchPage(document: Document): AnimesPage {
        val items = document.select("a.asn-search-result").map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".asn-search-result-title")?.text()?.trim().orEmpty()
                thumbnail_url = element.selectFirst(".asn-search-result-img")?.attr("abs:src")
                url = fixUrl(element.attr("href")).replace(baseUrl, "")
            }
        }
        return AnimesPage(items, false)
    }

    private fun parseSearchPage(document: Document): AnimesPage {
        val titleElement = document.selectFirst("h1.anime-title") ?: document.selectFirst("h1")
        val isAnimePage = document.selectFirst(".anime-cover, .synopsis-content, .seasons-grid") != null

        if (isAnimePage && titleElement != null) {
            val anime = SAnime.create().apply {
                title = titleElement.text().replace("VOSTFR", "", true).replace("VF", "", true).trim()
                thumbnail_url = document.selectFirst(".anime-cover img")?.attr("abs:src")
                    ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                url = document.location().replace(baseUrl, "")
            }
            return AnimesPage(listOf(anime), false)
        }

        // Legacy AJAX search results (kept for compatibility)
        val items = document.select("a.asn-search-result").map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".asn-search-result-title")?.text() ?: ""
                thumbnail_url = element.selectFirst(".asn-search-result-img")?.attr("abs:src")
                url = fixUrl(element.attr("href")).replace(baseUrl, "")
            }
        }
        return AnimesPage(items, false)
    }

    private object AnimeSamaFanCatalogueFilters {
        private val filterData by lazy {
            val jsonStream = AnimeSamaFan::class.java.getResourceAsStream("filters.json")
            val jsonString = jsonStream?.bufferedReader()?.use { it.readText() } ?: "{}"
            try {
                Json.decodeFromString<Map<String, List<List<String>>>>(jsonString)
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun getOptions(key: String): List<Pair<String, String>> = filterData[key]?.map { it[0] to it[1] } ?: emptyList()

        class LangueFilter : AnimeFilter.Select<String>("Langue", getOptions("LANGUAGES").map { it.first }.toTypedArray(), 0)

        class TypeFilter : AnimeFilter.Select<String>("Type", getOptions("TYPES").map { it.first }.toTypedArray(), 0)

        class GenreFilter : AnimeFilter.Select<String>("Genre", getOptions("GENRES").map { it.first }.toTypedArray(), 0)

        class SortFilter : AnimeFilter.Select<String>("Trier par", getOptions("SORT").map { it.first }.toTypedArray(), 0)

        val FILTER_LIST get() = AnimeFilterList(
            LangueFilter(),
            TypeFilter(),
            GenreFilter(),
            SortFilter(),
        )

        data class SearchFilters(
            val langue: String,
            val type: String,
            val genre: String,
            val sort: String,
        )

        fun getSearchFilters(filters: AnimeFilterList): SearchFilters {
            if (filters.isEmpty()) {
                return SearchFilters(langue = "", type = "", genre = "", sort = "recent")
            }

            val langueIndex = filters.filterIsInstance<LangueFilter>().firstOrNull()?.state ?: 0
            val typeIndex = filters.filterIsInstance<TypeFilter>().firstOrNull()?.state ?: 0
            val genreIndex = filters.filterIsInstance<GenreFilter>().firstOrNull()?.state ?: 0
            val sortIndex = filters.filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0

            return SearchFilters(
                langue = getOptions("LANGUAGES").getOrNull(langueIndex)?.second ?: "",
                type = getOptions("TYPES").getOrNull(typeIndex)?.second ?: "",
                genre = getOptions("GENRES").getOrNull(genreIndex)?.second ?: "",
                sort = getOptions("SORT").getOrNull(sortIndex)?.second ?: "recent",
            )
        }
    }

    // ================== Details ==================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()

        val docTitle = document.selectFirst("h1.anime-title")?.text()?.trim()
            ?: document.selectFirst("h1.season-title")?.text()?.trim()

        var pageTitle = docTitle ?: anime.title

        // Extract ID from URL for cache/logic
        val animeId = anime.url.substringAfter("/anime/").substringBefore("/")

        // If title is just "Saison X", try to get the real name from the URL slug
        if (pageTitle.matches(Regex("(?i)^Saison\\s*\\d+$")) || pageTitle.length < 3) {
            val slug = animeId.replace(Regex("^\\d+-"), "").replace("-", " ").trim()
            if (slug.isNotBlank() && slug != "anime") pageTitle = slug
        }

        // Extract season number from URL and fix aberrant numbers (e.g. 32 -> 3)
        var sNum = seasonRegex.find(anime.url)?.groupValues?.get(1)?.toIntOrNull()
            ?: anime.url.substringBefore(".html").substringAfterLast("-").toIntOrNull()
            ?: 1

        if (sNum > 20) sNum = sNum.toString().substring(0, 1).toIntOrNull() ?: 1

        val cleanTitle = sanitizeTitle(pageTitle)

        // Set title and season number
        val isAlreadySeason = anime.url.contains("saison-", ignoreCase = true) || (sNum > 1 && document.selectFirst(".seasons-grid") == null)
        anime.title = if (isAlreadySeason && !pageTitle.contains("Saison", true)) "$pageTitle - Saison $sNum" else pageTitle
        setSeasonNumberSafe(anime, sNum.toDouble())

        // Baseline description from site
        val siteDescription = document.selectFirst(".synopsis-content p")?.text()
        if (!siteDescription.isNullOrBlank()) anime.description = siteDescription

        // Determine target TMDB Season with continuation fallback
        var tmdbMetadata = fetchTmdbMetadata(cleanTitle, sNum)
        if (sNum > 1 && (tmdbMetadata == null || tmdbMetadata.episodeSummaries.size < 2)) {
            tmdbMetadata = fetchTmdbMetadata(cleanTitle, sNum - 1)
        }

        // Use TMDB summary only if it's specific (different from S1 or if we are on S1)
        if (tmdbMetadata?.summary != null) {
            val s1Meta = if (sNum == 1) tmdbMetadata else fetchTmdbMetadata(cleanTitle, 1)
            if (sNum == 1 || tmdbMetadata.summary != s1Meta?.summary) {
                anime.description = tmdbMetadata.summary
            }
        }

        // Prepend release date
        tmdbMetadata?.releaseDate?.let { date ->
            if (anime.description?.contains(date) == false) {
                anime.description = "Date de sortie : $date\n\n${anime.description ?: ""}"
            }
        }

        tmdbMetadata?.posterUrl?.let { anime.thumbnail_url = it }
        tmdbMetadata?.author?.let { anime.author = it }
        tmdbMetadata?.artist?.let { anime.artist = it }
        tmdbMetadata?.status?.let { anime.status = it }

        // AniZen: If seasons grid is present with more than 1 season, set fetch type to Seasons
        // BUT only if we are not already on a specific season page to avoid infinite loops
        val seasonCards = document.select(".seasons-grid a.season-card")
        val hasMultipleSeasons = seasonCards.size > 1

        if (hasMultipleSeasons && !isAlreadySeason) {
            setFetchTypeSafe(anime, eu.kanade.tachiyomi.animesource.model.FetchType.Seasons)
        } else {
            setFetchTypeSafe(anime, eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
        }

        return anime
    }

    // ================== Seasons ==================
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()
        val baseTitle = sanitizeTitle(anime.title)

        val seasonCards = document.select(".seasons-grid a.season-card")
        val siteSeasons = seasonCards.map { element ->
            val sHref = element.attr("href")
            var sNum = seasonRegex.find(sHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            if (sNum > 20) sNum = sNum.toString().substring(0, 1).toIntOrNull() ?: 1
            val title = element.selectFirst(".season-title")?.text() ?: element.text().trim()
            Triple(title, element.attr("abs:href").replace(baseUrl, ""), sNum)
        }

        return siteSeasons.map { (sTitle, sUrl, siteSNum) ->
            SAnime.create().apply {
                title = sTitle
                url = sUrl

                // Poster fetching for grid
                val tmdbMeta = kotlinx.coroutines.runBlocking { fetchTmdbMetadata(baseTitle, siteSNum) }
                val isContinuation = siteSNum > 1 && (tmdbMeta == null || tmdbMeta.episodeSummaries.size < 2)
                val finalMeta = if (isContinuation) kotlinx.coroutines.runBlocking { fetchTmdbMetadata(baseTitle, siteSNum - 1) } else tmdbMeta

                thumbnail_url = finalMeta?.posterUrl ?: anime.thumbnail_url
                setFetchTypeSafe(this, eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
                setSeasonNumberSafe(this, siteSNum.toDouble())
                initialized = false
            }
        }
    }

    // ================== Episodes ==================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val initialPath = anime.url
        Log.d("AnimeSamaFan", "getEpisodeList for: $initialPath")

        // AniZen Hierarchical Mode
        if (initialPath.contains("saison-", ignoreCase = true)) {
            val doc = client.newCall(GET("$baseUrl$initialPath", headers)).execute().asJsoup()
            val pageTitle = doc.selectFirst("h1.season-title")?.text()
                ?: doc.selectFirst("h1.anime-title")?.text()
                ?: anime.title

            val baseTitle = sanitizeTitle(pageTitle)

            // 1. Find season tabs to calculate offset dynamically
            val tabs = doc.select(".tabs-container a.tab")
            val seasonLinks = tabs.map { it.attr("abs:href").replace(baseUrl, "") }.distinct()
            val currentIdx = seasonLinks.indexOfFirst { it == initialPath }

            var siteOffsetAccumulator = 0
            var oavOffsetAccumulator = 0
            var lastTmdbSNum = -1
            var finalTargetSNum = 1
            var finalOffset = 0
            var finalOavOffset = 0

            // Analyze current and previous seasons from tabs
            val seasonsToAnalyze = if (currentIdx >= 0) seasonLinks.take(currentIdx + 1) else listOf(initialPath)

            for (sUrl in seasonsToAnalyze) {
                var sNum = seasonRegex.find(sUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: sUrl.substringBefore(".html").substringAfterLast("-").toIntOrNull()
                    ?: 1
                if (sNum > 20) sNum = sNum.toString().substring(0, 1).toIntOrNull() ?: 1

                // Determine TMDB Season
                var tmdbS = sNum
                val meta = fetchTmdbMetadata(baseTitle, tmdbS)
                val tmdbCount = meta?.episodeSummaries?.size ?: 0

                if (sNum > 1 && (meta == null || tmdbCount < 2)) {
                    tmdbS = sNum - 1
                }

                if (tmdbS != lastTmdbSNum) {
                    siteOffsetAccumulator = 0
                    oavOffsetAccumulator = 0
                    lastTmdbSNum = tmdbS
                }

                if (sUrl == initialPath) {
                    finalTargetSNum = tmdbS
                    finalOffset = siteOffsetAccumulator
                    finalOavOffset = oavOffsetAccumulator
                    break
                }

                // Fetch previous season to count episodes and surplus (OAVs)
                val sDoc = tryGetDocument("$baseUrl$sUrl")
                val siteCount = sDoc?.select("a.episode-card, .episodes-grid a, .episodes-list a, .episode-item")?.size ?: 0

                val currentTmdbMeta = fetchTmdbMetadata(baseTitle, tmdbS)
                val currentTmdbCount = currentTmdbMeta?.episodeSummaries?.size ?: 0

                if (currentTmdbCount > 0) {
                    val remainingInTmdb = maxOf(0, currentTmdbCount - siteOffsetAccumulator)
                    siteOffsetAccumulator += minOf(siteCount, remainingInTmdb)
                    if (siteCount > remainingInTmdb) {
                        oavOffsetAccumulator += (siteCount - remainingInTmdb)
                    }
                } else {
                    siteOffsetAccumulator += siteCount
                }
            }
            Log.d("AnimeSamaFan", "Final Mapping: TMDB S=$finalTargetSNum, Offset=$finalOffset, OAV Offset=$finalOavOffset")

            return parseEpisodesFromDocument(doc, baseTitle, 1, finalOffset, finalOavOffset, finalTargetSNum)
                .sortedByDescending { it.episode_number }
        }

        // Aniyomi Classic Mode
        val normalizedPath = normalizeSingleSeasonUrlPath(initialPath)

        val initialDoc = client.newCall(GET("$baseUrl$initialPath", headers)).execute().asJsoup()

        val rootDoc = if (normalizedPath != initialPath) {
            val normalizedDoc = tryGetDocument("$baseUrl$normalizedPath")
            val normalizedLooksValid = normalizedDoc != null && (
                normalizedDoc.select("a.episode-card").isNotEmpty() ||
                    normalizedDoc.selectFirst(".seasons-grid") != null ||
                    normalizedDoc.select("iframe").isNotEmpty()
                )

            if (normalizedLooksValid) {
                normalizedDoc
            } else {
                initialDoc
            }
        } else {
            initialDoc
        }

        val seasonLinks = rootDoc.select(".seasons-grid a.season-card")
            .mapNotNull { it.attr("abs:href").takeIf { href -> href.isNotBlank() } }
            .distinct()

        val totalSeasons = seasonLinks.size.takeIf { it > 0 } ?: 1

        // Collect one document per season (to compute offsets safely)
        val seasonDocuments = mutableListOf<Document>()
        seasonDocuments.add(rootDoc)
        seasonLinks
            .filter { it != rootDoc.location() }
            .forEach { sUrl ->
                try {
                    seasonDocuments.add(client.newCall(GET(sUrl, headers)).execute().asJsoup())
                } catch (_: Exception) {
                }
            }

        data class SeasonContext(
            val seasonNum: Int,
            val document: Document,
            val episodeCount: Int,
        )

        fun seasonNumFromUrl(url: String): Int = seasonRegex.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val contexts = seasonDocuments
            .map { doc ->
                val sNum = seasonNumFromUrl(doc.location())
                SeasonContext(sNum, doc, doc.select("a.episode-card").size)
            }
            .groupBy { it.seasonNum }
            .map { (_, list) -> list.maxBy { it.episodeCount } }
            .sortedBy { it.seasonNum }

        // Compute cumulative episode offsets per season.
        // This is used when TMDB merges multiple site seasons into a single TMDB season (usually season 1).
        val episodeOffsetBySeason = mutableMapOf<Int, Int>()
        val oavOffsetBySeason = mutableMapOf<Int, Int>()
        var siteOffsetAccumulator = 0
        var oavOffsetAccumulator = 0

        val baseTitle = anime.title.replace(Regex("""(?i)\s*(?:Saison|Season)\s*\d+.*"""), "").trim()
        val seenTmdbSeasons = mutableSetOf<Int>()

        contexts.forEach { ctx ->
            episodeOffsetBySeason[ctx.seasonNum] = siteOffsetAccumulator
            oavOffsetBySeason[ctx.seasonNum] = oavOffsetAccumulator

            val count = ctx.episodeCount
            val sNum = ctx.seasonNum

            if (seenTmdbSeasons.contains(sNum)) {
                oavOffsetAccumulator += count
            } else {
                seenTmdbSeasons.add(sNum)
                val tmdbMeta = kotlinx.coroutines.runBlocking { fetchTmdbMetadata(baseTitle, sNum, "tv") }
                val tmdbCount = tmdbMeta?.episodeSummaries?.size ?: 0
                if (tmdbCount > 0) {
                    siteOffsetAccumulator += kotlin.math.min(count, tmdbCount)
                    if (count > tmdbCount) {
                        oavOffsetAccumulator += (count - tmdbCount)
                    }
                } else {
                    siteOffsetAccumulator += count
                }
            }
        }

        val episodes = mutableListOf<SEpisode>()
        contexts.forEach { ctx ->
            val sOffset = episodeOffsetBySeason[ctx.seasonNum] ?: 0
            val oOffset = oavOffsetBySeason[ctx.seasonNum] ?: 0
            episodes.addAll(parseEpisodesFromDocument(ctx.document, baseTitle, totalSeasons, sOffset, oOffset))
        }

        return episodes.distinctBy { it.url }.sortedByDescending { it.episode_number }
    }

    private fun normalizeSingleSeasonUrlPath(path: String): String {
        // Some animes with a single season are linked as /anime/<id-slug>/saison-1.html.
        // The canonical page (and the player for movies) is often /anime/<id-slug>.html.
        return path.replace(Regex("/saison-1\\.html$"), ".html")
            .replace(Regex("/saison-1$"), "")
    }

    private fun extractEmbeddedPlayerUrl(document: Document): String? {
        val ogVideo = document.selectFirst("meta[property=og:video]")?.attr("content")?.trim().orEmpty()
        if (ogVideo.isNotBlank()) return ogVideo

        val embedUrl = document.selectFirst("meta[itemprop=embedUrl]")?.attr("content")?.trim().orEmpty()
        if (embedUrl.isNotBlank()) return embedUrl

        return null
    }

    private fun tryGetDocument(url: String): Document? {
        return try {
            val response = client.newCall(GET(url, headers)).execute()
            if (!response.isSuccessful) return null
            response.asJsoup()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun parseEpisodesFromDocument(
        document: Document,
        animeTitle: String,
        totalSeasons: Int,
        siteOffset: Int,
        oavOffset: Int,
        tmdbSNumOverride: Int? = null,
    ): List<SEpisode> {
        val url = document.location()
        val seasonMatch = seasonRegex.find(url)
        val sNum = tmdbSNumOverride ?: seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        // Fetch primary metadata with fallback for empty seasons
        var tmdbMetadata = fetchTmdbMetadata(animeTitle, sNum)
        if (sNum > 1 && (tmdbMetadata == null || (tmdbMetadata.episodeSummaries.size < 2))) {
            val fallback = fetchTmdbMetadata(animeTitle, sNum - 1)
            if (fallback != null && fallback.episodeSummaries.size > 5) {
                tmdbMetadata = fallback
            }
        }

        var tmdbSeason1Metadata: fr.bluecxt.core.TmdbMetadata? = null
        val tmdbSeasonEpisodeCount = tmdbMetadata?.episodeSummaries?.size ?: 0

        // Lazy load S0 metadata
        var tmdbS0Metadata: fr.bluecxt.core.TmdbMetadata? = null

        val episodeCards = document.select("a.episode-card, .episodes-grid a, .episodes-list a, .episode-item")
        Log.d("AnimeSamaFan", "Parsing episodes from $url, found ${episodeCards.size} cards")

        if (episodeCards.isEmpty()) {
            // Movie pages can have the player directly on the anime page with no episode list.
            val embeddedPlayerUrl = extractEmbeddedPlayerUrl(document)
            val hasKnownPlayerIframe = document.select("iframe")
                .flatMap { listOf(it.attr("abs:src"), it.attr("abs:data-src")) }
                .any { src -> src.isNotBlank() && getServerName(src) != null }

            if (hasKnownPlayerIframe || embeddedPlayerUrl != null) {
                return listOf(
                    SEpisode.create().apply {
                        this.url = url
                        name = "Épisode 1"
                        episode_number = 1f
                        scanlator = "VOSTFR, VF"
                    },
                )
            }
        }

        return episodeCards.map { card ->
            val epUrl = card.attr("abs:href")
            val availableLangs = mutableListOf<String>()
            val langs = card.attr("data-langs").uppercase()
            if (langs.contains("VOSTFR")) availableLangs.add("VOSTFR")
            if (langs.contains("VF")) availableLangs.add("VF")

            SEpisode.create().apply {
                this.url = epUrl
                val epTitleRaw = card.selectFirst("h3.episode-title")?.text()
                    ?: card.selectFirst(".episode-title")?.text()
                    ?: card.text().trim()

                val epTitle = epTitleRaw.replace("Épisode", "Episode", true).ifBlank { "Episode" }

                // Extract episode number from text or URL
                val epNumStr = (
                    card.selectFirst(".episode-number")?.text()
                        ?: epNumRegex.replace(epTitle, "")
                        ?: epNumRegex.replace(epUrl.substringAfterLast("/"), "")
                        ?: "0"
                    ).replace(epNumRegex, "")

                val epNum = epNumStr.toIntOrNull() ?: 0

                // Metadata from TMDB - Apply offset directly to primary metadata
                val absEpNum = epNum + siteOffset
                var epMeta = tmdbMetadata?.episodeSummaries?.get(absEpNum)

                // If not found and we are beyond the season count, try Season 0 (Specials)
                if (epMeta == null && tmdbSeasonEpisodeCount > 0 && absEpNum > tmdbSeasonEpisodeCount) {
                    if (tmdbS0Metadata == null) tmdbS0Metadata = fetchTmdbMetadata(animeTitle, 0)?.let { filterSmartMetadata(it, isSpecialSeason = true) }
                    val s0EpNum = (absEpNum - tmdbSeasonEpisodeCount) + oavOffset
                    epMeta = tmdbS0Metadata?.episodeSummaries?.get(s0EpNum)
                }

                val tmdbName = epMeta?.first
                val formattedName = if (epTitle.contains(Regex("(?i)Episode\\s*\\d+")) || epTitle.isBlank() || epTitle.length < 3) {
                    if (tmdbName != null) "Episode $epNumStr - $tmdbName" else "Episode $epNumStr"
                } else {
                    if (epTitle.contains("Episode", true)) epTitle else "Episode $epNumStr - $epTitle"
                }

                val sPrefix = if (totalSeasons > 1) "[S$sNum] " else ""
                name = "$sPrefix$formattedName"
                this.episode_number = epNum.toFloat()
                this.scanlator = availableLangs.joinToString(", ").ifBlank { "VOSTFR" }
                this.preview_url = epMeta?.second
                this.summary = epMeta?.third
            }
        }
    }

    // ================== Video (Extracteurs) ==================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val hosters = mutableListOf<Hoster>()
        val langsString = episode.scanlator ?: "VOSTFR"
        val langs = langsString.split(", ")

        langs.forEach { lang ->
            try {
                val urlWithLang = if (episode.url.contains("?")) {
                    "${episode.url}&lang=${lang.lowercase()}"
                } else {
                    "${episode.url}?lang=${lang.lowercase()}"
                }
                val doc = client.newCall(GET(urlWithLang, headers)).execute().asJsoup()

                val iframes = doc.select("iframe")
                if (iframes.isNotEmpty()) {
                    iframes.forEach { iframe ->
                        val serverUrl = listOf(
                            iframe.attr("abs:src"),
                            iframe.attr("abs:data-src"),
                            iframe.attr("abs:data-lazy-src"),
                        ).firstOrNull { it.isNotBlank() }.orEmpty()

                        val serverName = getServerName(serverUrl)
                        if (serverName != null) {
                            hosters.add(Hoster(hosterName = "($lang) $serverName", internalData = "$serverUrl|$lang"))
                        }
                    }
                } else {
                    // Movie pages often expose only an embed URL in meta tags (og:video / itemprop=embedUrl)
                    val playerUrl = extractEmbeddedPlayerUrl(doc)
                    val serverName = playerUrl?.let { getServerName(it) }
                    if (playerUrl != null && serverName != null) {
                        hosters.add(Hoster(hosterName = "($lang) $serverName", internalData = "$playerUrl|$lang"))
                    }
                }
            } catch (_: Exception) {}
        }
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|")
        val serverUrl = data[0]
        val lang = data[1]
        val prefix = "($lang) ${hoster.hosterName.substringAfter(") ")} - "

        val videoList = mutableListOf<Video>()
        when {
            serverUrl.contains("sibnet.ru") -> sibnetExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            serverUrl.contains("sendvid.com") -> sendvidExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            serverUrl.contains("streamtape") || serverUrl.contains("shavetape") -> streamTapeExtractor.videoFromUrl(serverUrl, prefix)?.let { videoList.add(it) }
            serverUrl.contains("dood") -> doodExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            serverUrl.contains("vidoza.net") -> vidoExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            serverUrl.contains("vidmoly") -> vidmolyExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            serverUrl.contains("voe.sx") -> voeExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
        }

        return videoList.map {
            Video(videoUrl = it.videoUrl, videoTitle = cleanQuality(it.videoTitle), headers = it.headers, subtitleTracks = it.subtitleTracks, audioTracks = it.audioTracks)
        }.sortVideos()
    }

    private fun getServerName(url: String): String? = when {
        url.contains("sibnet.ru") -> "Sibnet"
        url.contains("sendvid.com") -> "Sendvid"
        url.contains("streamtape") || url.contains("shavetape") -> "Streamtape"
        url.contains("dood") -> "Doodstream"
        url.contains("vidoza.net") -> "Vidoza"
        url.contains("vidmoly", true) -> "Vidmoly"
        url.contains("voe.sx") -> "Voe"
        else -> null
    }

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s"), "")
            .replace(Regex("\\s*\\(\\d+x\\d+\\)"), "")
            .replace(Regex("(?i)(Sendvid|Sibnet|Voe|Vidoza):default"), "")
            .replace(" - - ", " - ")
            .trim()
            .removeSuffix("-")
            .trim()

        val servers = listOf("Sibnet", "Sendvid", "Streamtape", "Doodstream", "Vidoza", "Vidmoly", "Voe")
        for (server in servers) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server(?!:)", RegexOption.IGNORE_CASE), server)
            cleaned = cleaned.replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(Regex("\\s+"), " ").replace(" - - ", " - ").trim()
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val prefVoice = preferences.getString("preferred_voices", "VOSTFR")!!
        val player = preferences.getString("preferred_server", "sibnet")!!

        return this.sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains("($prefVoice)", true) }
                .thenByDescending { it.hosterName.contains(player, true) },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefVoice = preferences.getString("preferred_voices", "VOSTFR")!!
        val player = preferences.getString("preferred_server", "sibnet")!!

        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains("($prefVoice)", true) }
                .thenByDescending { it.videoTitle.contains(player, true) }
                .thenByDescending {
                    pQualityRegex.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val voicesPref = ListPreference(screen.context).apply {
            key = "preferred_voices"
            title = "Préférence des voix"
            entries = arrayOf("Préférer VOSTFR", "Préférer VF")
            entryValues = arrayOf("VOSTFR", "VF")
            setDefaultValue("VOSTFR")
            summary = "%s"
        }
        val serverPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Serveur préféré"
            entries = arrayOf("Sibnet", "Sendvid", "Voe", "Streamtape", "Doodstream", "Vidoza")
            entryValues = arrayOf("sibnet", "sendvid", "voe", "streamtape", "dood", "vidoza")
            setDefaultValue("sibnet")
            summary = "%s"
        }
        screen.addPreference(voicesPref)
        screen.addPreference(serverPref)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
