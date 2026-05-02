package eu.kanade.tachiyomi.animeextension.fr.animesamafan

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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
    override val baseUrl = "https://anime-samaa.com"
    override val lang = "fr"
    override val supportsLatest = true

    override val json: Json by injectLazy()

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidoExtractor by lazy { VidoExtractor(client) }
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

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(catalogueRequest(page, "views")).execute()
        return parseAnimePage(response.asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(catalogueRequest(page, "recent")).execute()
        return parseAnimePage(response.asJsoup())
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val response = client.newCall(GET("$baseUrl/anime/$id", headers)).execute()
            return parseSearchPage(response.asJsoup())
        }
        val formBody = FormBody.Builder()
            .add("query", query)
            .build()

        val request = Request.Builder()
            .url("$baseUrl/template-php/defaut/fetch.php")
            .post(formBody)
            .headers(headers)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .build()

        val response = client.newCall(request).execute()
        return parseSearchPage(response.asJsoup())
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

        val items = document.select("a.asn-search-result").map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".asn-search-result-title")?.text() ?: ""
                thumbnail_url = element.selectFirst(".asn-search-result-img")?.attr("abs:src")
                url = fixUrl(element.attr("href")).replace(baseUrl, "")
            }
        }
        return AnimesPage(items, false)
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
        tmdbMetadata?.posterUrl?.let { anime.thumbnail_url = it }
        tmdbMetadata?.author?.let { anime.author = it }
        tmdbMetadata?.artist?.let { anime.artist = it }
        tmdbMetadata?.status?.let { anime.status = it }

        return anime
    }

    // ================== Episodes ==================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        episodes.addAll(parseEpisodesFromDocument(document, anime.title))

        document.select(".seasons-grid a.season-card").forEach { seasonLink ->
            val sUrl = seasonLink.attr("abs:href")
            if (sUrl != document.location()) {
                try {
                    val sDoc = client.newCall(GET(sUrl, headers)).execute().asJsoup()
                    episodes.addAll(parseEpisodesFromDocument(sDoc, anime.title))
                } catch (_: Exception) {
                }
            }
        }
        return episodes.distinctBy { it.url }.sortedByDescending { it.episode_number }
    }

    private suspend fun parseEpisodesFromDocument(document: Document, animeTitle: String): List<SEpisode> {
        val url = document.location()
        val seasonMatch = seasonRegex.find(url)
        val sNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        // Fetch specific season metadata from TMDB
        val tmdbMetadata = fetchTmdbMetadata(animeTitle, sNum)

        return document.select("a.episode-card").map { card ->
            val availableLangs = mutableListOf<String>()
            val langs = card.attr("data-langs").uppercase()
            if (langs.contains("VOSTFR")) availableLangs.add("VOSTFR")
            if (langs.contains("VF")) availableLangs.add("VF")

            SEpisode.create().apply {
                this.url = card.attr("abs:href")
                val epTitle = card.selectFirst("h3.episode-title")?.text()?.replace("Épisode", "Episode", true) ?: "Episode"
                val epNumStr = (card.selectFirst(".episode-number")?.text() ?: "0").replace(epNumRegex, "")
                val epNum = epNumStr.toIntOrNull() ?: 0

                // Metadata from TMDB
                val epMeta = tmdbMetadata?.episodeSummaries?.get(epNum)
                val tmdbName = epMeta?.first

                // GEMINI.md Naming Rules + Use TMDB name if site name is empty or generic
                name = if (epTitle.matches(Regex("(?i)^Episode\\s*\\d+$")) || epTitle.isBlank()) {
                    if (tmdbName != null) "Episode $epNumStr - $tmdbName" else "Episode $epNumStr"
                } else {
                    if (epTitle.contains("Episode", true)) epTitle else "Episode $epNumStr - $epTitle"
                }

                this.episode_number = epNumStr.toFloatOrNull() ?: 0f
                this.scanlator = "Season $sNum (${availableLangs.joinToString(", ")})"

                // Directly use TMDB metadata
                this.preview_url = epMeta?.second
                this.summary = epMeta?.third
            }
        }
    }

    // ================== Video (Extracteurs) ==================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val hosters = mutableListOf<Hoster>()
        val langsString = episode.scanlator?.substringAfter("(")?.substringBefore(")") ?: "VOSTFR"
        val langs = langsString.split(", ")

        langs.forEach { lang ->
            try {
                val urlWithLang = if (episode.url.contains("?")) {
                    "${episode.url}&lang=${lang.lowercase()}"
                } else {
                    "${episode.url}?lang=${lang.lowercase()}"
                }
                val doc = client.newCall(GET(urlWithLang, headers)).execute().asJsoup()
                doc.select("iframe").forEach { iframe ->
                    val serverUrl = iframe.attr("abs:src")
                    val serverName = getServerName(serverUrl)
                    if (serverName != null) {
                        hosters.add(Hoster(hosterName = "($lang) $serverName", internalData = "$serverUrl|$lang"))
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

        val servers = listOf("Sibnet", "Sendvid", "Streamtape", "Doodstream", "Vidoza", "Voe")
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
