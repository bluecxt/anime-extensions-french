package eu.kanade.tachiyomi.animeextension.fr.animesultra

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
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimesUltra :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimesUltra"
    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!
    }
    override val lang = "fr"
    override val supportsLatest = true
    override val client = network.client

    private val json: Json by injectLazy()
    private val preferences: SharedPreferences by lazy { Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000) }

    companion object {
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://ww.animesultra.org"
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
            title = "URL de base"
            setDefaultValue(PREF_URL_DEFAULT)
            summary = baseUrl
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_URL_KEY, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime-vostfr/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("div.flw-item").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("h3.film-name a")!!
                title = link.text()
                setUrlWithoutDomain(link.attr("abs:href"))
                thumbnail_url = element.selectFirst("img.film-poster-img")?.attr("abs:data-src") ?: element.selectFirst("img.film-poster-img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst(".pagi-nav a:contains(Suivant)") != null
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

    private fun cleanTitle(title: String): String = title.replace(cleanRegex, "").replace(Regex("\\s+"), " ").trim()

    private val cleanRegex = Regex("(?i)\\s*(\\((?:VF|VOSTFR|AU|DLL)\\)|\\b(?:VF|VOSTFR|AU|DLL)\\b)")

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/index.php?do=search&subaction=search&story=$query", headers)
    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

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

        return SAnime.create().apply {
            title = cleanedTitle
            description = document.selectFirst("div.description")?.text() ?: document.selectFirst(".description")?.text()
            thumbnail_url = document.selectFirst("img.film-poster-img")?.attr("abs:src") ?: anime.thumbnail_url
            genre = document.select("div.elements .item:contains(Genre) a").joinToString { it.text() }
            status = if (document.select(".item:contains(Status)").text().contains("En cours", true)) SAnime.ONGOING else SAnime.COMPLETED
            url = json.encodeToString(urlMap)
            initialized = true
        }
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
        } catch (e: Exception) {}
        return newMap
    }

    // ============================== Episodes ==============================
    private fun getNewsId(url: String): String = Regex("""/(\d+)-""").find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore("-")

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

        val episodes = mutableMapOf<String, MutableMap<String, String>>()

        val fetch = { path: String?, lang: String ->
            if (path != null) {
                val newsId = getNewsId(path)
                val ajaxUrl = "$baseUrl/engine/ajax/full-story.php?newsId=$newsId&d=${System.currentTimeMillis()}"
                val response = client.newCall(GET(ajaxUrl, headers.newBuilder().add("Referer", baseUrl + path).build())).execute()
                val body = response.body.string()
                val html = if (body.trim().startsWith("{")) {
                    try {
                        json.decodeFromString<FullStoryResponse>(body).html
                    } catch (e: Exception) {
                        body
                    }
                } else {
                    body
                }

                Jsoup.parse(html).select("a.ep-item").forEach { element ->
                    val epNum = (element.attr("data-number").takeIf { it.isNotEmpty() } ?: Regex("""\d+""").findAll(element.text()).lastOrNull()?.value ?: "1").trimStart('0').ifEmpty { "0" }
                    val epUrl = element.attr("abs:href")
                    episodes.getOrPut(epNum) { mutableMapOf() }[lang] = epUrl
                }
            }
        }

        fetch(urlMap.vostfr, "VOSTFR")
        fetch(urlMap.vf, "VF")

        return episodes.map { (num, langUrls) ->
            SEpisode.create().apply {
                name = "Episode $num"
                episode_number = num.toFloatOrNull() ?: 0f
                url = buildJsonObject { langUrls.forEach { (l, u) -> put(l.lowercase(), u) } }.toString()
                scanlator = langUrls.keys.joinToString(" / ") { it.uppercase() }
            }
        }.sortedByDescending { it.episode_number }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // = :::::::::::::::::::::::::: Video Links :::::::::::::::::::::::::: =
    override fun List<Video>.sort(): List<Video> = this.sortedWith(
        compareBy(
            {
                Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            },
        ),
    ).reversed()

    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = mutableListOf<Video>()
        val urlMap = try {
            json.decodeFromString<Map<String, String>>(episode.url)
        } catch (e: Exception) {
            return emptyList()
        }

        urlMap.forEach { (lang, path) ->
            val langTag = lang.uppercase()
            val url = if (path.startsWith("http")) path else baseUrl + path
            val response = client.newCall(GET(url, headers)).execute()
            val document = response.asJsoup()

            val extractFromDoc = { doc: org.jsoup.nodes.Document ->
                // 1. Try server items
                doc.select("div.server-item").forEach { element ->
                    val embedUrl = element.attr("data-embed")
                    val serverName = element.text().trim()
                    if (embedUrl.isNotBlank()) {
                        videoList.addAll(
                            extractVideosFromEmbed(embedUrl, serverName).map { video ->
                                Video(video.url, "($langTag) ${cleanQuality(video.quality)}", video.videoUrl, video.headers)
                            },
                        )
                    }
                }

                // 2. Try content_player divs
                doc.select("[id^=content_player_]").forEach { element ->
                    val playerUrl = element.text().trim()
                    if (playerUrl.startsWith("http")) {
                        val serverName = when {
                            playerUrl.contains("sibnet.ru") -> "Sibnet"
                            playerUrl.contains("vidmoly") -> "Vidmoly"
                            playerUrl.contains("voe.sx") -> "Voe"
                            playerUrl.contains("sendvid.com") -> "Sendvid"
                            playerUrl.contains("dood") || playerUrl.contains("d0000d") -> "Doodstream"
                            playerUrl.contains("ok.ru") -> "Okru"
                            playerUrl.contains("filemoon") || playerUrl.contains("fmoon") -> "Filemoon"
                            else -> "Player"
                        }
                        videoList.addAll(
                            extractVideosFromEmbed(playerUrl, serverName).map { video ->
                                Video(video.url, "($langTag) ${cleanQuality(video.quality)}", video.videoUrl, video.headers)
                            },
                        )
                    }
                }
            }

            extractFromDoc(document)

            // 3. Try AJAX full-story if still empty or anyway
            try {
                val newsId = getNewsId(path)
                val ajaxUrl = "$baseUrl/engine/ajax/full-story.php?newsId=$newsId&d=${System.currentTimeMillis()}"
                val ajaxResponse = client.newCall(GET(ajaxUrl, headers.newBuilder().add("Referer", url).build())).execute()
                val body = ajaxResponse.body.string()
                val html = if (body.trim().startsWith("{")) {
                    json.decodeFromString<FullStoryResponse>(body).html
                } else {
                    body
                }
                extractFromDoc(Jsoup.parse(html))
            } catch (e: Exception) {}
        }

        return videoList.distinctBy { it.url }.sort()
    }

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(Regex("(?i)\\(\\s*Player\\s*\\)\\s*|\\(\\s*None\\s*\\)\\s*|\\s*\\(\\d+x\\d+\\)|\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s"), "")
            .replace(Regex("(?i)Sendvid:default|Sibnet:default|Voe:default|Vidmoly:default"), "")
            .replace(" - - ", " - ")
            .trim()
            .removeSuffix("-")
            .trim()

        val servers = listOf("Vidmoly", "Sibnet", "Sendvid", "Voe", "Doodstream", "Okru", "Filemoon", "Player")
        for (server in servers) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server", RegexOption.IGNORE_CASE), server)
        }
        return cleaned.replace(Regex("\\s+"), " ").replace(" - - ", " - ").trim()
    }

    private fun extractVideosFromEmbed(url: String, name: String): List<Video> {
        val absoluteUrl = if (url.startsWith("//")) "https:$url" else url
        val prefix = "$name - "
        return try {
            when {
                absoluteUrl.contains("sibnet.ru") -> sibnetExtractor.videosFromUrl(absoluteUrl, prefix)
                absoluteUrl.contains("vidmoly") -> vidmolyExtractor.videosFromUrl(absoluteUrl, prefix)
                absoluteUrl.contains("voe.sx") -> voeExtractor.videosFromUrl(absoluteUrl, prefix)
                absoluteUrl.contains("sendvid.com") -> sendvidExtractor.videosFromUrl(absoluteUrl, prefix)
                absoluteUrl.contains("dood") || absoluteUrl.contains("d0000d") -> doodExtractor.videosFromUrl(absoluteUrl, "${name}Doodstream - ")
                absoluteUrl.contains("ok.ru") -> okruExtractor.videosFromUrl(absoluteUrl, "${name}Okru - ")
                absoluteUrl.contains("filemoon") || absoluteUrl.contains("fmoon") -> filemoonExtractor.videosFromUrl(absoluteUrl, "${name}Filemoon - ")
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()
}
