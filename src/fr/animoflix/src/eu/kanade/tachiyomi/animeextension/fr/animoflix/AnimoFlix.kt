package eu.kanade.tachiyomi.animeextension.fr.animoflix

import android.app.Application
import androidx.preference.EditTextPreference
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
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.Source
import fr.bluecxt.core.TmdbMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimoFlix : Source() {
    override val name = "AnimoFlix"
    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!
    }
    override val lang = "fr"
    override val supportsLatest = true

    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val vidoExtractor by lazy { VidoExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    companion object {
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://animoflix.to"

        private const val PREF_VOICES_KEY = "preferred_voices"
        private const val PREF_VOICES_TITLE = "Préférence des voix"
        private val VOICES_ENTRIES = arrayOf("Préférer VOSTFR", "Préférer VF")
        private val VOICES_VALUES = arrayOf("VOSTFR", "VF")
        private const val PREF_VOICES_DEFAULT = "VOSTFR"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Serveur préféré"
        private val SERVER_ENTRIES = arrayOf("Sibnet", "Sendvid", "Voe", "Streamtape", "Doodstream", "Vidoza")
        private val SERVER_VALUES = arrayOf("sibnet", "sendvid", "voe", "streamtape", "dood", "vidoza")
        private const val PREF_SERVER_DEFAULT = "sibnet"

        private val seasonRegex = Regex("""(?i)(?:Saison|Season)\s*(\d+)|(\d+)$""")
        private val epNumRegex = Regex("""(\d+(?:\.\d+)?)""")
        private val qualityNumRegex = Regex("""(\d+)p""")
        private val lecteurRegex = Regex("(?i)Lecteur\\s*\\d+\\s*-?\\s*")
        private val cleanTitleRegex = Regex("(?i)(?:Saison|Season)\\s*\\d+|FILM|MOVIE|OAV|OVA|\\(TV\\)|\\(Film\\)|\\(OAV\\)|\\(OVA\\)|\\s+\\d+$")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = "URL de base"
            setDefaultValue(PREF_URL_DEFAULT)
            summary = baseUrl
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_URL_KEY, newValue as String).commit()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = PREF_VOICES_TITLE
            entries = VOICES_ENTRIES
            entryValues = VOICES_VALUES
            setDefaultValue(PREF_VOICES_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_VOICES_KEY, newValue as String).commit()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = SERVER_ENTRIES
            entryValues = SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_SERVER_KEY, newValue as String).commit()
                true
            }
        }.also(screen::addPreference)
    }

    private fun parseSeasonNumber(title: String): Double {
        val t = title.trim()
        return when {
            t.contains("Film", true) || t.contains("Movie", true) -> -2.0

            t.contains("OAV", true) || t.contains("OVA", true) -> -3.0

            else -> {
                val result = seasonRegex.find(t)
                val num = result?.groupValues?.get(1)?.ifBlank { null } ?: result?.groupValues?.get(2)?.ifBlank { null }
                num?.toDoubleOrNull() ?: 1.0
            }
        }
    }

    private fun getHubUrl(url: String): String {
        val segments = url.trim('/').split("/")
        return if (segments.size >= 2) {
            "/${segments[0]}/${segments[1]}/"
        } else {
            if (url.endsWith("/")) url else "$url/"
        }
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http", true)) return url
        return if (url.startsWith("/")) "$baseUrl$url" else "$baseUrl/$url"
    }

    private fun getCleanTitle(title: String): String = title.replace(cleanTitleRegex, "").trim()

    private suspend fun getBestTmdbMetadata(fullTitle: String, url: String, sNum: Double): TmdbMetadata? {
        val baseTitle = getCleanTitle(fullTitle)
        val hubName = url.trim('/').split("/").getOrNull(1)?.replace("-", " ") ?: ""

        return when {
            sNum == -2.0 -> fetchTmdbMovieMetadata(baseTitle) ?: fetchTmdbMetadata(baseTitle)
            sNum == -3.0 -> fetchTmdbMetadata(baseTitle, 0)
            else -> fetchTmdbMetadata(baseTitle, sNum.toInt()) ?: fetchTmdbMetadata(hubName, sNum.toInt())
        }
    }

    // ================== Catalogue (/catalogue/) ==================

    override fun getFilterList() = AnimoFlixCatalogueFilters.FILTER_LIST

    private fun catalogueRequest(
        page: Int,
        query: String = "",
        genre: String = "",
        status: String = "",
        lang: String = "",
        type: String = "",
        sort: String = "recent",
        letter: String = "",
    ): Request {
        val q = query.trim()
        val g = genre.trim()
        val st = status.trim()
        val lg = lang.trim()
        val tp = type.trim()
        val so = sort.trim()
        val lt = letter.trim()

        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder()
            .addQueryParameter("ajax", "1")
            .addQueryParameter("page", page.toString())
            .apply {
                if (q.isNotBlank()) addQueryParameter("search", q)
                if (g.isNotBlank()) addQueryParameter("genre", g)
                if (st.isNotBlank()) addQueryParameter("status", st)
                if (lg.isNotBlank()) addQueryParameter("lang", lg)
                if (tp.isNotBlank()) addQueryParameter("type", tp)
                if (so.isNotBlank()) addQueryParameter("sort", so)
                if (lt.isNotBlank()) addQueryParameter("letter", lt)
            }
            .build()

        return GET(url.toString(), headers)
    }

    private fun parseCatalogueAjaxResponse(response: Response, page: Int): AnimesPage {
        val body = response.body.string()
        val jsonObj = JSONObject(body)

        val cardsHtml = jsonObj.optString("animeCards")
        val paginationHtml = jsonObj.optString("pagination")

        val cardsDoc = Jsoup.parseBodyFragment(cardsHtml, baseUrl)
        val items = cardsDoc.select(".anime-card-pro a[href]")
            .mapNotNull { a ->
                val href = a.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null

                val absUrl = fixUrl(href)
                val path = try {
                    absUrl.toHttpUrl().encodedPath
                } catch (_: Exception) {
                    href
                }

                val title = a.selectFirst(".card-title-pro")?.text()?.trim().orEmpty()
                val thumb = a.selectFirst("img.card-image-pro")?.attr("abs:src")
                    ?.takeIf { it.isNotBlank() }
                    ?: a.selectFirst("img.card-image-pro")?.attr("src")

                SAnime.create().apply {
                    this.url = path
                    this.title = title
                    this.thumbnail_url = thumb
                }
            }
            .distinctBy { it.url }

        val paginationDoc = Jsoup.parseBodyFragment(paginationHtml, baseUrl)
        val pages = paginationDoc.select(".pagination-pro a[data-page]")
            .mapNotNull { it.attr("data-page").toIntOrNull() }
        val hasNextPage = pages.any { it > page }

        return AnimesPage(items, hasNextPage)
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        // Site behavior: when "sort" is omitted (or invalid), it behaves like a "views"/popular sort.
        val response = client.newCall(catalogueRequest(page = page, sort = "")).execute()
        return parseCatalogueAjaxResponse(response, page)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(catalogueRequest(page = page, sort = "recent")).execute()
        return parseCatalogueAjaxResponse(response, page)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val f = AnimoFlixCatalogueFilters.getSearchFilters(filters)
        val response = client.newCall(
            catalogueRequest(
                page = page,
                query = query,
                genre = f.genre,
                status = f.status,
                lang = f.lang,
                type = f.type,
                sort = f.sort,
                letter = f.letter,
            ),
        ).execute()
        return parseCatalogueAjaxResponse(response, page)
    }

    private object AnimoFlixCatalogueFilters {
        // Note: the site uses literal strings for genre (e.g. "Action").
        // This is a curated list; we can expand it if you want the full site list.
        private val GENRE_OPTIONS = arrayOf(
            "Tous les genres" to "",
            "Action" to "Action",
            "Aventure" to "Aventure",
            "Comédie" to "Comédie",
            "Drame" to "Drame",
            "Fantastique" to "Fantastique",
            "Fantasy" to "Fantasy",
            "Horreur" to "Horreur",
            "Mystère" to "Mystère",
            "Romance" to "Romance",
            "Science-Fiction" to "Science-Fiction",
            "Slice of Life" to "Slice of Life",
            "Sport" to "Sport",
            "Thriller" to "Thriller",
            "Isekai" to "Isekai",
            "Ecchi" to "Ecchi",
            "Harem" to "Harem",
            "Mecha" to "Mecha",
            "Magie" to "Magie",
            "Super pouvoirs" to "Super pouvoirs",
        )

        private val STATUS_OPTIONS = arrayOf(
            "Tous les statuts" to "",
            "En cours" to "ongoing",
            "Terminé" to "completed",
        )

        private val LANG_OPTIONS = arrayOf(
            "Toutes" to "",
            "VF" to "VF",
            "VOSTFR" to "VOSTFR",
        )

        private val TYPE_OPTIONS = arrayOf(
            "Tous les types" to "",
            "Série" to "serie",
            "Film" to "film",
            "OAV" to "oav",
            "Scans" to "scans",
            "Anime + Scans" to "both",
        )

        private val SORT_OPTIONS = arrayOf(
            "Populaire" to "",
            "Plus récents" to "recent",
            "A → Z" to "az",
        )

        private val LETTER_OPTIONS = (listOf("Toutes" to "") + ('A'..'Z').map { it.toString() to it.toString() }).toTypedArray()

        class GenreFilter : AnimeFilter.Select<String>("Genre", GENRE_OPTIONS.map { it.first }.toTypedArray(), 0)
        class StatusFilter : AnimeFilter.Select<String>("Statut", STATUS_OPTIONS.map { it.first }.toTypedArray(), 0)
        class LangFilter : AnimeFilter.Select<String>("Langue", LANG_OPTIONS.map { it.first }.toTypedArray(), 0)
        class TypeFilter : AnimeFilter.Select<String>("Type", TYPE_OPTIONS.map { it.first }.toTypedArray(), 0)
        class SortFilter : AnimeFilter.Select<String>("Trier par", SORT_OPTIONS.map { it.first }.toTypedArray(), 0)
        class LetterFilter : AnimeFilter.Select<String>("Lettre", LETTER_OPTIONS.map { it.first }.toTypedArray(), 0)

        val FILTER_LIST get() = AnimeFilterList(
            GenreFilter(),
            StatusFilter(),
            LangFilter(),
            TypeFilter(),
            SortFilter(),
            LetterFilter(),
        )

        data class SearchFilters(
            val genre: String,
            val status: String,
            val lang: String,
            val type: String,
            val sort: String,
            val letter: String,
        )

        fun getSearchFilters(filters: AnimeFilterList): SearchFilters {
            if (filters.isEmpty()) {
                return SearchFilters(genre = "", status = "", lang = "", type = "", sort = "recent", letter = "")
            }

            val genreIndex = filters.filterIsInstance<GenreFilter>().firstOrNull()?.state ?: 0
            val statusIndex = filters.filterIsInstance<StatusFilter>().firstOrNull()?.state ?: 0
            val langIndex = filters.filterIsInstance<LangFilter>().firstOrNull()?.state ?: 0
            val typeIndex = filters.filterIsInstance<TypeFilter>().firstOrNull()?.state ?: 0
            val sortIndex = filters.filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0
            val letterIndex = filters.filterIsInstance<LetterFilter>().firstOrNull()?.state ?: 0

            return SearchFilters(
                genre = GENRE_OPTIONS.getOrNull(genreIndex)?.second ?: "",
                status = STATUS_OPTIONS.getOrNull(statusIndex)?.second ?: "",
                lang = LANG_OPTIONS.getOrNull(langIndex)?.second ?: "",
                type = TYPE_OPTIONS.getOrNull(typeIndex)?.second ?: "",
                sort = SORT_OPTIONS.getOrNull(sortIndex)?.second ?: "recent",
                letter = LETTER_OPTIONS.getOrNull(letterIndex)?.second ?: "",
            )
        }
    }

    // ================== Details ==================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()

        val fullTitle = document.selectFirst("h1.anime-title-pro, h1")?.text() ?: anime.title
        val sSegment = anime.url.trim('/').split("/").lastOrNull() ?: ""
        val sName = if (sSegment.contains("saison", true) || sSegment.contains("season", true)) sSegment else fullTitle

        anime.title = fullTitle
        anime.season_number = parseSeasonNumber(sName)

        anime.description = document.selectFirst(".synopsis-text")?.text()
        anime.genre = document.select(".genre-tag").joinToString { it.text() }
        val statusText = document.selectFirst(".status-ongoing")?.text() ?: document.selectFirst(".status-completed")?.text()
        anime.status = when {
            statusText?.contains("En Cours", true) == true -> SAnime.ONGOING
            statusText?.contains("Terminé", true) == true -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        anime.thumbnail_url = document.selectFirst("img.poster-image")?.attr("abs:src") ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        // TMDB Metadata with type precision
        val tmdbType = if (anime.season_number == -2.0) "movie" else "tv"
        val tmdbMetadata = fetchTmdbMetadata(getCleanTitle(fullTitle), type = tmdbType)

        tmdbMetadata?.summary?.let { anime.description = it }
        tmdbMetadata?.releaseDate?.let { date ->
            anime.description = "Date de sortie : $date\n\n${anime.description ?: ""}"
        }
        tmdbMetadata?.posterUrl?.let { anime.thumbnail_url = it }
        tmdbMetadata?.author?.let { anime.author = it }
        tmdbMetadata?.artist?.let { anime.artist = it }
        tmdbMetadata?.status?.let { anime.status = it }

        return anime
    }

    // ================== Seasons ==================
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val hubUrl = getHubUrl(anime.url)
        val response = client.newCall(GET(baseUrl + hubUrl, headers)).execute()
        val document = response.asJsoup()
        val seasonCards = document.select(".seasons-grid a.season-card")

        return if (seasonCards.isNotEmpty()) {
            seasonCards.map { card ->
                SAnime.create().apply {
                    val sTitle = card.selectFirst(".season-title")?.text() ?: ""
                    title = sTitle
                    url = card.attr("href").let { if (it.startsWith("http")) it.toHttpUrl().encodedPath else it }
                    thumbnail_url = anime.thumbnail_url
                    season_number = parseSeasonNumber(sTitle)
                    initialized = true
                }
            }
        } else {
            emptyList()
        }
    }

    // ================== Episodes ==================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()
        val episodeCards = document.select("a.episode-card")
        val seasonCards = document.select(".seasons-grid a.season-card")

        return if (episodeCards.isEmpty() && seasonCards.isNotEmpty()) {
            // HUB MODE
            coroutineScope {
                seasonCards.map { card ->
                    async(Dispatchers.IO) {
                        val seasonName = card.selectFirst(".season-title")?.text() ?: ""
                        val seasonNum = parseSeasonNumber(seasonName)
                        val seasonUrl = card.attr("href").let { if (it.startsWith("http")) it else baseUrl + it }
                        val seasonResponse = client.newCall(GET(seasonUrl, headers)).execute()

                        parseEpisodesFromSeasonPage(seasonResponse.asJsoup(), seasonName, seasonCards.size, anime.title, seasonUrl)
                    }
                }.awaitAll().flatten().reversed()
            }
        } else {
            // SEASON MODE
            val sSegment = anime.url.trim('/').split("/").lastOrNull() ?: ""
            val totalSeasons = document.select(".seasons-grid a.season-card").size.let { if (it == 0) 1 else it }
            val baseTitle = document.selectFirst("h1.anime-title-pro, h1")?.text() ?: anime.title
            parseEpisodesFromSeasonPage(document, sSegment, totalSeasons, baseTitle, anime.url).reversed()
        }
    }

    private suspend fun parseEpisodesFromSeasonPage(document: Document, seasonName: String, totalSeasons: Int, animeTitle: String, animeUrl: String): List<SEpisode> {
        val allEpisodeCards = document.select("a.episode-card")
        val episodesMap = mutableMapOf<Float, MutableMap<String, String>>()
        val titlesMap = mutableMapOf<Float, String>()

        allEpisodeCards.forEach { card ->
            val url = card.attr("href").let { if (it.startsWith("http")) it else baseUrl + it }
            val epTitle = card.selectFirst("h3.episode-title")?.text() ?: "Episode"
            val epNumRaw = card.selectFirst(".episode-number")?.text() ?: epTitle
            val epNum = epNumRegex.find(epNumRaw)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

            if (!episodesMap.containsKey(epNum)) {
                episodesMap[epNum] = mutableMapOf()
                titlesMap[epNum] = epTitle.replace("Épisode", "Episode", true)
            }
            val lang = if (url.contains("/vf/")) "VF" else "VOSTFR"
            episodesMap[epNum]!![lang] = url
        }

        // --- Simplified Metadata Fetching ---
        val sNumRaw = parseSeasonNumber(seasonName)
        val tmdbMetadata = getBestTmdbMetadata(animeTitle, animeUrl, sNumRaw)

        return episodesMap.keys.sorted().map { epNum ->
            val langs = episodesMap[epNum]!!
            SEpisode.create().apply {
                episode_number = epNum
                val siteEpTitle = titlesMap[epNum] ?: "Episode $epNum"

                // Metadata direct lookup only
                val epLookup = if (sNumRaw == -2.0) 1 else epNum.toInt()
                val epMeta = tmdbMetadata?.episodeSummaries?.get(epLookup)
                val tmdbName = epMeta?.first

                // GEMINI.md Naming Rules: [S1] Episode Y - [Titre]
                val formattedName = if (siteEpTitle.matches(Regex("(?i)^Episode\\s*\\d+$")) || siteEpTitle.isBlank()) {
                    if (tmdbName != null) "Episode ${epNum.toInt()} - $tmdbName" else "Episode ${epNum.toInt()}"
                } else {
                    if (siteEpTitle.contains("Episode", true)) siteEpTitle else "Episode ${epNum.toInt()} - $siteEpTitle"
                }

                name = if (totalSeasons > 1 && seasonName.isNotBlank()) {
                    val sN = sNumRaw
                    val sPrefix = when {
                        sN == -2.0 -> "[Movie]"
                        sN == -3.0 -> "[OVA]"
                        sN > 0 -> "[S${sN.toInt()}]"
                        else -> "[Special]"
                    }
                    "$sPrefix $formattedName"
                } else {
                    formattedName
                }

                val scanlatorsList = mutableListOf<String>()
                if (langs.containsKey("VOSTFR")) scanlatorsList.add("VOSTFR")
                if (langs.containsKey("VF")) scanlatorsList.add("VF")
                scanlator = scanlatorsList.joinToString(", ")

                this.preview_url = epMeta?.second
                this.summary = epMeta?.third
                url = json.encodeToString(langs)
            }
        }
    }

    // ================== Hoster ==================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val hosterList = mutableListOf<Hoster>()
        val langsMap = try {
            json.decodeFromString<Map<String, String>>(episode.url)
        } catch (e: Exception) {
            return emptyList()
        }

        langsMap.forEach { (lang, url) ->
            hosterList.add(Hoster(hosterUrl = url, hosterName = lang, lazy = true))
        }
        return hosterList
    }

    // ================== Video ==================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val videoList = mutableListOf<Video>()
        val lang = hoster.hosterName
        val url = hoster.hosterUrl

        try {
            val response = client.newCall(GET(url, headers)).execute()
            val doc = response.asJsoup()
            doc.select("select#lecteurSelect option").forEach { option ->
                val serverUrl = option.attr("value")
                val serverName = when {
                    serverUrl.contains("sibnet.ru") -> "Sibnet"
                    serverUrl.contains("sendvid.com") -> "Sendvid"
                    serverUrl.contains("streamtape") || serverUrl.contains("shavetape") -> "Streamtape"
                    serverUrl.contains("dood") -> "Doodstream"
                    serverUrl.contains("vidoza.net") -> "Vidoza"
                    serverUrl.contains("voe.sx") -> "Voe"
                    else -> option.text().trim().replace(lecteurRegex, "")
                }
                val prefix = "($lang) $serverName - "
                when {
                    serverUrl.contains("sibnet.ru") -> sibnetExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
                    serverUrl.contains("sendvid.com") -> sendvidExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
                    serverUrl.contains("streamtape") || serverUrl.contains("shavetape") -> streamTapeExtractor.videoFromUrl(serverUrl, prefix)?.let { videoList.add(it) }
                    serverUrl.contains("dood") -> doodExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
                    serverUrl.contains("vidoza.net") -> vidoExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
                    serverUrl.contains("voe.sx") -> voeExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
                }
            }
        } catch (e: Exception) {}

        return videoList.map { it.copy(videoTitle = cleanQuality(it.videoTitle), resolution = qualityNumRegex.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull()) }.sortVideos()
    }

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s"), "")
            .replace(Regex("\\s*\\(\\d+x\\d+\\)"), "").replace(Regex("(?i)(Sendvid|Sibnet|Voe|Streamtape|Doodstream|Vidoza):default"), "")
            .replace(" - - ", " - ").trim().removeSuffix("-").trim()
        val servers = listOf("Sibnet", "Sendvid", "Streamtape", "Doodstream", "Vidoza", "Voe")
        for (server in servers) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server(?!:)", RegexOption.IGNORE_CASE), server)
            cleaned = cleaned.replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(lecteurRegex, "").replace(Regex("\\s+"), " ").replace(" - - ", " - ").trim()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefVoice = preferences.getString("preferred_voices", "VOSTFR")!!
        return this.sortedWith(compareBy({ it.videoTitle.contains(prefVoice, true) }, { it.resolution ?: 0 })).reversed()
    }
}
