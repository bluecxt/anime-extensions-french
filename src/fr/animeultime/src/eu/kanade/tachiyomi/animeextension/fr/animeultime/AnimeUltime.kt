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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

class AnimeUltime :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anime-Ultime"

    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!
    }

    override val lang = "fr"

    override val supportsLatest = false

    override val client: OkHttpClient = network.client

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val ajaxHeaders by lazy {
        headersBuilder().add("X-Requested-With", "XMLHttpRequest").build()
    }

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
        val body = FormBody.Builder()
            .add("search", query)
            .add("type", "Anime")
            .build()
        return POST("$baseUrl/SeriesResults.html", ajaxHeaders, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchData = json.decodeFromString<List<SearchResponse>>(response.body.string())
        val animes = searchData.map {
            SAnime.create().apply {
                title = it.title
                thumbnail_url = it.img_url.replace("_thindex", "")
                url = it.url.toHttpUrl().encodedPath
            }
        }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector(): String? = null

    @Serializable
    data class SearchResponse(
        val title: String,
        val img_url: String,
        val url: String,
    )

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val h1 = document.selectFirst("h1")
        title = h1?.ownText()?.substringBefore(" vostfr")?.substringBefore(" vf")?.trim() ?: ""
        description = document.selectFirst("div[data-target=synopsis] p")?.text()
        thumbnail_url = document.selectFirst("div.main img")?.attr("abs:src")?.replace("_thindex", "")

        val infoBlock = document.selectFirst("div[data-target=info] ul")
        genre = infoBlock?.selectFirst("li:contains(Genre)")?.text()?.substringAfter(":")?.trim()?.replace(" | ", ", ")
        author = infoBlock?.selectFirst("li:contains(Studio)")?.text()?.substringAfter(":")?.trim()
        status = SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val h1 = document.selectFirst("h1")?.text() ?: ""
        val seasonNum = Regex("""\[Saison\s*(\d+)\]""").find(h1)?.groupValues?.get(1)
        val sPrefix = if (seasonNum != null && !h1.contains("Saison $seasonNum", true) && !h1.contains("Season $seasonNum", true)) {
            "Season $seasonNum "
        } else {
            ""
        }

        val episodesMap = mutableMapOf<String, MutableList<Pair<String, String>>>()
        val epOrder = mutableListOf<String>()

        document.select(episodeListSelector()).forEach { element ->
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
                this.url = infoList.map {
                    buildJsonObject {
                        put("url", it.first)
                        put("fansub", it.second)
                    }
                }.toString()
                this.episode_number = name.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 1f
            }
        }.reversed()
    }

    override fun episodeListSelector(): String = "ul.ficheDownload li.file"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun parseDate(date: String): Long {
        val parts = date.split("/")
        if (parts.size != 3) return 0L
        return Calendar.getInstance().apply {
            set(parts[2].toInt(), parts[1].toInt() - 1, parts[0].toInt())
        }.timeInMillis
    }

    // ============================ Video Links =============================
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> = emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val infoList = try {
            json.decodeFromString<List<kotlinx.serialization.json.JsonObject>>(episode.url)
        } catch (e: Exception) {
            return emptyList()
        }
        val allVideos = mutableListOf<Video>()

        infoList.forEach { item ->
            val url = item["url"]?.jsonPrimitive?.content ?: return@forEach
            val fansub = item["fansub"]?.jsonPrimitive?.content ?: "Unknown"

            val response = client.newCall(GET(url, headers)).execute()
            val document = response.asJsoup()
            val h1 = document.selectFirst("h1")?.text()?.lowercase() ?: ""
            val lang = if (h1.contains(" vf")) "VF" else "VOSTFR"

            val playerElement = document.selectFirst(".AUVideoPlayer")
            if (playerElement != null) {
                val idserie = playerElement.attr("data-serie")
                val idfile = playerElement.attr("data-file").takeIf { it.isNotEmpty() } ?: playerElement.attr("data-focus")

                val body = FormBody.Builder()
                    .add("idfile", idfile)
                    .add("idserie", idserie)
                    .build()

                try {
                    val videoResponse = client.newCall(POST("$baseUrl/VideoPlayer.html", ajaxHeaders, body)).execute()
                    val videoDataJson = videoResponse.body.string()
                    val jsonMap = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(videoDataJson)

                    jsonMap.forEach { (quality, element) ->
                        if (element is kotlinx.serialization.json.JsonObject) {
                            val mp4 = element["mp4"]?.let { it as? kotlinx.serialization.json.JsonObject }
                            if (mp4 != null) {
                                val videoUrl = mp4["url"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                                if (videoUrl != null) {
                                    allVideos.add(Video(videoUrl, "($lang) $fansub $quality", videoUrl))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}
            }

            document.select("iframe").forEach { iframe ->
                val iframeUrl = iframe.attr("abs:src")
                allVideos.addAll(extractVideosFromUrl(iframeUrl, lang, fansub))
            }
        }

        return allVideos.map { video ->
            Video(video.url, cleanQuality(video.quality), video.videoUrl, video.headers)
        }.sort()
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

    override fun List<Video>.sort(): List<Video> = this.sortedWith(
        compareBy(
            {
                Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            },
        ),
    ).reversed()

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s"), "")
            .replace(Regex("\\s*\\(\\d+x\\d+\\)"), "")
            .replace(Regex("(?i)Sendvid:default"), "")
            .replace(Regex("(?i)Sibnet:default"), "")
            .replace(Regex("(?i)Doodstream:default"), "")
            .replace(Regex("(?i)Voe:default"), "")
            .replace(" - - ", " - ")
            .trim()
            .removeSuffix("-")
            .trim()

        val servers = listOf("Sibnet", "Vidmoly", "Doodstream", "Voe")
        for (server in servers) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server(?!:)", RegexOption.IGNORE_CASE), server)
            cleaned = cleaned.replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(Regex("\\s+"), " ").replace(" - - ", " - ").trim()
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
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

    companion object {
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://v5.anime-ultime.net"
    }
}
