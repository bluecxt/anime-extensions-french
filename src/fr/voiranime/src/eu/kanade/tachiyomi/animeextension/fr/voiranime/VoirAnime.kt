@file:Suppress("SpellCheckingInspection")

package eu.kanade.tachiyomi.animeextension.fr.voiranime

import android.util.Base64
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
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class VoirAnime : Source() {

    override val name = "VoirAnime"
    override val baseUrl by lazy { preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!! }
    override val lang = "fr"
    override val supportsLatest = true

    override val json: Json by injectLazy()

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
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/series/?page=$page&order=popular", headers)).execute()
        val document = response.asJsoup()
        val items = document.select("div.listupd article.bs").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a")!!
                setUrlWithoutDomain(link.attr("abs:href"))
                title = link.selectFirst(".tt")?.ownText() ?: "Inconnu"
                thumbnail_url = link.selectFirst("img")?.attr("abs:src")?.substringBefore("?")
            }
        }
        val hasNextPage = document.selectFirst("div.hpage a.r") != null
        return AnimesPage(items, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/series/?page=$page&order=update", headers)).execute()
        val document = response.asJsoup()
        val items = document.select("div.listupd article.bs").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a")!!
                setUrlWithoutDomain(link.attr("abs:href"))
                title = link.selectFirst(".tt")?.ownText() ?: "Inconnu"
                thumbnail_url = link.selectFirst("img")?.attr("abs:src")?.substringBefore("?")
            }
        }
        val hasNextPage = document.selectFirst("div.hpage a.r") != null
        return AnimesPage(items, hasNextPage)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val response = client.newCall(GET("$baseUrl/series/$id", headers)).execute()
            val document = response.asJsoup()
            val anime = SAnime.create().apply {
                title = document.selectFirst("h1.entry-title")?.text() ?: ""
                setUrlWithoutDomain(document.location())
                thumbnail_url = document.selectFirst(".thumb img")?.attr("abs:src")?.substringBefore("?")
            }
            return AnimesPage(listOf(anime), false)
        }
        val formBody = FormBody.Builder()
            .add("action", "ts_ac_do_search")
            .add("ts_ac_query", query)
            .build()
        val response = client.newCall(
            Request.Builder()
                .url("$baseUrl/wp-admin/admin-ajax.php")
                .post(formBody)
                .headers(headers)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .build(),
        ).execute()
        return parseSearchPage(response.body.string())
    }

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
        } catch (_: Exception) {
            return AnimesPage(emptyList(), false)
        }
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET(baseUrl + anime.url, headers)).execute()
        val document = response.asJsoup()

        anime.title = document.selectFirst("h1.entry-title")?.text() ?: anime.title
        anime.description = document.select(".entry-content[itemprop=description]").text()
        anime.genre = document.select(".genxed a").joinToString { it.text() }
        anime.thumbnail_url = document.selectFirst(".thumb img")?.attr("abs:src")?.substringBefore("?")
        anime.artist = document.select(".spe > span:contains(Studio)").text().substringAfter(":").trim()

        // TMDB Metadata
        val tmdbMetadata = fetchTmdbMetadata(anime.title)
        tmdbMetadata?.summary?.let { anime.description = it }

        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET(baseUrl + anime.url, headers)).execute()
        val document = response.asJsoup()

        val tmdbMetadata = fetchTmdbMetadata(anime.title)
        val sNumRegex = Regex("""(?i)(?:Saison|Season)\s*(\d+)""")
        val sNumMatch = sNumRegex.find(anime.title)
        val sNum = sNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val sPrefix = if (sNumMatch != null) "[S$sNum] " else ""

        return document.select("div.eplister ul li a").map { element ->
            val numStr = element.selectFirst(".epl-num")?.text() ?: "0"
            val num = numStr.toIntOrNull() ?: 0
            val subText = element.selectFirst(".epl-sub")?.text() ?: "VOSTFR"
            val lang = if (subText.contains("VF", true)) "VF" else "VOSTFR"

            SEpisode.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                val epMeta = tmdbMetadata?.episodeSummaries?.get(num)
                val tmdbName = epMeta?.first
                val baseName = if (tmdbName != null) "Episode $numStr - $tmdbName" else "Episode $numStr"
                name = "$sPrefix$baseName"
                episode_number = numStr.toFloatOrNull() ?: 0f
                scanlator = lang

                // TMDB Metadata
                preview_url = epMeta?.second
                summary = epMeta?.third
            }
        }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.newCall(GET(baseUrl + episode.url, headers)).execute()
        val document = response.asJsoup()
        val lang = if (episode.scanlator == "VF") "VF" else "VOSTFR"

        return document.select("select.mirror option[data-index]").mapNotNull { element ->
            val encodedData = element.attr("value")
            if (encodedData.isBlank()) return@mapNotNull null

            val decodedHtml = try {
                Base64.decode(encodedData, Base64.DEFAULT).toString(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }

            val iframeUrl = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src") ?: return@mapNotNull null
            val absoluteUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl

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

            Hoster(hosterName = "($lang) $server", internalData = "$absoluteUrl|$lang|$server")
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|")
        val absoluteUrl = data[0]
        val lang = data[1]
        val server = data[2]
        val prefix = "($lang) $server - "

        val videos = when (server) {
            "Doodstream" -> doodExtractor.videosFromUrl(absoluteUrl, prefix)
            "Sibnet" -> sibnetExtractor.videosFromUrl(absoluteUrl, prefix)
            "Voe" -> voeExtractor.videosFromUrl(absoluteUrl, prefix)
            "Vidmoly" -> vidMolyExtractor.videosFromUrl(absoluteUrl, prefix)
            "Okru" -> okruExtractor.videosFromUrl(absoluteUrl, prefix)
            "VK" -> vkExtractor.videosFromUrl(absoluteUrl, prefix)
            "Filemoon" -> filemoonExtractor.videosFromUrl(absoluteUrl, prefix)
            else -> emptyList()
        }

        return videos.map { video ->
            Video(videoUrl = video.videoUrl, videoTitle = cleanQuality(video.videoTitle), headers = video.headers, subtitleTracks = video.subtitleTracks, audioTracks = video.audioTracks)
        }.sortVideos()
    }

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(qualityRegex, "").replace(sizeRegex, "").replace(serverDefaultRegex, "$1").replace(" - - ", " - ").trim().removeSuffix("-").trim()
        val servers = listOf("Vidmoly", "Sibnet", "Sendvid", "VK", "Filemoon", "Voe", "Doodstream", "Okru")
        for (server in servers) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server", RegexOption.IGNORE_CASE), server).replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(whitespaceRegex, " ").replace(" - - ", " - ").trim()
    }

    override fun List<Video>.sortVideos(): List<Video> = this.sortedWith(
        compareBy { pQualityRegex.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
    ).reversed()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = "URL de base"
            setDefaultValue(PREF_URL_DEFAULT)
            summary = baseUrl
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_URL_KEY, newValue as String).commit()
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://voiranime.io"
    }
}
