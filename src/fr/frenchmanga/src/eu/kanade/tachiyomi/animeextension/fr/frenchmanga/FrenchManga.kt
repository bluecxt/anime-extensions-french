package eu.kanade.tachiyomi.animeextension.fr.frenchmanga

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
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class FrenchManga :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "French-Manga"

    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!.removeSuffix("/")
    }

    override val lang = "fr"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val json: Json by injectLazy()

    @Serializable
    data class AnimeUrl(
        val ids: List<String>,
    )

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Cookie", "skin_name=MGV1") // Force stable interface

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/manga-streaming-1/coups-de-cur/page/$page/?m_orderby=views", headers)

    override fun popularAnimeSelector(): String = "div.short"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a.short-poster")!!
        val newsId = link.attr("href").substringAfter("newsid=", "").substringBefore("&")
            .takeIf { it.isNotEmpty() } ?: link.attr("abs:href").substringAfterLast("/").substringBefore("-")

        url = newsId
        title = element.selectFirst(".short-title")!!.text()
        thumbnail_url = link.selectFirst("img")?.attr("abs:src")

        val version = element.selectFirst(".film-version")?.text()?.trim()
        if (!version.isNullOrEmpty()) {
            title += " ($version)"
        }
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map(::popularAnimeFromElement)
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(unifyAnimes(animes), hasNextPage)
    }

    private fun unifyAnimes(animes: List<SAnime>): List<SAnime> {
        val cleanRegex = Regex("(?i)\\s*\\((VF|VOSTFR|VF\\+VOSTFR|VOST|UNCUT)\\)")
        return animes.distinctBy { it.url }.groupBy {
            it.title.replace(cleanRegex, "").trim()
        }.map { (title, versions) ->
            val anime = versions.first()
            anime.title = title

            val allVersions = versions.flatMap { v ->
                val vText = cleanRegex.find(v.title)?.groupValues?.get(1) ?: ""
                vText.split("+", "/")
            }.map { it.trim().uppercase() }.distinct().filter { it.isNotEmpty() }

            if (allVersions.isNotEmpty()) {
                anime.title += " (${allVersions.joinToString("+")})"
            }

            val ids = versions.map { it.url }.distinct()
            anime.url = json.encodeToString(AnimeUrl(ids))
            anime
        }
    }

    override fun popularAnimeNextPageSelector(): String = ".pagi-nav a:contains(Suivant)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/manga-streaming/page/$page/", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun latestUpdatesNextPageSelector(): String = ".pagi-nav a:contains(Suivant)"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return GET("$baseUrl/index.php?newsid=$id", headers)
        }
        val formBody = okhttp3.FormBody.Builder()
            .add("query", query)
            .add("page", page.toString())
            .build()

        return Request.Builder()
            .url("$baseUrl/engine/ajax/search.php")
            .post(formBody)
            .headers(headers)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = org.jsoup.Jsoup.parse(response.body.string(), baseUrl)
        val animes = document.select("div.search-item").map { element ->
            SAnime.create().apply {
                val onclick = element.attr("onclick")
                val path = onclick.substringAfter("'").substringBefore("'")
                url = path.substringAfterLast("/").substringBefore("-")
                title = element.selectFirst(".search-title")?.text() ?: ""
                thumbnail_url = element.selectFirst(".search-poster img")?.attr("abs:src")
            }
        }
        return AnimesPage(unifyAnimes(animes), false)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val ids = try {
            json.decodeFromString<AnimeUrl>(anime.url).ids
        } catch (e: Exception) {
            listOf(anime.url)
        }
        val response = client.newCall(GET("$baseUrl/index.php?newsid=${ids.first()}", headers)).execute()
        val document = response.asJsoup()

        return SAnime.create().apply {
            title = anime.title
            description = document.selectFirst(".fdesc")?.text() ?: document.select(".full-story").text()
            author = document.select("li").firstOrNull { it.text().contains("Studio", true) }?.text()?.substringAfter(":")?.trim()
                ?: document.selectFirst("li:contains(Director) a")?.text()
            genre = document.select(".genres a, .full-inf li:contains(Genre) a").joinToString { it.text() }
            thumbnail_url = anime.thumbnail_url
            url = anime.url
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val ids = try {
            json.decodeFromString<AnimeUrl>(anime.url).ids
        } catch (e: Exception) {
            listOf(anime.url)
        }
        val episodesMap = mutableMapOf<String, MutableMap<String, JsonObject>>()

        ids.forEach { newsId ->
            val ajaxUrl = "$baseUrl/engine/ajax/manga_episodes_api.php?id=$newsId"
            try {
                val ajaxResponse = client.newCall(GET(ajaxUrl, headers)).execute()
                val jsonResponse = json.parseToJsonElement(ajaxResponse.body.string()).jsonObject

                listOf("vf", "vostfr").forEach { langType ->
                    jsonResponse[langType]?.jsonObject?.forEach { (epNum, hosters) ->
                        val epMap = episodesMap.getOrPut(epNum) { mutableMapOf() }
                        val existingHosters = epMap[langType]?.toMutableMap() ?: mutableMapOf()
                        hosters.jsonObject.forEach { (k, v) -> existingHosters[k] = v }
                        epMap[langType] = JsonObject(existingHosters)
                    }
                }
            } catch (e: Exception) {}
        }

        return episodesMap.map { (epNum, langMap) ->
            SEpisode.create().apply {
                val actualEpNum = epNum.toFloatOrNull() ?: 0f
                episode_number = actualEpNum
                name = "Episode $epNum"
                url = buildJsonObject {
                    put("epNum", epNum)
                    put(
                        "langs",
                        buildJsonObject {
                            langMap.forEach { (lang, hosters) -> put(lang, hosters) }
                        },
                    )
                }.toString()

                scanlator = listOfNotNull(
                    if (langMap.containsKey("vostfr")) "VOSTFR" else null,
                    if (langMap.containsKey("vf")) "VF" else null,
                ).joinToString(", ")
            }
        }.sortedByDescending { it.episode_number }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val epData = json.parseToJsonElement(episode.url).jsonObject
        val langs = epData["langs"]?.jsonObject ?: return emptyList()

        val videos = mutableListOf<Video>()

        val fmExtractor = FrenchMangaExtractor(network.cloudflareClient, baseUrl)
        val doodExtractor = DoodExtractor(client)
        val voeExtractor = VoeExtractor(client, headers)
        val sibnetExtractor = SibnetExtractor(client)
        val vidMolyExtractor = VidMolyExtractor(client, headers)
        val filemoonExtractor = FilemoonExtractor(client)

        langs.forEach { (langType, hosters) ->
            hosters.jsonObject.forEach { (hosterName, hosterUrlElement) ->
                val hosterUrl = hosterUrlElement.toString().trim('"')
                val lang = langType.uppercase()

                val server = when {
                    hosterUrl.contains("vidzy") -> "Vidzy"
                    hosterUrl.contains("luluvid") || hosterUrl.contains("vidnest") || hosterUrl.contains("lulu") -> "Lulu"
                    hosterUrl.contains("dood") -> "Doodstream"
                    hosterUrl.contains("voe") -> "Voe"
                    hosterUrl.contains("sibnet") -> "Sibnet"
                    hosterUrl.contains("vidmoly") -> "Vidmoly"
                    hosterUrl.contains("filemoon") -> "Filemoon"
                    else -> "Serveur"
                }

                val prefix = "($lang) $server - "

                when (server) {
                    "Vidzy", "Lulu" -> videos.addAll(fmExtractor.videosFromUrl(hosterUrl, prefix))
                    "Doodstream" -> videos.addAll(doodExtractor.videosFromUrl(hosterUrl, prefix))
                    "Voe" -> videos.addAll(voeExtractor.videosFromUrl(hosterUrl, prefix))
                    "Sibnet" -> videos.addAll(sibnetExtractor.videosFromUrl(hosterUrl, prefix))
                    "Vidmoly" -> videos.addAll(vidMolyExtractor.videosFromUrl(hosterUrl, prefix))
                    "Filemoon" -> videos.addAll(filemoonExtractor.videosFromUrl(hosterUrl, prefix))
                }
            }
        }

        return videos.map { video ->
            Video(video.url, cleanQuality(video.quality), video.videoUrl, video.headers)
        }.sort()
    }
    private fun cleanQuality(quality: String): String = quality.replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s"), "")
        .replace(Regex("\\s*\\(\\d+x\\d+\\)"), "")
        .replace(Regex("(?i)Sendvid:default"), "")
        .replace(Regex("(?i)Sibnet:default"), "")
        .replace(Regex("(?i)Doodstream:default"), "")
        .replace(Regex("(?i)Voe:default"), "")
        .replace(Regex("(?i)Vidzy:default"), "")
        .replace(Regex("(?i)Lulu:default"), "")
        .replace(Regex("(?i)\\bHD\\b"), "1080p")
        .replace(Regex(" - - "), " - ")
        .replace(Regex("\\s*-\\s*-\\s*"), " - ")
        .trim()
        .removeSuffix("-")
        .trim()

    override fun List<Video>.sort(): List<Video> {
        val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains("Vidzy", true) } // Priority Vidzy
                .thenByDescending { it.quality.contains(prefVoice, true) }
                .thenByDescending { it.quality.contains("1080") || it.quality.contains("720") },
        )
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Preferences ============================
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

        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = "Préférence des voix"
            entries = VOICES_ENTRIES
            entryValues = VOICES_VALUES
            setDefaultValue(PREF_VOICES_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_VOICES_KEY, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://w16.french-manga.net"

        private const val PREF_VOICES_KEY = "preferred_voices"
        private const val PREF_VOICES_TITLE = "Préférence des voix"
        private val VOICES_ENTRIES = arrayOf("Préférer VOSTFR", "Préférer VF")
        private val VOICES_VALUES = arrayOf("VOSTFR", "VF")
        private const val PREF_VOICES_DEFAULT = "VOSTFR"
    }
}
