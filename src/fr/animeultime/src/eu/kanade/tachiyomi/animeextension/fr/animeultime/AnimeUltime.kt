package eu.kanade.tachiyomi.animeextension.fr.animeultime

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.Source
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
import uy.kohesive.injekt.injectLazy

class AnimeUltime : Source() {

    override val name = "Anime-Ultime"
    override val baseUrl by lazy { preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!! }
    override val lang = "fr"
    override val supportsLatest = false
    override val client: OkHttpClient = network.client
    override val json: Json by injectLazy()

    private val userAgent = "Mozilla/5.0 (X11; Linux x86_64; rv:149.0) Gecko/20100101 Firefox/149.0"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", userAgent)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET(baseUrl, headers)).execute()
        val document = response.asJsoup()
        val items = document.select("div.slides li").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a")!!
                setUrlWithoutDomain(link.attr("abs:href"))
                title = element.selectFirst(".box-text p")?.text() ?: ""
                thumbnail_url = element.selectFirst(".imgCtn img")?.attr("abs:src")?.replace("_thindex", "")
            }
        }
        return AnimesPage(items, false)
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val body = FormBody.Builder().add("search", query).build()
        val ajaxHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .build()
        val response = client.newCall(POST("$baseUrl/MenuSearch.html", ajaxHeaders, body)).execute()
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

    @Serializable
    data class SearchResponse(val title: String, val imgUrl: String, val url: String)

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET(baseUrl + anime.url, headers)).execute()
        val document = response.asJsoup()

        val h1 = document.selectFirst("h1")
        val mainTitle = h1?.ownText()?.substringBefore(" vostfr")?.substringBefore(" vf")?.trim() ?: ""
        val synopsis = document.selectFirst("div[data-target=synopsis] p")?.text()
        val releaseDate = document.selectFirst("div[data-target=info] ul")?.selectFirst("li:contains(Année de production)")?.text()?.substringAfter(":")?.trim()

        anime.title = mainTitle
        anime.description = buildString {
            if (releaseDate != null && releaseDate.isNotBlank()) append("Date de sortie : $releaseDate\n\n")
            append(synopsis ?: "")
        }
        anime.thumbnail_url = document.selectFirst("div.main img")?.attr("abs:src")?.replace("_thindex", "")
        anime.genre = document.selectFirst("div[data-target=info] ul")?.selectFirst("li:contains(Genre)")?.text()?.substringAfter(":")?.trim()?.replace(" | ", ", ")
        anime.artist = document.selectFirst("div[data-target=info] ul")?.selectFirst("li:contains(Studio)")?.text()?.substringAfter(":")?.trim()
        anime.status = SAnime.UNKNOWN

        // TMDB Metadata
        val tmdbMetadata = fetchTmdbMetadata(mainTitle)
        tmdbMetadata?.summary?.let { anime.description = it }

        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET(baseUrl + anime.url, headers)).execute()
        val document = response.asJsoup()
        val animeTitle = document.selectFirst("h1")?.text() ?: ""
        val seasonNumStr = seasonRegex.find(animeTitle)?.groupValues?.get(1) ?: "1"
        val seasonNum = seasonNumStr.toIntOrNull() ?: 1

        val tmdbMetadata = fetchTmdbMetadata(animeTitle.substringBefore(" [Saison"), seasonNum)

        val episodesMap = mutableMapOf<String, MutableList<Pair<String, String>>>()
        val epOrder = mutableListOf<String>()

        document.select("ul.ficheDownload li.file").forEach { element ->
            val rawNum = element.selectFirst(".number label")?.text() ?: ""
            val fansub = element.selectFirst(".fansub label")?.text()?.trim()?.ifBlank { "Unknown" } ?: "Unknown"
            val link = element.selectFirst("a")?.attr("abs:href") ?: return@forEach

            val sPrefix = if (seasonNum > 1) "[S$seasonNum] " else ""
            val sType = when {
                rawNum.contains("OAV", true) || rawNum.contains("OVA", true) -> "[OVA] "
                rawNum.contains("ONA", true) -> "[ONA] "
                rawNum.contains("Special", true) || rawNum.contains("Spécial", true) -> "[Special] "
                rawNum.contains("Film", true) || rawNum.contains("Movie", true) -> "[Movie] "
                else -> sPrefix
            }

            val epNumStr = digitRegex.find(rawNum)?.value ?: "1"
            val epName = "${sType}Episode $epNumStr"
            if (!episodesMap.containsKey(epName)) {
                episodesMap[epName] = mutableListOf()
                epOrder.add(epName)
            }
            episodesMap[epName]!!.add(link to fansub)
        }

        return epOrder.map { name ->
            val infoList = episodesMap[name]!!
            val epNum = digitRegex.find(name)?.value?.toIntOrNull() ?: 1

            SEpisode.create().apply {
                this.name = name
                this.url = json.encodeToString(infoList.map { mapOf("url" to it.first, "fansub" to it.second) })
                this.episode_number = epNum.toFloat()
                this.scanlator = "Season $seasonNum"

                // TMDB Metadata
                val epMeta = tmdbMetadata?.episodeSummaries?.get(epNum)
                this.preview_url = epMeta?.second
                this.summary = epMeta?.third
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val infoList = json.decodeFromString<List<Map<String, String>>>(episode.url)
        val hosters = mutableListOf<Hoster>()

        infoList.forEach { item ->
            val url = item["url"] ?: return@forEach
            val fansub = item["fansub"] ?: "Unknown"

            val response = client.newCall(GET(url, headers)).execute()
            val document = response.asJsoup()
            val h1 = document.selectFirst("h1")?.text() ?: ""
            val lang = if (h1.lowercase().contains(" vf")) "VF" else "VOSTFR"

            // 1. Internal Player
            val playerElement = document.selectFirst(".AUVideoPlayer")
            if (playerElement != null) {
                val targetNum = digitRegex.find(episode.name)?.value ?: ""
                val idfile = document.select("a[data-idfile]").firstOrNull {
                    Regex("""\b(0?$targetNum)\b""").containsMatchIn(it.text().lowercase())
                }?.attr("data-idfile")
                    ?: dataIdFileRegex.findAll(document.html()).map { it.groupValues[1] }.firstOrNull { it != playerElement.attr("data-focus") }
                    ?: playerElement.attr("data-focus")

                if (!idfile.isNullOrEmpty()) {
                    hosters.add(Hoster(hosterName = "($lang) $fansub (Internal)", internalData = "internal|$idfile|$lang|$fansub|$url"))
                }
            }

            // 2. External Iframes
            document.select("iframe").forEach { iframe ->
                val iframeUrl = iframe.attr("abs:src")
                val server = when {
                    iframeUrl.contains("sibnet.ru") -> "Sibnet"
                    iframeUrl.contains("vidmoly") -> "Vidmoly"
                    iframeUrl.contains("dood") || iframeUrl.contains("d0000d") -> "Doodstream"
                    iframeUrl.contains("voe.sx") -> "Voe"
                    else -> "Serveur"
                }
                hosters.add(Hoster(hosterName = "($lang) $fansub ($server)", internalData = "external|$iframeUrl|$lang|$fansub|$server"))
            }
        }
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|")
        val type = data[0]
        val videoList = mutableListOf<Video>()

        if (type == "internal") {
            val idfile = data[1]
            val lang = data[2]
            val fansub = data[3]
            val referer = data[4]
            val apiHeaders = headersBuilder().add("Referer", referer).add("X-Requested-With", "XMLHttpRequest").build()
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
                        videoList.add(Video(videoUrl = mp4Url, videoTitle = "($lang) $fansub - $quality", headers = headers))
                    }
                }
            } catch (_: Exception) {}
        } else {
            val iframeUrl = data[1]
            val lang = data[2]
            val fansub = data[3]
            val server = data[4]
            val prefix = "($lang) $fansub $server - "
            val videos = when (server) {
                "Sibnet" -> sibnetExtractor.videosFromUrl(iframeUrl, prefix)
                "Vidmoly" -> vidmolyExtractor.videosFromUrl(iframeUrl, prefix)
                "Doodstream" -> doodExtractor.videosFromUrl(iframeUrl, prefix)
                "Voe" -> voeExtractor.videosFromUrl(iframeUrl, prefix)
                else -> emptyList()
            }
            videoList.addAll(videos)
        }

        return videoList.map {
            Video(videoUrl = it.videoUrl, videoTitle = cleanQuality(it.videoTitle), headers = it.headers, subtitleTracks = it.subtitleTracks, audioTracks = it.audioTracks)
        }.sortVideos()
    }

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(cleanQualityRegex, "").replace(cleanQualityRegex2, "").replace(cleanQualityRegex3, "").replace(" - - ", " - ").trim().removeSuffix("-").trim()
        listOf("Sibnet", "Vidmoly", "Doodstream", "Voe").forEach { server ->
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server(?!:)", RegexOption.IGNORE_CASE), server).replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(whitespaceRegex, " ").replace(" - - ", " - ").trim()
    }

    override fun List<Video>.sortVideos(): List<Video> = this.sortedWith(
        compareBy { qualityRegex.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
    ).reversed()

    private val seasonRegex = Regex("""\[Saison\s*(\d+)]""")
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

    companion object {
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://v5.anime-ultime.net"
    }
}
