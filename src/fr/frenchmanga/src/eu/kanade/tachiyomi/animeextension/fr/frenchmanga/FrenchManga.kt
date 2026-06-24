package eu.kanade.tachiyomi.animeextension.fr.frenchmanga

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
import fr.bluecxt.core.fetchTmdbMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

open class FrenchManga(
    override val name: String = "French-Manga",
    protected open val prefUrlDefault: String = "https://w16.french-manga.net",
) : Source(),
    CommonPreferences {

    override val defaultBaseUrl = prefUrlDefault

    override val baseUrl: String get() = currentBaseUrl

    override val lang = "fr"

    override val supportsLatest = true

    override val supportedServers = listOf(
        "Filemoon",
        "Lulu",
        "Dood",
        "Voe",
        "Sibnet",
        "Vidmoly",
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super<CommonPreferences>.setupPreferenceScreen(screen)
    }

    override val json: Json by injectLazy()

    @Serializable
    data class AnimeUrl(
        val ids: List<String>,
    )

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Cookie", "skin_name=MGV1")

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/manga-streaming-1/coups-de-cur/page/$page/?m_orderby=views", headers)).awaitSuccess()
        val document = response.asJsoup()
        val animes = document.select("div.short").map { element ->
            SAnime.create().apply {
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
        }
        val hasNextPage = document.selectFirst(".pagi-nav a:contains(Suivant), .pagi-nav a:contains(Next)") != null
        return AnimesPage(unifyAnimes(animes), hasNextPage)
    }

    private fun unifyAnimes(animes: List<SAnime>): List<SAnime> = animes.distinctBy { it.url }.groupBy {
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

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/manga-streaming/page/$page/", headers)).awaitSuccess()
        val document = response.asJsoup()
        val animes = document.select("div.short").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a.short-poster")!!
                val newsId = link.attr("href").substringAfter("newsid=", "").substringBefore("&")
                    .takeIf { it.isNotEmpty() } ?: link.attr("abs:href").substringAfterLast("/").substringBefore("-")
                url = newsId
                title = element.selectFirst(".short-title")!!.text()
                thumbnail_url = link.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst(".pagi-nav a:contains(Suivant), .pagi-nav a:contains(Next)") != null
        return AnimesPage(unifyAnimes(animes), hasNextPage)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val response = client.newCall(GET("$baseUrl/index.php?newsid=$id", headers)).awaitSuccess()
            val document = response.asJsoup()
            val anime = SAnime.create().apply {
                title = document.selectFirst("h1")?.text() ?: ""
                url = json.encodeToString(AnimeUrl(listOf(id)))
                thumbnail_url = document.selectFirst(".fposter img")?.attr("abs:src")
            }
            return AnimesPage(listOf(anime).filter(::isValidSearchAnime), false)
        }
        val formBody = okhttp3.FormBody.Builder()
            .add("query", query)
            .add("page", page.toString())
            .build()

        val response = client.newCall(
            Request.Builder()
                .url("$baseUrl/engine/ajax/search.php")
                .post(formBody)
                .headers(headers)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .build(),
        ).awaitSuccess()

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
        return AnimesPage(unifyAnimes(animes).filter(::isValidSearchAnime), false)
    }

    private fun isValidSearchAnime(anime: SAnime): Boolean {
        val t = anime.title.trim()
        val u = anime.url.trim()
        if (t.isBlank() || u.isBlank()) return false
        val low = t.lowercase()
        if (low == "unknown" || low == "unknown title" || low == "inconnu") return false
        return true
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val ids = try {
            json.decodeFromString<AnimeUrl>(anime.url).ids
        } catch (_: Exception) {
            listOf(anime.url)
        }
        val response = client.newCall(GET("$baseUrl/index.php?newsid=${ids.first()}", headers)).awaitSuccess()
        val document = response.asJsoup()

        anime.artist = document.selectFirst("li:contains(Studio)")?.ownText()?.trim() ?: ""
        anime.author = document.select("li:contains(Director) a").joinToString { it.text().trim() }
        anime.title = document.selectFirst("#s-title")?.text() ?: anime.title
        anime.description = (document.selectFirst(".fdesc")?.text() ?: document.select(".full-story").text()).trim()
        anime.genre = document.select(".genres a, .full-inf li:contains(Genre) a").joinToString { it.text() }
        anime.thumbnail_url = document.selectFirst(".fposter img")?.attr("abs:src") ?: anime.thumbnail_url

        // TMDB Metadata
        val tmdbMetadata = fetchTmdbMetadata(cleanTitle(anime.title))
        tmdbMetadata?.summary?.let { anime.description = it }

        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val ids = try {
            json.decodeFromString<AnimeUrl>(anime.url).ids
        } catch (_: Exception) {
            listOf(anime.url)
        }
        val episodesMap = mutableMapOf<String, MutableMap<String, JsonObject>>()

        val tmdbMetadata = fetchTmdbMetadata(cleanTitle(anime.title))
        val sNumRegex = Regex("""(?i)(?:Saison|Season)\s*(\d+)""")
        val sNumMatch = sNumRegex.find(anime.title)
        val sNum = sNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val sPrefix = if (sNumMatch != null && sNum > 1) "[S$sNum] " else ""

        ids.forEach { newsId ->
            val ajaxUrl = "$baseUrl/engine/ajax/manga_episodes_api.php?id=$newsId"
            try {
                val ajaxResponse = client.newCall(GET(ajaxUrl, headers)).awaitSuccess()
                val jsonResponse = json.parseToJsonElement(ajaxResponse.body.string()).jsonObject

                listOf("vf", "vostfr").forEach { langType ->
                    jsonResponse[langType]?.jsonObject?.forEach { (epNum, hosters) ->
                        val epMap = episodesMap.getOrPut(epNum) { mutableMapOf() }
                        val existingHosters = epMap[langType]?.toMutableMap() ?: mutableMapOf()
                        hosters.jsonObject.forEach { (k, v) -> existingHosters[k] = v }
                        epMap[langType] = JsonObject(existingHosters)
                    }
                }
            } catch (_: Exception) {}
        }

        return episodesMap.map { (epNum, langMap) ->
            val epNumInt = epNum.toIntOrNull() ?: 1
            SEpisode.create().apply {
                val actualEpNum = epNum.toFloatOrNull() ?: 0f
                episode_number = actualEpNum
                name = "${sPrefix}Episode $epNum"
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

                // TMDB Metadata
                val epMeta = tmdbMetadata?.episodeSummaries?.get(epNumInt)
                preview_url = epMeta?.second
                summary = epMeta?.third
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val epData = json.parseToJsonElement(episode.url).jsonObject
        val langs = epData["langs"]?.jsonObject ?: return emptyList()

        return langs.map { (langType, langHosters) ->
            val urls = langHosters.jsonObject.values.map { it.jsonPrimitive.content }
            Hoster(
                hosterName = langType.uppercase(),
                hosterUrl = json.encodeToString(urls),
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val playerUrls = json.decodeFromString<List<String>>(hoster.hosterUrl)
        val lang = hoster.hosterName

        return playerUrls.flatMap { playerUrl ->
            extractVideos(playerUrl, lang, supportedServers)
        }.coreSortVideos()
    }

    override fun getAnimeUrl(anime: SAnime): String {
        val ids = try {
            json.decodeFromString<AnimeUrl>(anime.url).ids
        } catch (_: Exception) {
            listOf(anime.url)
        }
        return "$baseUrl/index.php?newsid=${ids.first()}"
    }

    // ============================ Utils =============================

    private fun cleanTitle(title: String): String = title.replace(cleanRegex, "").trim()
    private val cleanRegex = Regex("(?i)\\s*\\((VF|VOSTFR|VF\\+VOSTFR|VOST|UNCUT)\\)")

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
