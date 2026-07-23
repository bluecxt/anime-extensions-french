package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.util.Log
import eu.kanade.tachiyomi.animeextension.fr.animesama.dto.UrlContent
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.ANIMESAMA_LOG
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.Source
import fr.bluecxt.core.network.ErrorWebhook
import fr.bluecxt.core.tmdb.TmdbMetadata
import fr.bluecxt.core.tmdb.utils.extractSeasonNumber
import fr.bluecxt.core.tmdb.utils.fetchTmdbForPanel
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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
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
        val animes = document.selectLog(animesSelector).mapNotNull { anime ->
            if (anime.selectLog(".info-row:has(.info-label:contains(Types)) .info-value").text().trim().equals("Scans", true)) return@mapNotNull null

            val link = anime.selectFirstLog("a")?.safeRelativePath() ?: return@mapNotNull null
            val realLink = link.split("/").take(3).joinToString("/")
            var thumbnail = anime.selectLog("img:not(.ak-cta-flag)").attr("abs:src")

            if (thumbnail.contains("thumb/") && thumbnail.contains(".webp")) {
                thumbnail = thumbnail.replace("thumb/", "").replace(".webp", ".jpg")
            }

            val name = anime.selectFirstLog(".card-title, h2")?.text() ?: "unknown title"
            val names: Set<String> = buildSet {
                add(name)
                add(realLink.substringAfterLast("/").replace("-", " "))
                anime.selectFirstLog("p.alternate-titles")?.text()?.split(",")
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
        val lastPage = document.selectLog("#list_pagination a:last-child").text().toIntOrNull() ?: 0
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

        val isMovie = medias.size == 1 && medias[0].season?.equals("Film", true) == true

        Log.d(ANIMESAMA_LOG, "media number = ${medias.size}")

        if (isHub) {
            anime.apply {
                fetch_type = FetchType.Seasons
                season_number = HUB_SEASON_NUMBER
            }
        }

        if (anime.author.isNullOrEmpty()) anime.author = document.selectFirst("div.info-grid > span:contains(Studio) + .info-val")?.text() ?: ""
        if (anime.genre.isNullOrEmpty()) anime.genre = document.selectLog("div.genres-wrap > span").joinToString { it.text() }
        val statusText = document.selectFirst("div.info-grid > span:contains(État) + .info-val")?.text() ?: ""
        anime.status = when (statusText) {
            "Terminé", "Sorti" -> SAnime.COMPLETED
            "En cours" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

        var year = document.selectFirst("div.info-grid > span:contains(Année) + .info-val")?.text()
        val description = document.selectFirstLog("p#synopsisText")?.text() ?: ""

        val tmdbMetadata = fetchTmdbForPanel(anime.title, season, anime.title, titles)

        tmdbMetadata?.let { metadata ->
            if (anime.artist.isNullOrBlank()) anime.artist = metadata.artist
            if (anime.author.isNullOrBlank()) anime.author = metadata.author
            if (anime.genre.isNullOrBlank()) anime.genre = metadata.genre
            if (anime.status == SAnime.UNKNOWN) anime.status = metadata.status
            // Preserve the season-specific poster when viewing a season or movie page
            val targetPoster = if (season != null && !isHub) (metadata.seasonPosterUrl ?: metadata.mainPosterUrl) else metadata.mainPosterUrl
            anime.thumbnail_url = targetPoster ?: anime.thumbnail_url
            anime.background_url = metadata.mainPosterUrl
            if (year.isNullOrBlank() || metadata.releaseDate != null) year = metadata.releaseDate
        }

        if (anime.description.isNullOrEmpty()) anime.description = buildDescription(description, year)

        return anime
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
            val tmdbMetadata = fetchTmdbForPanel(anime.title, rawSeason, fullTitle, media.titles)

            SAnime.create().apply {
                title = fullTitle
                url = UrlContent(
                    url = media.url,
                    titles = parsedUrl.titles,
                    season = media.season,
                ).toJsonString()
                thumbnail_url = tmdbMetadata?.seasonPosterUrl ?: tmdbMetadata?.mainPosterUrl ?: anime.thumbnail_url

                description = tmdbMetadata?.summary
                author = tmdbMetadata?.author
                artist = tmdbMetadata?.artist
                genre = tmdbMetadata?.genre
                status = tmdbMetadata?.status ?: SAnime.UNKNOWN
                initialized = true
                fetch_type = FetchType.Episodes
                season_number = HUB_SEASON_NUMBER
            }
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (parsedUrl, newUrl) = urlParser(anime.url)
        if (!newUrl) return getLegacyEpisodeList(anime)

        val link = parsedUrl.url
        val document = getOrFetchDocument(link)
        val medias = parseMedias(link, document, parsedUrl.titles)
        val isHub = (parsedUrl.season == null && medias.size > 1)

        if (isHub) {
            return listOf(
                SEpisode.create().apply {
                    url = ""
                    name = ""
                    date_upload = 0L
                    episode_number = 0F
                },
            )
        }

        return emptyList()
    }

    // ============================== Utils ===============================

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
        return if (fullCombined.length > 35 && !cleanSeason.startsWith("Film") && !cleanSeason.contains("OAV", ignoreCase = true)) {
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
        Log.d(ANIMESAMA_LOG, "parseMedias: link = '$link'")
        val scriptContent = document.select("script").joinToString("\n") { it.html() }
        val uncommented = commentRegex.replace(scriptContent, "")
        return uncommented.lines()
            .map { it.trim() }
            .mapNotNull { line ->
                panneauRegex.find(line)?.let { match ->
                    val name = match.groupValues[1]
                    val rawUrl = match.groupValues[2]
                    val urlClean = rawUrl.substringBefore("/").safeRelativePath("$baseUrl$link/").removeSuffix("/")
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

    private fun sendWebhook(url: String, additionalContext: List<String>) = ErrorWebhook.sendWebhook(baseUrl, url, additionalContext)

    companion object {
        const val PREFIX_SEARCH = "id:"
        private val commentRegex = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val panneauRegex = Regex("""panneauAnime\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")
        private val documentCache = synchronizedMap(
            mutableMapOf<String, Pair<Document, Long>>(),
        )
        const val CACHE_LIFETIME = 30000L
    }
}
