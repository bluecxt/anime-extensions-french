package eu.kanade.tachiyomi.animeextension.fr.animeultime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

class AnimeUltime :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anime-Ultime"
    override val baseUrl by lazy { preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!! }
    override val lang = "fr"
    override val supportsLatest = false
    override val client: OkHttpClient = network.client
    private val json = Json { ignoreUnknownKeys = true }
    private val preferences: SharedPreferences by lazy { Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000) }

    private val userAgent = "Mozilla/5.0 (X11; Linux x86_64; rv:149.0) Gecko/20100101 Firefox/149.0"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", userAgent)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)
    override fun popularAnimeSelector(): String = "div.slides li"
    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        title = element.selectFirst(".box-text p")?.text() ?: ""
        thumbnail_url = element.selectFirst(".imgCtn img")?.attr("abs:src")?.replace("_thindex", "")
    }
    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = FormBody.Builder().add("search", query).build()
        val ajaxHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .build()
        return POST("$baseUrl/MenuSearch.html", ajaxHeaders, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseBody = response.body.string()
        if (responseBody.isBlank() || responseBody == "[]") return AnimesPage(emptyList(), false)
        val searchData = json.decodeFromString<List<SearchResponse>>(responseBody)
        val animes = searchData.map {
            SAnime.create().apply {
                title = it.title
                thumbnail_url = it.imgUrl.replace("_thindex", "")
                url = it.url.toHttpUrl().encodedPath
            }
        }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector(): String? = null

    @Serializable
    data class SearchResponse(val title: String, val imgUrl: String, val url: String)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val h1 = document.selectFirst("h1")
        title = h1?.ownText()?.substringBefore(" vostfr")?.substringBefore(" vf")?.trim() ?: ""
        description = document.selectFirst("div[data-target=synopsis] p")?.text()
        thumbnail_url = document.selectFirst("div.main img")?.attr("abs:src")?.replace("_thindex", "")
        genre = document.selectFirst("div[data-target=info] ul")?.selectFirst("li:contains(Genre)")?.text()?.substringAfter(":")?.trim()?.replace(" | ", ", ")
        author = document.selectFirst("div[data-target=info] ul")?.selectFirst("li:contains(Studio)")?.text()?.substringAfter(":")?.trim()
        status = SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val animeTitle = document.selectFirst("h1")?.text() ?: ""
        val seasonNum = seasonRegex.find(animeTitle)?.groupValues?.get(1)
        val sPrefix = if (seasonNum != null && !animeTitle.contains("Saison $seasonNum", true) && !animeTitle.contains("Season $seasonNum", true)) "Season $seasonNum " else ""

        val episodesMap = mutableMapOf<String, MutableList<Pair<String, String>>>()
        val epOrder = mutableListOf<String>()

        document.select("ul.ficheDownload li.file").forEach { element ->
            val rawNum = element.selectFirst(".number label")?.text() ?: ""
            val fansub = element.selectFirst(".fansub label")?.text()?.trim()?.ifBlank { "Unknown" } ?: "Unknown"
            val link = element.selectFirst("a")?.attr("abs:href") ?: return@forEach

            val cleanNum = rawNum.replace("Épisode", "Episode", true)
                .replace("OAV", "Episode OAV", true)
                .replace("ONA", "Episode ONA", true)
                .replace("Special", "Episode Special", true)
                .replace("Spécial", "Episode Special", true)

            val epName = sPrefix + cleanNum
            if (!episodesMap.containsKey(epName)) {
                episodesMap[epName] = mutableListOf()
                epOrder.add(epName)
            }
            episodesMap[epName]!!.add(link to fansub)
        }

        return epOrder.map { name ->
            val infoList = episodesMap[name]!!
            SEpisode.create().apply {
                this.name = name
                // Store multiple links in the URL as JSON
                this.url = json.encodeToString(infoList.map { mapOf("url" to it.first, "fansub" to it.second) })
                this.episode_number = digitRegex.find(name)?.value?.toFloatOrNull() ?: 1f
            }
        }.reversed()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = emptyList()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> = this.sortedWith(compareBy {
        qualityRegex.find(it.quality)?.groupValues?.get(
            1
        )?.toIntOrNull() ?: 0
    }).reversed()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val infoList = try {
            json.decodeFromString<List<Map<String, String>>>(episode.url)
        } catch (e: Exception) {
            return emptyList()
        }

        val allVideos = mutableListOf<Video>()
        infoList.forEach { item ->
            val url = item["url"] ?: return@forEach
            val fansub = item["fansub"] ?: "Unknown"

            val pageHeaders = headersBuilder().add("Referer", baseUrl).build()
            val response = client.newCall(GET(url, pageHeaders)).execute()
            val document = response.asJsoup()
            val h1 = document.selectFirst("h1")?.text() ?: ""
            val lang = if (h1.lowercase().contains(" vf")) "VF" else "VOSTFR"

            val playerElement = document.selectFirst(".AUVideoPlayer")
            if (playerElement != null) {
                val targetNum = digitRegex.find(episode.name)?.value ?: ""

                // On cherche l'ID réel de l'épisode via Regex dans la page
                val idfile = document.select("a[data-idfile]").firstOrNull {
                    val text = it.text().lowercase()
                    Regex("""\b(0?$targetNum)\b""").containsMatchIn(text)
                }?.attr("data-idfile")
                    ?: dataIdFileRegex.findAll(document.html()).map { it.groupValues[1] }.firstOrNull { it != playerElement.attr("data-focus") }
                    ?: playerElement.attr("data-file").takeIf { it.isNotEmpty() }
                    ?: playerElement.attr("data-focus")

                if (idfile.isNotEmpty()) {
                    val apiHeaders = headersBuilder()
                        .add("Referer", url)
                        .add("X-Requested-With", "XMLHttpRequest")
                        .build()
                    val body = FormBody.Builder().add("idfile", idfile).build()
                    try {
                        val videoResponse = client.newCall(POST("$baseUrl/VideoPlayer.html", apiHeaders, body)).execute()
                        val videoData = JSONObject(videoResponse.body.string())
                        val keys = videoData.keys()
                        while (keys.hasNext()) {
                            val quality = keys.next()
                            val element = videoData.optJSONObject(quality) ?: continue
                            if (element.has("mp4")) {
                                val mp4Url = element.getJSONObject("mp4").getString("url")
                                if (mp4Url.isNotBlank()) {
                                    allVideos.add(Video(mp4Url, "($lang) $fansub $quality", mp4Url))
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }

            document.select("iframe").forEach { iframe ->
                val iframeUrl = iframe.attr("abs:src")
                allVideos.addAll(extractVideosFromUrl(iframeUrl, lang, fansub))
            }
        }
        return allVideos.map { Video(it.url, cleanQuality(it.quality), it.videoUrl, it.headers) }.sort()
    }

    private fun extractVideosFromUrl(url: String, lang: String, fansub: String): List<Video> {
        val server = when {
            url.contains("sibnet.ru") -> "Sibnet"
            url.contains("vidmoly") -> "Vidmoly"
            url.contains("dood") || url.contains("d0000d") -> "Doodstream"
            url.contains("voe.sx") -> "Voe"
            else -> "Serveur"
        }
        val prefix = "($lang) $fansub $server - "
        return when (server) {
            "Sibnet" -> sibnetExtractor.videosFromUrl(url, prefix)
            "Vidmoly" -> vidmolyExtractor.videosFromUrl(url, prefix)
            "Doodstream" -> doodExtractor.videosFromUrl(url, prefix)
            "Voe" -> voeExtractor.videosFromUrl(url, prefix)
            else -> emptyList()
        }
    }

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(cleanQualityRegex, "").replace(cleanQualityRegex2, "").replace(cleanQualityRegex3, "").replace(" - - ", " - ").trim().removeSuffix("-").trim()
        listOf("Sibnet", "Vidmoly", "Doodstream", "Voe").forEach { server ->
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server(?!:)", RegexOption.IGNORE_CASE), server).replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(whitespaceRegex, " ").replace(" - - ", " - ").trim()
    }

    private val seasonRegex = Regex("""\[Saison\s*(\d+)\]""")
    private val digitRegex = Regex("""\d+""")
    private val qualityRegex = Regex("""(\d+)p""")
    private val dataIdFileRegex = Regex("""data-idfile=["'](\d+)["']""")
    private val cleanQualityRegex = Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s")
    private val cleanQualityRegex2 = Regex("\\s*\\(\\d+x\\d+\\)")
    private val cleanQualityRegex3 = Regex("(?i)Sendvid:default|Sibnet:default|Doodstream:default|Voe:default")
    private val whitespaceRegex = Regex("\\s+")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = "URL de base"
            setDefaultValue(PREF_URL_DEFAULT)
            summary = baseUrl
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_URL_KEY, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)
    }

    private fun parseDate(date: String): Long = try {
        val parts = date.split("/")
        Calendar.getInstance().apply { set(parts[2].toInt(), parts[1].toInt() - 1, parts[0].toInt()) }.timeInMillis
    } catch (e: Exception) {
        0L
    }

    companion object {
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://v5.anime-ultime.net"
        const val PREFIX_SEARCH = "id:"
    }
}
