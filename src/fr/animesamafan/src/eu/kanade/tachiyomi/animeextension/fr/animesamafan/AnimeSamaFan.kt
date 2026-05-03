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
    private val ignoredSpecialRegex = Regex("""(?i)(émission spéciale avant diffusion|commentaires vidéo|video comments|behind the scenes|recap|résumé|trailer|bande[- ]annonce|pv)""")

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        )

    // ================== Utils ==================
    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$baseUrl$url"

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
        private val LANGUAGE_OPTIONS = arrayOf(
            "Toutes les langues" to "",
            "VF" to "VF",
            "VOSTFR" to "VOSTFR",
        )

        private val TYPE_OPTIONS = arrayOf(
            "Tous les types" to "",
            "Série" to "Series",
            "Film" to "Film",
        )

        private val GENRE_OPTIONS = arrayOf(
            "Tous les genres" to "",
            "Action" to "9",
            "Animes" to "1",
            "Aventure" to "13",
            "Comédie" to "11",
            "Crime" to "15",
            "Drame" to "12",
            "Famille" to "16",
            "Fantastique" to "14",
            "Films" to "2",
            "Guerre" to "17",
            "Horreur" to "22",
            "Mystère" to "18",
            "Romance" to "20",
            "Scans" to "50",
            "Science-Fiction" to "19",
            "Thriller" to "21",
            "Top Animes" to "3",
            "Voirdrama" to "48",
        )

        private val SORT_OPTIONS = arrayOf(
            "Plus récents" to "recent",
            "A → Z" to "az",
            "Les plus vus" to "views",
        )

        class LangueFilter : AnimeFilter.Select<String>("Langue", LANGUAGE_OPTIONS.map { it.first }.toTypedArray(), 0)

        class TypeFilter : AnimeFilter.Select<String>("Type", TYPE_OPTIONS.map { it.first }.toTypedArray(), 0)

        class GenreFilter : AnimeFilter.Select<String>("Genre", GENRE_OPTIONS.map { it.first }.toTypedArray(), 0)

        class SortFilter : AnimeFilter.Select<String>("Trier par", SORT_OPTIONS.map { it.first }.toTypedArray(), 0)

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
                langue = LANGUAGE_OPTIONS.getOrNull(langueIndex)?.second ?: "",
                type = TYPE_OPTIONS.getOrNull(typeIndex)?.second ?: "",
                genre = GENRE_OPTIONS.getOrNull(genreIndex)?.second ?: "",
                sort = SORT_OPTIONS.getOrNull(sortIndex)?.second ?: "recent",
            )
        }
    }

    // ================== Details ==================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()

        anime.title = document.selectFirst("h1.anime-title")?.text() ?: anime.title
        anime.description = document.selectFirst(".synopsis-content p")?.text()
        anime.genre = document.select(".genre-link").joinToString { it.text() }

        // Fetch HD Metadata and Status from TMDB
        val tmdbMetadata = fetchTmdbMetadata(anime.title)
        tmdbMetadata?.summary?.let { anime.description = it }

        // Prepend release date to description
        tmdbMetadata?.releaseDate?.let { date ->
            anime.description = "Date de sortie : $date\n\n${anime.description ?: ""}"
        }

        tmdbMetadata?.posterUrl?.let { anime.thumbnail_url = it }
        tmdbMetadata?.author?.let { anime.author = it }
        tmdbMetadata?.artist?.let { anime.artist = it }
        tmdbMetadata?.status?.let { anime.status = it }

        return anime
    }

    // ================== Episodes ==================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val initialPath = anime.url
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
                normalizedDoc!!
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
        var cumulative = 0
        contexts.forEach { ctx ->
            episodeOffsetBySeason[ctx.seasonNum] = cumulative
            cumulative += ctx.episodeCount
        }

        val episodes = mutableListOf<SEpisode>()
        contexts.forEach { ctx ->
            val offset = episodeOffsetBySeason[ctx.seasonNum] ?: 0
            episodes.addAll(parseEpisodesFromDocument(ctx.document, anime.title, totalSeasons, offset))
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
        absoluteEpisodeOffset: Int,
    ): List<SEpisode> {
        val url = document.location()
        val seasonMatch = seasonRegex.find(url)
        val sNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        Log.d("AnimeSamaFan", "Parsing episodes for season $sNum (Total seasons: $totalSeasons)")

        // Fetch specific season metadata from TMDB
        val tmdbMetadata = fetchTmdbMetadata(animeTitle, sNum)
        var tmdbSeason1Metadata: fr.bluecxt.core.TmdbMetadata? = null
        val tmdbSeasonEpisodeCount = tmdbMetadata?.episodeSummaries?.size ?: 0
        val tmdbSpecialEpisodes = fetchTmdbMetadata(animeTitle, 0)
            ?.episodeSummaries
            ?.toSortedMap()
            ?.values
            ?.filterNot { isIgnoredSpecial(it.first) }
            .orEmpty()

        val episodeCards = document.select("a.episode-card")

        // Movie pages can have the player directly on the anime page with no episode list.
        val embeddedPlayerUrl = extractEmbeddedPlayerUrl(document)

        val hasKnownPlayerIframe = document.select("iframe")
            .flatMap { iframe ->
                listOf(
                    iframe.attr("abs:src"),
                    iframe.attr("abs:data-src"),
                    iframe.attr("abs:data-lazy-src"),
                )
            }
            .map { it.trim() }
            .any { src -> src.isNotBlank() && getServerName(src) != null }

        val hasSeasonsGrid = document.selectFirst(".seasons-grid") != null
        val ogType = document.selectFirst("meta[property=og:type]")?.attr("content")?.trim().orEmpty()
        val isMovieOgType = ogType.equals("video.movie", ignoreCase = true)

        if (episodeCards.isEmpty() && !hasSeasonsGrid && (hasKnownPlayerIframe || embeddedPlayerUrl != null || isMovieOgType)) {
            return listOf(
                SEpisode.create().apply {
                    this.url = url
                    name = "[Movie] Episode 1"
                    episode_number = 1f
                    scanlator = "VOSTFR, VF"
                },
            )
        }

        return episodeCards.map { card ->
            val availableLangs = mutableListOf<String>()
            val langs = card.attr("data-langs").uppercase()
            if (langs.contains("VOSTFR")) availableLangs.add("VOSTFR")
            if (langs.contains("VF")) availableLangs.add("VF")

            SEpisode.create().apply {
                this.url = card.attr("abs:href")
                val epTitleRaw = card.selectFirst("h3.episode-title")?.text()?.trim().orEmpty()
                val epTitle = epTitleRaw.replace("Épisode", "Episode", true).ifBlank { "Episode" }
                val epNumStr = (card.selectFirst(".episode-number")?.text() ?: "0").replace(epNumRegex, "")
                val epNum = epNumStr.toIntOrNull() ?: 0
                val isSpecialEpisode = tmdbSeasonEpisodeCount > 0 && epNum > tmdbSeasonEpisodeCount
                val specialIndex = (epNum - tmdbSeasonEpisodeCount - 1).coerceAtLeast(0)

                // Metadata from TMDB
                // Some titles have seasons split on the site but merged on TMDB (e.g. site S2 E1 == TMDB S1 E13).
                val epMeta = tmdbMetadata?.episodeSummaries?.get(epNum)
                    ?: run {
                        if (sNum > 1 && absoluteEpisodeOffset > 0) {
                            if (tmdbSeason1Metadata == null) {
                                tmdbSeason1Metadata = fetchTmdbMetadata(animeTitle, 1)
                            }
                            tmdbSeason1Metadata?.episodeSummaries?.get(epNum + absoluteEpisodeOffset)
                        } else if (isSpecialEpisode) {
                            tmdbSpecialEpisodes.getOrNull(specialIndex)
                        } else {
                            null
                        }
                    }
                val tmdbName = epMeta?.first

                // GEMINI.md Naming Rules: [S1] Episode Y - [Titre]
                val formattedName = if (epTitle.matches(Regex("(?i)^Episode\\s*\\d+$")) || epTitle.isBlank()) {
                    if (tmdbName != null) "Episode $epNumStr - $tmdbName" else "Episode $epNumStr"
                } else {
                    if (epTitle.contains("Episode", true)) epTitle else "Episode $epNumStr - $epTitle"
                }

                val sPrefix = if (totalSeasons > 1) "[S$sNum] " else ""
                name = "$sPrefix$formattedName"

                this.episode_number = epNumStr.toFloatOrNull() ?: 0f
                this.scanlator = availableLangs.joinToString(", ")

                // Directly use TMDB metadata
                this.preview_url = epMeta?.second
                this.summary = epMeta?.third
            }
        }
    }

    private fun isIgnoredSpecial(name: String?): Boolean {
        val normalized = name?.trim().orEmpty()
        return normalized.isNotBlank() && ignoredSpecialRegex.containsMatchIn(normalized)
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

    override fun List<Video>.sortVideos(): List<Video> {
        val prefVoice = preferences.getString("preferred_voices", "VOSTFR")!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(prefVoice, true) },
                {
                    pQualityRegex.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
            ),
        ).reversed()
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
