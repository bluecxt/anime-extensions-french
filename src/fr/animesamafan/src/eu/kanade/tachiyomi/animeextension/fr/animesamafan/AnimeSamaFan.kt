package eu.kanade.tachiyomi.animeextension.fr.animesamafan

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelMap
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.CommonPreferences.Companion.PREF_URL_KEY
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.Source
import fr.bluecxt.core.TmdbMetadata
import fr.bluecxt.core.fetchTmdbMetadata
import fr.bluecxt.core.filterSmartMetadata
import fr.bluecxt.core.safeRelativePath
import fr.bluecxt.core.sanitizeTitle
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy

class AnimeSamaFan :
    Source(),
    CommonPreferences {
    override val name = "Anime-Sama-Fan"

    override val defaultBaseUrl = "https://animesama.co"
    override val supportedServers = listOf("Sibnet", "Sendvid")

    override val baseUrl by lazy {
        val url = currentBaseUrl.removeSuffix("/")
        val finalUrl = if (url.startsWith("http")) url else "https://$url"
        Log.d("AnimeSamaFan", "Base URL: $finalUrl")
        finalUrl
    }

    override val lang = "fr"

    override val supportsLatest = true

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super<CommonPreferences>.setupPreferenceScreen(screen)
    }

    private val seasonRegex = Regex("""saison-(\d+)""")
    private val epNumRegex = Regex("[^0-9.]")

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", DEFAULT_USER_AGENT)

    // ================== Utils ==================

    private fun parseAnimePage(document: Document, fallbackPage: Int): AnimesPage {
        val items = document.select("div.catalog-card").map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".card-title")?.text()?.trim().orEmpty()
                thumbnail_url = element.selectFirst("img.card-image")?.attr("abs:src")
                url = element.selectFirst("a")?.safeRelativePath() ?: ""
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
        type: String = "",
        genre: String = "",
    ): Request {
        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            query.takeIf { it.isNotBlank() }?.let { addQueryParameter("search", it) }
            type.takeIf { it.isNotBlank() }?.let { addQueryParameter("type", it) }
            genre.takeIf { it.isNotBlank() }?.let { addQueryParameter("genre", it) }
        }.build()

        return GET(url.toString(), headers)
    }

    override fun getFilterList() = AnimeSamaFanCatalogueFilters.FILTER_LIST

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(catalogueRequest(page = page)).execute()
        return parseAnimePage(response.asJsoup(), page)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val response = client.newCall(GET(baseUrl, headers)).execute()
        val doc = response.asJsoup()
        return AnimesPage(parseLatestFromHome(doc), false)
    }

    private fun parseLatestFromHome(document: Document): List<SAnime> {
        val container = document.selectFirst("main.container") ?: document
        val sections = container.select("div.section:has(h1:contains(DERNIERS ANIMES SORTIS)), div.section:has(h1:contains(DERNIERS ÉPISODES AJOUTÉS))")

        return sections.flatMap { section ->
            section.select("div.catalog-card a[href], div.card-base a[href]").map { a ->
                val href = a.attr("href")
                val hubUrl = getHubUrl(href)
                val title = a.selectFirst(".card-title")?.text()?.trim().orEmpty()
                val thumb = a.selectFirst("img.card-image")?.attr("abs:src")

                SAnime.create().apply {
                    this.title = title
                    this.url = hubUrl
                    this.thumbnail_url = thumb
                }
            }
        }.distinctBy { it.url }
    }

    private fun getHubUrl(url: String): String {
        val segments = url.trim('/').split("/")
        return if (segments.size >= 2) {
            "/${segments[0]}/${segments[1].substringBefore(".html")}.html"
        } else {
            url
        }
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val query = query.trim()

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val response = client.newCall(GET("$baseUrl/anime/$id", headers)).execute()
            return parseSearchPage(response.asJsoup())
        }

        val searchFilters = AnimeSamaFanCatalogueFilters.getSearchFilters(filters)
        val response = client.newCall(
            catalogueRequest(
                page = page,
                query = query,
                type = searchFilters.type,
                genre = searchFilters.genre,
            ),
        ).execute()

        return parseAnimePage(response.asJsoup(), page)
    }

    private fun parseSearchPage(document: Document): AnimesPage {
        val titleElement = document.selectFirst("h1.anime-title") ?: document.selectFirst("h1")
        val isAnimePage = document.selectFirst(".anime-cover, .synopsis-content, .seasons-grid") != null

        val anime = SAnime.create().apply {
            title = titleElement?.text()?.replace("VOSTFR", "", true)?.replace("VF", "", true)?.trim() ?: "Unknown Title"
            thumbnail_url = document.selectFirst(".anime-cover img")?.attr("abs:src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            url = document.location().safeRelativePath(baseUrl)
        }
        return AnimesPage(listOf(anime), false)
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

        class TypeFilter : AnimeFilter.Select<String>("Type", getOptions("TYPES").map { it.first }.toTypedArray(), 0)

        class GenreFilter : AnimeFilter.Select<String>("Genre", getOptions("GENRES").map { it.first }.toTypedArray(), 0)

        val FILTER_LIST get() = AnimeFilterList(
            TypeFilter(),
            GenreFilter(),
        )

        data class SearchFilters(
            val type: String,
            val genre: String,
        )

        fun getSearchFilters(filters: AnimeFilterList): SearchFilters {
            if (filters.isEmpty()) {
                return SearchFilters(type = "", genre = "")
            }

            val typeIndex = filters.filterIsInstance<TypeFilter>().firstOrNull()?.state ?: 0
            val genreIndex = filters.filterIsInstance<GenreFilter>().firstOrNull()?.state ?: 0

            return SearchFilters(
                type = getOptions("TYPES").getOrNull(typeIndex)?.second ?: "",
                genre = getOptions("GENRES").getOrNull(genreIndex)?.second ?: "",
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

        // Use Core Engine for title optimization
        anime.coreOptimizeDisplayTitle(pageTitle, pageTitle.replace(Regex("(?i)\\s*-\\s*Saison.*|\\s*Saison.*"), "").trim())
        // AniZen bypass: Force -2.0 to totally disable auto-labeling and use our provided title
        anime.coreSetSeasonNumber(-2.0)

        // Baseline description from site
        val siteDescription = document.selectFirst(".synopsis-content p")?.text()
        if (!siteDescription.isNullOrBlank()) {
            anime.description = if (anime.description.isNullOrBlank()) siteDescription else "${anime.description}\n\n$siteDescription"
        }

        // Genres from site
        val siteGenres = document.select(".synopsis-content .anime-genres .genre-link")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Animes", true) }
        if (siteGenres.isNotEmpty()) {
            anime.genre = siteGenres.joinToString(", ")
        }

        // Determine target TMDB Season with continuation fallback
        var tmdbMetadata = fetchTmdbMetadata(cleanTitle, sNum)
        if (sNum > 1 && (tmdbMetadata == null || tmdbMetadata.episodeSummaries.size < 2)) {
            tmdbMetadata = fetchTmdbMetadata(cleanTitle, sNum - 1)
        }

        // TMDB Fallbacks
        if (anime.genre.isNullOrBlank()) tmdbMetadata?.genre?.let { anime.genre = it }

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
            anime.coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Seasons)
        } else {
            anime.coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
        }

        return anime
    }

    // ================== Seasons ==================
    private fun getSeasonNumber(url: String): Int {
        var sNum = seasonRegex.find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: url.substringBefore(".html").substringAfterLast("-").toIntOrNull()
            ?: 1
        if (sNum > 20) sNum = sNum.toString().substring(0, 1).toIntOrNull() ?: 1
        return sNum
    }

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()
        val baseTitle = sanitizeTitle(anime.title)

        val seasonCards = document.select(".seasons-grid a.season-card")
        val siteSeasons = seasonCards.map { element ->
            val sHref = element.attr("href")
            val siteSNum = getSeasonNumber(sHref)
            val sTitle = element.selectFirst(".season-title")?.text() ?: element.text().trim()

            // Format full title for the engine: "[Name] - Saison [X]"
            val fullSeasonTitle = if (!sTitle.contains(baseTitle, true)) "$baseTitle - $sTitle" else sTitle
            Triple(fullSeasonTitle, element.safeRelativePath(), siteSNum)
        }

        return coreBuildSeasonList(baseTitle, siteSeasons, anime.status).onEach { it.coreSetSeasonNumber(-2.0) }
    }

    // ================== Episodes ==================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val initialPath = anime.url
        Log.d("AnimeSamaFan", "getEpisodeList: initialPath='$initialPath'")

        val initialDoc = client.newCall(GET("$baseUrl$initialPath", headers)).execute().asJsoup()

        // If it's a hub page with no episodes but has a seasons grid, follow the first one
        // (Avoids aggregation but ensures we find episodes for single-season animes)
        val episodeCards = initialDoc.select("a.episode-card, .episodes-grid a, .episodes-list a, .episode-item")
        val gridCards = initialDoc.select(".seasons-grid a.season-card")

        val (doc, path) = if (episodeCards.isEmpty() && gridCards.isNotEmpty()) {
            val firstUrl = gridCards.first()!!.safeRelativePath()
            client.newCall(GET("$baseUrl$firstUrl", headers)).execute().asJsoup() to firstUrl
        } else {
            initialDoc to initialPath
        }

        val pageTitle = doc.selectFirst("h1.season-title")?.text()
            ?: doc.selectFirst("h1.anime-title")?.text()
            ?: anime.title

        val baseTitle = sanitizeTitle(pageTitle)
        val siteSNum = getSeasonNumber(path)

        // 1. Find season tabs to calculate offset dynamically (Hierarchical Mode)
        val tabs = doc.select(".tabs-container a.tab")
        val (finalOffset, finalOavOffset, finalTargetSNum) = if (tabs.isEmpty()) {
            Triple(0, 0, siteSNum)
        } else {
            val seasonLinks = tabs.map { it.safeRelativePath() }.distinct()
            val currentIdx = seasonLinks.indexOfFirst { it == path }
            val seasonsToAnalyze = if (currentIdx >= 0) seasonLinks.take(currentIdx + 1) else listOf(path)

            val countsMap = seasonsToAnalyze.associate { sUrl ->
                val sNum = getSeasonNumber(sUrl)
                val sDoc = if (sUrl == path) doc else tryGetDocument("$baseUrl$sUrl")
                val siteCount = sDoc?.select("a.episode-card, .episodes-grid a, .episodes-list a, .episode-item")?.size ?: 0
                sNum to siteCount
            }

            val offsets = coreCalculateEpisodeOffset(baseTitle, siteSNum, countsMap)
            Triple(offsets.first, offsets.second, siteSNum)
        }

        return parseEpisodesFromDocument(doc, baseTitle, finalOffset, finalOavOffset, finalTargetSNum)
            .sortedByDescending { it.episode_number }
    }

    private fun extractEmbeddedPlayerUrl(document: Document): String? {
        val ogVideo = document.selectFirst("meta[property=og:video]")?.attr("content")?.trim().orEmpty()
        if (ogVideo.isNotBlank()) return ogVideo

        val embedUrl = document.selectFirst("meta[itemprop=embedUrl]")?.attr("content")?.trim().orEmpty()
        if (embedUrl.isNotBlank()) return embedUrl

        return null
    }

    private fun tryGetDocument(url: String): Document? {
        Log.d("AnimeSamaFan", "tryGetDocument: url='$url'")
        return try {
            val response = client.newCall(GET(url, headers)).execute()
            if (!response.isSuccessful) {
                Log.d("AnimeSamaFan", "tryGetDocument failed: code=${response.code}")
                return null
            }
            response.asJsoup()
        } catch (e: Exception) {
            Log.d("AnimeSamaFan", "tryGetDocument exception: ${e.message}")
            null
        }
    }

    private suspend fun parseEpisodesFromDocument(
        document: Document,
        animeTitle: String,
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

        // Lazy load S0 metadata
        val tmdbS0Metadata = if (sNum > 0) fetchTmdbMetadata(animeTitle, 0)?.let { filterSmartMetadata(it, isSpecialSeason = true) } else null

        val episodeCards = document.select("a.episode-card, .episodes-grid a, .episodes-list a, .episode-item")
        Log.d("AnimeSamaFan", "parseEpisodesFromDocument cards found: ${episodeCards.size}")

        if (episodeCards.isEmpty()) {
            // Movie pages can have the player directly on the anime page with no episode list.
            val embeddedPlayerUrl = extractEmbeddedPlayerUrl(document)
            val hasKnownPlayerIframe = document.select("iframe")
                .flatMap { listOf(it.attr("abs:src"), it.attr("abs:data-src")) }
                .any { src -> src.isNotBlank() && getServerName(src, supportedServers) != null }

            if (hasKnownPlayerIframe || embeddedPlayerUrl != null) {
                return listOf(
                    SEpisode.create().apply {
                        this.url = url.safeRelativePath(baseUrl)
                        name = "[Movie] Film"
                        episode_number = 1f
                        scanlator = "VOSTFR, VF"
                    },
                )
            }
        }

        val rawEpisodes = episodeCards.map { card ->
            val epUrl = card.safeRelativePath()
            val availableLangs = mutableListOf<String>()
            val langs = card.attr("data-langs").uppercase()
            if (langs.contains("VOSTFR")) availableLangs.add("VOSTFR")
            if (langs.contains("VF")) availableLangs.add("VF")

            SEpisode.create().apply {
                this.url = epUrl
                val epTitleRaw = card.selectFirst("h3.episode-title")?.text()
                    ?: card.selectFirst(".episode-title")?.text()
                    ?: card.text().trim()

                // Clean title only (Episode 1, etc.)
                name = epTitleRaw.replace("Épisode", "Episode", true).ifBlank { "Episode" }

                // Extract episode number
                val epNumStr = (card.selectFirst(".episode-number")?.text() ?: epNumRegex.replace(name, "")).replace(epNumRegex, "")
                episode_number = epNumStr.toFloatOrNull() ?: 0f
                scanlator = availableLangs.joinToString(", ").ifBlank { "VOSTFR" }
            }
        }

        val isMovie = url.contains("film", true) || url.contains("movie", true) || animeTitle.contains("FILM", true) || animeTitle.contains("MOVIE", true)
        val isOav = url.contains("oav", true) || url.contains("special", true) || animeTitle.contains("OAV", true) || animeTitle.contains("Special", true)

        return coreMapEpisodes(rawEpisodes, tmdbMetadata, tmdbS0Metadata, siteOffset to oavOffset, sNum, isMovie, isOav)
    }

    // ================== Video (Extracteurs) ==================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val langsString = episode.scanlator ?: "VOSTFR"
        return langsString.split(", ").map { lang ->
            Hoster(hosterName = lang, internalData = "${episode.url}|$lang")
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|")
        val episodeUrl = data[0]
        val lang = data[1]
        val langLabel = lang.uppercase()
        val urlWithLang = if (episodeUrl.contains("?")) {
            "$episodeUrl&lang=${lang.lowercase()}"
        } else {
            "$episodeUrl?lang=${lang.lowercase()}"
        }

        val doc = client.newCall(GET("$baseUrl$urlWithLang", headers)).execute().asJsoup()
        val playerUrls = mutableListOf<String>()

        val iframes = doc.select("iframe")
        if (iframes.isNotEmpty()) {
            iframes.forEach { iframe ->
                val serverUrl = listOf(
                    iframe.attr("abs:src"),
                    iframe.attr("abs:data-src"),
                    iframe.attr("abs:data-lazy-src"),
                ).firstOrNull { it.isNotBlank() }.orEmpty()
                if (serverUrl.isNotBlank()) playerUrls.add(serverUrl)
            }
        } else {
            extractEmbeddedPlayerUrl(doc)?.let { playerUrls.add(it) }
        }

        return playerUrls.parallelMap { playerUrl ->
            extractVideos(playerUrl, langLabel, supportedServers)
        }.flatten().coreSortVideos()
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val prefVoice = preferences.getString(CommonPreferences.PREF_VOICES_KEY, "VOSTFR")!!
        val player = preferences.getString(CommonPreferences.PREF_SERVER_KEY, "Sibnet")!!

        return this.sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains("($prefVoice)", true) }
                .thenByDescending { it.hosterName.contains(player, true) },
        )
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
