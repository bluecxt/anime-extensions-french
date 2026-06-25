@file:Suppress("SpellCheckingInspection")

package eu.kanade.tachiyomi.animeextension.fr.voiranime

import android.util.Base64
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.Source
import fr.bluecxt.core.VOIRANIME_LOG
import fr.bluecxt.core.fetchTmdbMetadata
import fr.bluecxt.core.safeRelativePath
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

class VoirAnime :
    Source(),
    CommonPreferences {

    override val name = "VoirAnime"
    override val defaultBaseUrl = "https://voiranime.io"

    override val lang = "fr"
    override val supportsLatest = true

    override val supportedServers = listOf("Vidmoly")

    override val json: Json by injectLazy()

    private val whitespaceRegex = Regex("\\s+")

// ============================== Popular & Latest ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/series/?page=$page&order=popular", headers)).awaitSuccess()
        val document = response.asJsoup()
        val items = document.select("div.listupd article.bs").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a")!!
                url = link.safeRelativePath()
                title = link.selectFirst(".tt")?.ownText() ?: "Inconnu"
                thumbnail_url = link.selectFirst("img")?.attr("abs:src")?.substringBefore("?")
            }
        }
        val hasNextPage = document.selectFirst("div.hpage a.r") != null
        return AnimesPage(items, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/series/?page=$page&order=update", headers)).awaitSuccess()
        val document = response.asJsoup()
        val items = document.select("div.listupd article.bs").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a")!!
                url = link.safeRelativePath()
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
            val response = client.newCall(GET("$baseUrl/series/$id", headers)).awaitSuccess()
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
        ).awaitSuccess()
        return parseSearchPage(response.body.string())
    }

    @Serializable
    data class AnimeResult(val post_title: String, val post_image: String, val post_link: String)

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
                    title = result.post_title
                    thumbnail_url = result.post_image.substringBefore("?")
                    url = result.post_link.substringAfter(baseUrl)
                }
            }
            return AnimesPage(items, false)
        } catch (_: Exception) {
            return AnimesPage(emptyList(), false)
        }
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET(baseUrl + anime.url, headers)).awaitSuccess()
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
        val response = client.newCall(GET(baseUrl + anime.url, headers)).awaitSuccess()
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
                url = element.safeRelativePath()
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
        val lang = if (episode.scanlator == "VF") "VF" else "VOSTFR"

        return listOf(Hoster(hosterName = lang, hosterUrl = episode.url))
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = baseUrl + hoster.hosterUrl
        val lang = hoster.hosterName

        val response = client.newCall(GET(url)).awaitSuccess()
        val document = response.asJsoup()

        val videos = document.select("select.mirror option[data-index]").mapNotNull { element ->
            val base64Value = element.attr("value")
            val decoded = try {
                Base64.decode(base64Value, Base64.DEFAULT).toString(java.nio.charset.StandardCharsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
            val iframeUrl = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: return@mapNotNull null
            if (iframeUrl.startsWith("//")) {
                "https:$iframeUrl"
            } else {
                iframeUrl
            }
        }

        Log.d(VOIRANIME_LOG, "list url = $videos")
        return videos.map { playerUrl ->
            extractVideos(playerUrl, lang, supportedServers)
        }.flatten().coreSortVideos()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
