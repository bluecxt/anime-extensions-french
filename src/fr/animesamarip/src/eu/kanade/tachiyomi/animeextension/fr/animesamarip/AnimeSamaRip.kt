package eu.kanade.tachiyomi.animeextension.fr.animesamarip

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class AnimeSamaRip :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Anime-Sama (RIP)"
    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!
    }
    override val lang = "fr"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")

    private val json = Json { ignoreUnknownKeys = true }
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://animesama.rip"

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
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = "URL de base"
            setDefaultValue(PREF_URL_DEFAULT)
            summary = baseUrl
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_URL_KEY, newValue as String).commit()
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
            }
        }.also(screen::addPreference)
    }

    // ================== Utils ==================
    private fun fixUrl(url: String): String {
        var fixed = url.substringBefore("/saison-").substringBefore("/vostfr/").substringBefore("/vf/")
        if (!fixed.endsWith(".html")) {
            fixed = fixed.removeSuffix("/") + ".html"
        }
        if (!fixed.startsWith("http")) {
            fixed = if (fixed.startsWith("/")) "$baseUrl$fixed" else "$baseUrl/anime/$fixed"
        }
        return fixed
    }

    // AnimeSama-like logic to fetch seasons from main page and return as SAnimes
    private fun fetchAnimeSeasons(anime: SAnime): List<SAnime> = try {
        val animeUrl = if (anime.url.startsWith("http")) anime.url else baseUrl + anime.url
        val response = client.newCall(GET(animeUrl, headers)).execute()
        val doc = response.asJsoup()
        val animeName = doc.selectFirst("h1.anime-title, h1.season-title")?.text() ?: anime.title
        val desc = doc.selectFirst(".synopsis-content p")?.text() ?: anime.description
        val gnres = doc.select(".genre-link").joinToString { it.text() }.ifEmpty { anime.genre }
        val statusText = doc.selectFirst(".status-ongoing")?.text() ?: doc.selectFirst(".status-completed")?.text()
        val statusVal = when {
            statusText?.contains("En Cours", true) == true -> SAnime.ONGOING
            statusText?.contains("Terminé", true) == true -> SAnime.COMPLETED
            else -> anime.status
        }
        val thumb = doc.selectFirst(".anime-cover img, .season-cover img")?.attr("abs:src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: anime.thumbnail_url

        val seasonLinks = doc.select(".seasons-grid a.season-card")
        if (seasonLinks.isEmpty()) {
            // Return single anime
            listOf(
                anime.apply {
                    title = animeName
                    description = desc
                    genre = gnres
                    status = statusVal
                    thumbnail_url = thumb
                    initialized = true
                },
            )
        } else {
            seasonLinks.map { seasonLink ->
                val sName = seasonLink.selectFirst(".season-title")?.text() ?: "Saison"
                val sUrl = seasonLink.attr("abs:href").replace(baseUrl, "")
                SAnime.create().apply {
                    title = if (sName.contains(animeName, ignoreCase = true)) sName else "$animeName $sName"
                    url = sUrl
                    description = desc
                    genre = gnres
                    status = statusVal
                    thumbnail_url = thumb
                    initialized = true
                }
            }
        }
    } catch (e: Exception) {
        listOf(anime)
    }

    private fun paginateAndFetchSeasons(items: List<SAnime>, page: Int): AnimesPage {
        val chunks = items.chunked(8) // process 8 animes per page to avoid rate limit
        val requestedIdx = page - 1
        if (requestedIdx >= chunks.size) return AnimesPage(emptyList(), false)

        val animes = chunks[requestedIdx].flatMap { fetchAnimeSeasons(it) }
        return AnimesPage(animes, requestedIdx + 1 < chunks.size)
    }

    // ================== Popular ==================
    override fun popularAnimeRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder().addQueryParameter("pagechunk", page.toString()).build()
        return GET(url.toString(), headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val page = response.request.url.queryParameter("pagechunk")?.toIntOrNull() ?: 1

        val items = document.select("#mostViewedSlider .catalog-card a").map {
            val anime = SAnime.create()
            anime.title = it.selectFirst(".card-title")?.text() ?: ""
            anime.thumbnail_url = it.selectFirst(".card-image")?.attr("abs:src")
            anime.url = fixUrl(it.attr("abs:href")).replace(baseUrl, "")
            anime
        }.distinctBy { it.url }

        return paginateAndFetchSeasons(items, page)
    }

    override fun popularAnimeSelector(): String = "div.card-base a"
    override fun popularAnimeFromElement(element: Element) = throw UnsupportedOperationException("Not used")
    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.active + li a"

    // ================== Latest ==================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder().addQueryParameter("pagechunk", page.toString()).build()
        return GET(url.toString(), headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val page = response.request.url.queryParameter("pagechunk")?.toIntOrNull() ?: 1

        val items = document.select("#latestEpisodesSlider .card-base a").map {
            val anime = SAnime.create()
            anime.title = it.selectFirst(".card-title")?.text() ?: ""
            anime.thumbnail_url = it.selectFirst(".card-image")?.attr("abs:src")
            anime.url = fixUrl(it.attr("abs:href")).replace(baseUrl, "")
            anime
        }.distinctBy { it.url }

        return paginateAndFetchSeasons(items, page)
    }

    override fun latestUpdatesSelector() = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException("Not used")

    // ================== Search ==================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("q", query) // Store query in URL for parse
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val query = response.request.url.queryParameter("q") ?: ""
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

        val parseAnimes = { doc: Document ->
            doc.select("a.card-base").map { element ->
                val anime = SAnime.create()
                anime.title = element.selectFirst(".card-title")?.text() ?: ""
                anime.thumbnail_url = element.selectFirst(".card-image")?.attr("abs:src")
                anime.url = fixUrl(element.attr("abs:href")).replace(baseUrl, "")
                anime
            }.distinctBy { it.url }
        }

        val items = parseAnimes(document)

        // Local filtering
        val filtered = if (query.isNotEmpty()) {
            items.filter { it.title.contains(query, ignoreCase = true) }
        } else {
            items
        }

        // If no results on this page and more pages exist, we fetch more pages in parallel.
        // For a true "app search", let's fetch 9 more pages (10 total) for the first page search query.
        val results = if (query.isNotEmpty() && currentPage == 1) {
            val moreAnimes = (2..10).toList().parallelStream().flatMap { p ->
                try {
                    val doc = client.newCall(GET("$baseUrl/catalogue/?page=$p", headers)).execute().asJsoup()
                    parseAnimes(doc).filter { it.title.contains(query, ignoreCase = true) }.stream()
                } catch (e: Exception) {
                    java.util.stream.Stream.empty<SAnime>()
                }
            }.toList()
            (filtered + moreAnimes).distinctBy { it.url }
        } else {
            filtered
        }

        val hasNextPage = document.selectFirst("ul.pagination li.active + li a") != null

        // Fetch seasons for each search result to avoid "0 episodes" on main page
        val animes = results.flatMap { fetchAnimeSeasons(it) }
        return AnimesPage(animes, query.isEmpty() && hasNextPage)
    }

    override fun searchAnimeSelector() = throw UnsupportedOperationException("Not used")
    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException("Not used")
    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException("Not used")

    // ================== Details ==================
    override fun animeDetailsParse(document: Document): SAnime {
        // If it's a season page, we might just parse the season name.
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1.anime-title, h1.season-title")?.text() ?: ""
        val sName = document.selectFirst(".current-season .season-title")?.text()
        if (sName != null && !anime.title.contains(sName, true)) {
            anime.title += " $sName"
        }
        anime.description = document.selectFirst(".synopsis-content p")?.text()
        anime.genre = document.select(".genre-link").joinToString { it.text() }
        val statusText = document.selectFirst(".status-ongoing")?.text() ?: document.selectFirst(".status-completed")?.text()
        anime.status = when {
            statusText?.contains("En Cours", true) == true -> SAnime.ONGOING
            statusText?.contains("Terminé", true) == true -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        anime.thumbnail_url = document.selectFirst(".anime-cover img, .season-cover img")?.attr("abs:src") ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        return anime
    }

    // ================== Episodes ==================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val allEpisodeCards = document.select("a.episode-card")

        return allEpisodeCards.map { card ->
            val url = card.attr("abs:href")
            val epTitle = card.selectFirst("h3.episode-title")?.text() ?: "Épisode"
            val epNumStr = (card.selectFirst(".episode-number")?.text() ?: epTitle)
                .replace(Regex("[^0-9.]"), "")
            val epNum = epNumStr.toFloatOrNull() ?: 0f

            val langs = card.attr("data-langs").uppercase()
            val availableLangs = mutableListOf<String>()
            if (langs.contains("VOSTFR")) availableLangs.add("VOSTFR")
            if (langs.contains("VF")) availableLangs.add("VF")

            SEpisode.create().apply {
                this.url = url
                this.name = epTitle
                this.episode_number = epNum
                this.scanlator = availableLangs.joinToString(", ")
            }
        }.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException("Not used")
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException("Not used")

    // ================== Video ==================
    override fun videoListParse(response: Response): List<Video> = emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = mutableListOf<Video>()
        val baseUrl = episode.url
        val langs = episode.scanlator?.split(", ") ?: listOf("VOSTFR")

        langs.forEach { lang ->
            try {
                val urlWithLang = if (baseUrl.contains("?")) "$baseUrl&lang=${lang.lowercase()}" else "$baseUrl?lang=${lang.lowercase()}"
                val response = client.newCall(GET(urlWithLang, headers)).execute()
                val doc = response.asJsoup()

                // Check all iframes
                doc.select("iframe").forEach { iframe ->
                    val serverUrl = iframe.attr("abs:src")
                    addVideosFromUrl(serverUrl, lang, videoList)
                }
            } catch (e: Exception) {}
        }

        return videoList.map { video ->
            Video(video.url, cleanQuality(video.quality), video.videoUrl, video.headers)
        }.sort()
    }

    private fun addVideosFromUrl(serverUrl: String, lang: String, videoList: MutableList<Video>) {
        val server = when {
            serverUrl.contains("sibnet.ru") -> "Sibnet"
            serverUrl.contains("sendvid.com") -> "Sendvid"
            serverUrl.contains("streamtape") || serverUrl.contains("shavetape") -> "Streamtape"
            serverUrl.contains("dood") -> "Doodstream"
            serverUrl.contains("vidoza.net") -> "Vidoza"
            serverUrl.contains("voe.sx") -> "Voe"
            else -> "Serveur"
        }
        val prefix = "($lang) $server - "
        when (server) {
            "Sibnet" -> SibnetExtractor(client).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            "Sendvid" -> SendvidExtractor(client, headers).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            "Streamtape" -> StreamTapeExtractor(client).videoFromUrl(serverUrl, prefix)?.let { videoList.add(it) }
            "Doodstream" -> DoodExtractor(client).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            "Vidoza" -> VidoExtractor(client).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            "Voe" -> VoeExtractor(client, headers).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
        }
    }

    private fun cleanQuality(quality: String): String = quality.replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s"), "")
        .replace(Regex("\\s*\\(\\d+x\\d+\\)"), "")
        .replace(Regex("(?i)Sendvid:default"), "")
        .replace(Regex("(?i)Sibnet:default"), "")
        .replace(Regex("(?i)Doodstream:default"), "")
        .replace(Regex("(?i)Voe:default"), "")
        .replace(" - - ", " - ")
        .trim()
        .removeSuffix("-")
        .trim()

    override fun List<Video>.sort(): List<Video> {
        val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(prefVoice, true) },
                { it.quality.contains(prefServer, true) },
            ),
        ).reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException("Not used")
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException("Not used")
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException("Not used")
}
