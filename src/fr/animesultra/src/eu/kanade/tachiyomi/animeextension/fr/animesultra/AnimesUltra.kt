package eu.kanade.tachiyomi.animeextension.fr.animesultra

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy

class AnimesUltra : Source() {

    override val name = "AnimesUltra"
    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!
    }
    override val lang = "fr"
    override val supportsLatest = true
    override val client = network.client

    override val json: Json by injectLazy()

    companion object {
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://ww.animesultra.org"

        private val CLEAN_REGEX = Regex("(?i)\\s*(\\((?:VF|VOSTFR|AU|DLL)\\)|\\b(?:VF|VOSTFR|AU|DLL)\\b)")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val NEWS_ID_REGEX = Regex("""/(\d+)-""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val CLEAN_QUALITY_REGEX_1 = Regex("(?i)\\(\\s*Player\\s*\\)\\s*|\\(\\s*None\\s*\\)\\s*|\\s*\\(\\d+x\\d+\\)|\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s")
        private val CLEAN_QUALITY_REGEX_2 = Regex("(?i)Sendvid:default|Sibnet:default|Voe:default|Vidmoly:default")
        private val SERVERS = listOf("Vidmoly", "Sibnet", "Sendvid", "Voe", "Doodstream", "Okru", "Filemoon", "Player")
    }

    @Serializable
    data class AnimeUrl(var vostfr: String? = null, var vf: String? = null)

    @Serializable
    data class FullStoryResponse(val status: Boolean = false, val html: String = "")

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = "Base URL"
            setDefaultValue(PREF_URL_DEFAULT)
            summary = baseUrl
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_URL_KEY, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/anime-vostfr/page/$page/", headers)).execute()
        val document = response.asJsoup()
        val items = document.select("div.flw-item").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("h3.film-name a")!!
                title = link.text()
                setUrlWithoutDomain(link.attr("abs:href"))
                thumbnail_url = element.selectFirst("img.film-poster-img")?.attr("abs:data-src") ?: element.selectFirst("img.film-poster-img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst(".pagi-nav a:contains(Suivant), .pagi-nav a:contains(Next)") != null
        return AnimesPage(unifyAnimes(items), hasNextPage)
    }

    private fun unifyAnimes(animes: List<SAnime>): List<SAnime> = animes.distinctBy { it.url }.groupBy { cleanTitle(it.title) }.map { (title, versions) ->
        val anime = SAnime.create()
        anime.title = title
        anime.thumbnail_url = versions.firstOrNull { it.thumbnail_url != null }?.thumbnail_url ?: versions.first().thumbnail_url
        val urlMap = AnimeUrl(
            vostfr = versions.firstOrNull { it.title.contains("VOSTFR", true) }?.url ?: versions.firstOrNull { !it.title.contains("VF", true) }?.url,
            vf = versions.firstOrNull { it.title.contains("VF", true) }?.url,
        )
        anime.url = json.encodeToString(urlMap)
        anime
    }

    private fun cleanTitle(title: String): String = title.replace(CLEAN_REGEX, "").replace(WHITESPACE_REGEX, " ").trim()

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/page/$page/", headers)).execute()
        return getPopularAnime(page) // Reuse logic
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.newCall(GET("$baseUrl/index.php?do=search&subaction=search&story=$query", headers)).execute()
        val document = response.asJsoup()
        val items = document.select("div.flw-item").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("h3.film-name a")!!
                title = link.text()
                setUrlWithoutDomain(link.attr("abs:href"))
                thumbnail_url = element.selectFirst("img.film-poster-img")?.attr("abs:data-src") ?: element.selectFirst("img.film-poster-img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst(".pagi-nav a:contains(Suivant), .pagi-nav a:contains(Next)") != null
        return AnimesPage(unifyAnimes(items), hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val urlMap = try {
            json.decodeFromString<AnimeUrl>(anime.url)
        } catch (e: Exception) {
            if (anime.title.isNotBlank() && anime.title.contains("VF", true)) {
                AnimeUrl(vf = anime.url)
            } else {
                AnimeUrl(vostfr = anime.url)
            }
        }

        val targetUrl = urlMap.vostfr ?: urlMap.vf ?: return anime
        val document = client.newCall(GET(baseUrl + targetUrl, headers)).execute().asJsoup()

        val pageTitle = document.selectFirst("h2.film-name")?.text() ?: anime.title
        val cleanedTitle = cleanTitle(pageTitle)

        if (urlMap.vf == null || urlMap.vostfr == null) {
            val discovered = discoverVersions(cleanedTitle, urlMap)
            urlMap.vf = discovered.vf
            urlMap.vostfr = discovered.vostfr
        }

        anime.title = cleanedTitle
        anime.description = document.selectFirst("div.description")?.text() ?: document.selectFirst(".description")?.text()
        anime.thumbnail_url = document.selectFirst("img.film-poster-img")?.attr("abs:src") ?: anime.thumbnail_url
        anime.genre = document.select("div.elements .item:contains(Genre) a").joinToString { it.text() }
        anime.status = if (document.select(".item:contains(Status)").text().contains("En cours", true)) SAnime.ONGOING else SAnime.COMPLETED
        anime.url = json.encodeToString(urlMap)

        // TMDB Metadata
        val tmdbMetadata = fetchTmdbMetadata(cleanedTitle)
        tmdbMetadata?.summary?.let { anime.description = it }

        return anime
    }

    private fun discoverVersions(cleanedTitle: String, currentMap: AnimeUrl): AnimeUrl {
        val newMap = currentMap.copy()
        try {
            val searchResponse = client.newCall(GET("$baseUrl/index.php?do=search&subaction=search&story=$cleanedTitle", headers)).execute()
            val searchDoc = searchResponse.asJsoup()
            searchDoc.select("div.flw-item h3.film-name a").forEach { link ->
                val foundUrl = link.attr("abs:href").substringAfter(baseUrl)
                val foundTitle = link.text()
                if (cleanTitle(foundTitle).equals(cleanedTitle, true)) {
                    if (foundTitle.contains("VOSTFR", true) && newMap.vostfr == null) newMap.vostfr = foundUrl
                    if (foundTitle.contains("VF", true) && newMap.vf == null) newMap.vf = foundUrl
                }
            }
        } catch (_: Exception) {}
        return newMap
    }

    // ============================== Episodes ==============================
    private fun getNewsId(url: String): String = NEWS_ID_REGEX.find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore("-")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        var urlMap = try {
            json.decodeFromString<AnimeUrl>(anime.url)
        } catch (e: Exception) {
            if (anime.title.isNotBlank() && anime.title.contains("VF", true)) {
                AnimeUrl(vf = anime.url)
            } else {
                AnimeUrl(vostfr = anime.url)
            }
        }

        if (urlMap.vf == null || urlMap.vostfr == null) {
            urlMap = discoverVersions(cleanTitle(anime.title), urlMap)
        }

        val episodesMap = mutableMapOf<String, MutableMap<String, String>>()
        val tmdbMetadata = fetchTmdbMetadata(anime.title)

        val fetch = { path: String?, lang: String ->
            if (path != null) {
                val newsId = getNewsId(path)
                val ajaxUrl = "$baseUrl/engine/ajax/full-story.php?newsId=$newsId&d=${System.currentTimeMillis()}"
                val response = client.newCall(GET(ajaxUrl, headers.newBuilder().add("Referer", baseUrl + path).build())).execute()
                val body = response.body.string()
                val html = if (body.trim().startsWith("{")) {
                    try {
                        json.decodeFromString<FullStoryResponse>(body).html
                    } catch (_: Exception) {
                        body
                    }
                } else {
                    body
                }

                Jsoup.parse(html).select("a.ep-item").forEach { element ->
                    val epNum = (element.attr("data-number").takeIf { it.isNotEmpty() } ?: Regex("""\d+""").findAll(element.text()).lastOrNull()?.value ?: "1").trimStart('0').ifEmpty { "0" }
                    val epUrl = element.attr("abs:href")
                    episodesMap.getOrPut(epNum) { mutableMapOf() }[lang] = epUrl
                }
            }
        }

        fetch(urlMap.vostfr, "VOSTFR")
        fetch(urlMap.vf, "VF")

        val sNumRegex = Regex("""(?i)(?:Saison|Season)\s*(\d+)""")
        val sNumMatch = sNumRegex.find(anime.title)
        val sNum = sNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val sPrefix = if (sNumMatch != null) "[S$sNum] " else ""

        return episodesMap.map { (num, langUrls) ->
            SEpisode.create().apply {
                val numInt = num.toIntOrNull() ?: 1
                name = "${sPrefix}Episode $num"
                episode_number = num.toFloatOrNull() ?: 0f
                url = buildJsonObject { langUrls.forEach { (l, u) -> put(l.lowercase(), u) } }.toString()
                scanlator = langUrls.keys.joinToString(" / ") { it.uppercase() }

                // TMDB Metadata
                val epMeta = tmdbMetadata?.episodeSummaries?.get(numInt)
                preview_url = epMeta?.second
                summary = epMeta?.third
            }
        }.sortedByDescending { it.episode_number }
    }

    // = :::::::::::::::::::::::::: Video Links :::::::::::::::::::::::::: =
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val urlMap = try {
            json.decodeFromString<Map<String, String>>(episode.url)
        } catch (e: Exception) {
            return emptyList()
        }

        val hosters = mutableListOf<Hoster>()
        urlMap.forEach { (lang, path) ->
            val langTag = lang.uppercase()
            val url = if (path.startsWith("http")) path else baseUrl + path
            val response = client.newCall(GET(url, headers)).execute()
            val document = response.asJsoup()

            val findHosters = { doc: org.jsoup.nodes.Document ->
                doc.select("div.server-item").forEach { element ->
                    val embedUrl = element.attr("data-embed")
                    val serverName = element.text().trim()
                    if (embedUrl.isNotBlank()) {
                        hosters.add(Hoster(hosterName = "($langTag) $serverName", internalData = "$embedUrl|$langTag"))
                    }
                }
                doc.select("[id^=content_player_]").forEach { element ->
                    val playerUrl = element.text().trim()
                    if (playerUrl.startsWith("http")) {
                        val serverName = getServerName(playerUrl)
                        hosters.add(Hoster(hosterName = "($langTag) $serverName", internalData = "$playerUrl|$langTag"))
                    }
                }
            }

            findHosters(document)

            // Try AJAX
            try {
                val newsId = getNewsId(path)
                val ajaxUrl = "$baseUrl/engine/ajax/full-story.php?newsId=$newsId&d=${System.currentTimeMillis()}"
                val ajaxBody = client.newCall(GET(ajaxUrl, headers.newBuilder().add("Referer", url).build())).execute().body.string()
                val html = if (ajaxBody.trim().startsWith("{")) json.decodeFromString<FullStoryResponse>(ajaxBody).html else ajaxBody
                findHosters(Jsoup.parse(html))
            } catch (_: Exception) {}
        }
        return hosters.distinctBy { it.internalData }
    }

    private fun getServerName(url: String): String = when {
        url.contains("sibnet.ru") -> "Sibnet"
        url.contains("vidmoly") -> "Vidmoly"
        url.contains("voe.sx") -> "Voe"
        url.contains("sendvid.com") -> "Sendvid"
        url.contains("dood") || url.contains("d0000d") -> "Doodstream"
        url.contains("ok.ru") -> "Okru"
        url.contains("filemoon") || url.contains("fmoon") -> "Filemoon"
        else -> "Player"
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|")
        val embedUrl = data[0]
        val langTag = data[1]
        val server = hoster.hosterName.substringAfter(") ")
        val prefix = "($langTag) $server - "

        val absoluteUrl = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl

        val videos = try {
            when {
                absoluteUrl.contains("sibnet.ru") -> sibnetExtractor.videosFromUrl(absoluteUrl, prefix)
                absoluteUrl.contains("vidmoly") -> vidmolyExtractor.videosFromUrl(absoluteUrl, prefix)
                absoluteUrl.contains("voe.sx") -> voeExtractor.videosFromUrl(absoluteUrl, prefix)
                absoluteUrl.contains("sendvid.com") -> sendvidExtractor.videosFromUrl(absoluteUrl, prefix)
                absoluteUrl.contains("dood") || absoluteUrl.contains("d0000d") -> doodExtractor.videosFromUrl(absoluteUrl, "${server}Doodstream - ")
                absoluteUrl.contains("ok.ru") -> okruExtractor.videosFromUrl(absoluteUrl, "${server}Okru - ")
                absoluteUrl.contains("filemoon") || absoluteUrl.contains("fmoon") -> filemoonExtractor.videosFromUrl(absoluteUrl, "${server}Filemoon - ")
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }

        return videos.map {
            Video(videoUrl = it.videoUrl, videoTitle = cleanQuality(it.videoTitle), headers = it.headers, subtitleTracks = it.subtitleTracks, audioTracks = it.audioTracks)
        }.sortVideos()
    }

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(CLEAN_QUALITY_REGEX_1, "").replace(CLEAN_QUALITY_REGEX_2, "").replace(" - - ", " - ").trim().removeSuffix("-").trim()
        for (server in SERVERS) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server", RegexOption.IGNORE_CASE), server)
        }
        return cleaned.replace(WHITESPACE_REGEX, " ").replace(" - - ", " - ").trim()
    }

    override fun List<Video>.sortVideos(): List<Video> = this.sortedWith(
        compareBy {
            QUALITY_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        },
    ).reversed()

    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
}
