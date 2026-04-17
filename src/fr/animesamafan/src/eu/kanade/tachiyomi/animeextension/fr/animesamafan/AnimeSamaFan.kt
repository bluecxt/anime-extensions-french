package eu.kanade.tachiyomi.animeextension.fr.animesamafan

import android.app.Application
import android.content.SharedPreferences
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
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeSamaFan :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Anime-Sama-Fan"
    override val baseUrl = "https://anime-samaa.com"
    override val lang = "fr"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidoExtractor by lazy { VidoExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    private val seasonRegex = Regex("""saison-(\d+)""")
    private val epNumRegex = Regex("[^0-9.]")
    private val qualityCleanRegex = Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s")
    private val qualitySizeRegex = Regex("\\s*\\(\\d+x\\d+\\)")
    private val qualityDefaultRegex = Regex("(?i)(Sendvid|Sibnet|Voe|Vidoza):default")
    private val pQualityRegex = Regex("""(\d+)p""")
    private val whitespaceRegex = Regex("\\s+")

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        )

    // ================== Utils ==================
    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$baseUrl$url"

    private fun parseAnimePage(document: Document): AnimesPage {
        val items = document.select("a.card-base").map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".card-title")?.text() ?: ""
                thumbnail_url = element.selectFirst(".card-image")?.attr("abs:src")
                url = fixUrl(element.attr("href")).replace(baseUrl, "")
            }
        }
        val hasNextPage = document.selectFirst("ul.pagination li.active + li a") != null
        return AnimesPage(items, hasNextPage)
    }

    // ================== Catalogue ===============
    private fun catalogueRequest(page: Int, sort: String = "", query: String = ""): Request {
        val url = "$baseUrl/anime/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("search", query)
            .addQueryParameter("sort", sort)
            .build()

        return GET(url.toString(), headers)
    }

    // ================== Popular ==================
    override fun popularAnimeRequest(page: Int): Request = catalogueRequest(page, "views")
    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimePage(response.asJsoup())

    // ================== Latest ==================
    override fun latestUpdatesRequest(page: Int): Request = catalogueRequest(page, "recent")
    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimePage(response.asJsoup())

    // ================== Search ==================
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

        val items = document.select("a.asn-search-result").map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".asn-search-result-title")?.text() ?: ""
                thumbnail_url = element.selectFirst(".asn-search-result-img")?.attr("abs:src")
                url = fixUrl(element.attr("href")).replace(baseUrl, "")
            }
        }
        return AnimesPage(items, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return GET("$baseUrl/anime/$id", headers)
        }
        val formBody = FormBody.Builder()
            .add("query", query)
            .build()

        return Request.Builder()
            .url("$baseUrl/template-php/defaut/fetch.php")
            .post(formBody)
            .headers(headers)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseSearchPage(response.asJsoup())

    // ================== Details ==================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1.anime-title")?.text() ?: ""
        anime.description = document.selectFirst(".synopsis-content p")?.text()
        anime.genre = document.select(".genre-link").joinToString { it.text() }
        anime.status = SAnime.UNKNOWN
        anime.thumbnail_url = document.selectFirst(".anime-cover img")?.attr("abs:src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        return anime
    }

    // ================== Episodes ==================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        episodes.addAll(parseEpisodesFromDocument(document))

        document.select(".seasons-grid a.season-card").forEach { seasonLink ->
            val sUrl = seasonLink.attr("abs:href")
            if (sUrl != document.location()) {
                try {
                    val sDoc = client.newCall(GET(sUrl, headers)).execute().asJsoup()
                    episodes.addAll(parseEpisodesFromDocument(sDoc))
                } catch (e: Exception) {
                }
            }
        }
        return episodes.distinctBy { it.url }.reversed()
    }

    private fun parseEpisodesFromDocument(document: Document): List<SEpisode> {
        val url = document.location()
        val seasonMatch = seasonRegex.find(url)
        val sNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
        val totalSeasons = document.select(".seasons-grid a.season-card").size + (if (document.selectFirst(".seasons-grid") != null) 1 else 0)
        val sPrefix = if (totalSeasons > 1 && sNum != null) "Season $sNum " else ""

        return document.select("a.episode-card").map { card ->
            val availableLangs = mutableListOf<String>()
            val langs = card.attr("data-langs").uppercase()
            if (langs.contains("VOSTFR")) availableLangs.add("VOSTFR")
            if (langs.contains("VF")) availableLangs.add("VF")

            SEpisode.create().apply {
                this.url = card.attr("abs:href")
                val epTitle = card.selectFirst("h3.episode-title")?.text()?.replace("Épisode", "Episode", true) ?: "Episode"
                val epNumStr = (card.selectFirst(".episode-number")?.text() ?: "0")
                    .replace(epNumRegex, "")

                this.name = if (epTitle.contains("Episode", true)) {
                    sPrefix + epTitle
                } else {
                    "${sPrefix}Episode $epNumStr" + (if (epTitle.isNotBlank()) " - $epTitle" else "")
                }

                this.episode_number = epNumStr.toFloatOrNull() ?: 0f
                this.scanlator = availableLangs.joinToString(", ")
            }
        }
    }

    // ================== Video (Extracteurs) ==================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = mutableListOf<Video>()
        val langs = episode.scanlator?.split(", ") ?: listOf("VOSTFR")

        langs.forEach { lang ->
            try {
                val urlWithLang = if (episode.url.contains("?")) {
                    "${episode.url}&lang=${lang.lowercase()}"
                } else {
                    "${episode.url}?lang=${lang.lowercase()}"
                }
                val doc = client.newCall(GET(urlWithLang, headers)).execute().asJsoup()
                doc.select("iframe").forEach { iframe ->
                    addVideosFromUrl(iframe.attr("abs:src"), lang, videoList)
                }
            } catch (e: Exception) {
            }
        }
        return videoList.map {
            Video(it.url, cleanQuality(it.quality), it.videoUrl, it.headers, it.subtitleTracks, it.audioTracks)
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
            else -> return
        }
        val prefix = "($lang) $server - "
        when (server) {
            "Sibnet" -> sibnetExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            "Sendvid" -> sendvidExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            "Streamtape" -> streamTapeExtractor.videoFromUrl(serverUrl, prefix)?.let { videoList.add(it) }
            "Doodstream" -> doodExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            "Vidoza" -> vidoExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
            "Voe" -> voeExtractor.videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
        }
    }

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(qualityCleanRegex, "")
            .replace(qualitySizeRegex, "")
            .replace(qualityDefaultRegex, "")
            .replace(" - - ", " - ")
            .trim()
            .removeSuffix("-")
            .trim()

        val servers = listOf("Sibnet", "Sendvid", "Streamtape", "Doodstream", "Vidoza", "Voe")
        for (server in servers) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server(?!:)", RegexOption.IGNORE_CASE), server)
            cleaned = cleaned.replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(whitespaceRegex, " ").replace(" - - ", " - ").trim()
    }

    override fun List<Video>.sort(): List<Video> {
        val prefVoice = preferences.getString("preferred_voices", "VOSTFR")!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(prefVoice, true) },
                {
                    pQualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
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

    override fun popularAnimeSelector(): String = ""
    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun popularAnimeNextPageSelector(): String = ""
    override fun latestUpdatesSelector(): String = ""
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String = ""
    override fun searchAnimeSelector(): String = ""
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector(): String = ""
    override fun episodeListSelector(): String = ""
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()
    override fun videoListSelector(): String = ""
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
