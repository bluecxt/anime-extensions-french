@file:Suppress("SpellCheckingInspection")

package eu.kanade.tachiyomi.animeextension.fr.voiranime

import android.util.Base64
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
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class VoirAnime :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "VoirAnime"
    override val baseUrl by lazy { preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!! }
    override val lang = "fr"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val json = Json { ignoreUnknownKeys = true }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }

    private val qualityRegex = Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s")
    private val sizeRegex = Regex("\\s*\\(\\d+x\\d+\\)")
    private val serverDefaultRegex = Regex("(?i)(Sendvid|Sibnet|Doodstream|Voe|Vidmoly|Filemoon|Okru|VK):default")
    private val pQualityRegex = Regex("""(\d+)p""")
    private val whitespaceRegex = Regex("\\s+")

    // ============================== Popular & Latest ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series/?page=$page&order=popular", headers)
    override fun popularAnimeSelector(): String = "div.listupd article.bs"
    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        title = link.selectFirst(".tt")?.ownText() ?: "Inconnu"
        thumbnail_url = link.selectFirst("img")?.attr("abs:src")?.substringBefore("?")
    }
    override fun popularAnimeNextPageSelector(): String = "div.hpage a.r"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/series/?page=$page&order=update", headers)
    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search (AJAX) ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return GET("$baseUrl/series/$id", headers)
        }
        val formBody = FormBody.Builder()
            .add("action", "ts_ac_do_search")
            .add("ts_ac_query", query)
            .build()
        return Request.Builder()
            .url("$baseUrl/wp-admin/admin-ajax.php")
            .post(formBody)
            .headers(headers)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseSearchPage(response.body.string())

    @Serializable
    data class AnimeResult(val postTitle: String, val postImage: String, val postLink: String)

    @Serializable
    data class SearchResponse(val all: List<AnimeResult>)

    private fun parseSearchPage(responseString: String): AnimesPage {
        try {
            val responseJson = JSONObject(responseString)
            val seriesArray = responseJson.optJSONArray("series")
            if (seriesArray == null || seriesArray.length() == 0) return AnimesPage(emptyList(), false)

            val seriesDataString = seriesArray.getJSONObject(0).toString()
            val data = json.decodeFromString<SearchResponse>(seriesDataString)
            val items = data.all.map { result ->
                SAnime.create().apply {
                    title = result.postTitle
                    thumbnail_url = result.postImage.substringBefore("?")
                    url = result.postLink.substringAfter(baseUrl)
                }
            }
            return AnimesPage(items, false)
        } catch (e: Exception) {
            return AnimesPage(emptyList(), false)
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.entry-title")?.text() ?: "Titre inconnu"
        description = document.select(".entry-content[itemprop=description]").text().ifBlank { "Description non trouvée" }
        genre = document.select(".genxed a").joinToString { it.text() }
        thumbnail_url = document.selectFirst(".thumb img")?.attr("abs:src")?.substringBefore("?")
        author = document.select(".spe > span:nth-child(2)").text().substringAfter(":").trim()

        val statusText = document.select("span:contains(Status) i").text().lowercase()
        status = when {
            statusText.contains("ongoing") || statusText.contains("en cours") -> SAnime.ONGOING
            statusText.contains("completed") || statusText.contains("terminé") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "div.eplister ul li a"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        val num = element.selectFirst(".epl-num")?.text() ?: "0"
        val subText = element.selectFirst(".epl-sub")?.text() ?: "VOSTFR"

        name = "Episode $num"
        episode_number = num.toFloatOrNull() ?: 0f
        scanlator = if (subText.contains("VF", true)) "VF" else "VOSTFR"
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map(::episodeFromElement)
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val document = client.newCall(GET(baseUrl + episode.url, headers)).execute().asJsoup()
        val lang = if (episode.scanlator == "VF") "VF" else "VOSTFR"

        return document.select("select.mirror option[data-index]").parallelCatchingFlatMapBlocking { element ->
            val encodedData = element.attr("value")
            if (encodedData.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()

            val decodedHtml = try {
                Base64.decode(encodedData, Base64.DEFAULT).toString(Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }

            val iframeUrl = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")
                ?: return@parallelCatchingFlatMapBlocking emptyList()

            extractVideos(iframeUrl, lang)
        }.map { video ->
            Video(video.url, cleanQuality(video.quality), video.videoUrl, video.headers, video.subtitleTracks, video.audioTracks)
        }.sort()
    }

    private fun extractVideos(url: String, lang: String): List<Video> {
        val absoluteUrl = if (url.startsWith("//")) "https:$url" else url
        val server = when {
            absoluteUrl.contains("dood") -> "Doodstream"
            absoluteUrl.contains("sibnet") -> "Sibnet"
            absoluteUrl.contains("voe") -> "Voe"
            absoluteUrl.contains("vidmoly") -> "Vidmoly"
            absoluteUrl.contains("ok.ru") -> "Okru"
            absoluteUrl.contains("vk.com") -> "VK"
            absoluteUrl.contains("filemoon") -> "Filemoon"
            else -> "Serveur"
        }
        val prefix = "($lang) $server - "

        return when (server) {
            "Doodstream" -> doodExtractor.videosFromUrl(absoluteUrl, prefix)
            "Sibnet" -> sibnetExtractor.videosFromUrl(absoluteUrl, prefix)
            "Voe" -> voeExtractor.videosFromUrl(absoluteUrl, prefix)
            "Vidmoly" -> vidMolyExtractor.videosFromUrl(absoluteUrl, prefix)
            "Okru" -> okruExtractor.videosFromUrl(absoluteUrl, prefix)
            "VK" -> vkExtractor.videosFromUrl(absoluteUrl, prefix)
            "Filemoon" -> filemoonExtractor.videosFromUrl(absoluteUrl, prefix)
            else -> emptyList()
        }
    }

    private fun cleanQuality(quality: String): String {
        var cleaned = quality
            .replace(qualityRegex, "")
            .replace(sizeRegex, "")
            .replace(serverDefaultRegex, "$1")
            .replace(" - - ", " - ")
            .trim()
            .removeSuffix("-")
            .trim()

        val servers = listOf("Vidmoly", "Sibnet", "Sendvid", "VK", "Filemoon", "Voe", "Doodstream", "Okru")
        for (server in servers) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server", RegexOption.IGNORE_CASE), server)
            cleaned = cleaned.replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(whitespaceRegex, " ").replace(" - - ", " - ").trim()
    }

    override fun List<Video>.sort(): List<Video> = this.sortedWith(
        compareBy {
            pQualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        },
    ).reversed()

    // ============================== Utils ==============================
    override fun searchAnimeSelector(): String = ""
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector(): String = ""
    override fun videoListParse(response: Response) = throw UnsupportedOperationException()
    override fun videoListSelector() = ""
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

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
        const val PREFIX_SEARCH = "id:"
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://voiranime.io"
    }
}
