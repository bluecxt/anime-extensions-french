package eu.kanade.tachiyomi.animeextension.fr.animesultra

import android.util.Log
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
import eu.kanade.tachiyomi.util.parallelMap
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.Source
import fr.bluecxt.core.fetchTmdbMetadata
import fr.bluecxt.core.safeRelativePath
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimesUltra :
    Source(),
    CommonPreferences {

    override val name = "AnimesUltra"
    override val defaultBaseUrl = "https://ww.animesultra.org"
    override val supportedServers = listOf("UltraCDN", "Vidmoly", "Sibnet", "Sendvid")
    override val supportedVoices = arrayOf("VOSTFR", "VF")

    override val lang = "fr"
    override val supportsLatest = true

    companion object {
        private val CLEAN_REGEX = Regex("(?i)\\s*(\\((?:VF|VOSTFR|AU|DLL)\\)|\\b(?:VF|VOSTFR|AU|DLL|Saison|Season)\\b)")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val NEWS_ID_REGEX = Regex("""/(\d+)-""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val CLEAN_QUALITY_REGEX_1 = Regex("(?i)\\(\\s*Player\\s*\\)\\s*|\\(\\s*None\\s*\\)\\s*|\\s*\\(\\d+x\\d+\\)|\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s")
        private val CLEAN_QUALITY_REGEX_2 = Regex("(?i)Sendvid:default|Sibnet:default|Voe:default|Vidmoly:default")
        private val SERVERS = listOf("UltraCDN", "Vidmoly", "Sibnet", "Sendvid")
    }

    @Serializable
    data class AnimeUrl(var vostfr: String? = null, var vf: String? = null)

    @Serializable
    data class FullStoryResponse(val status: Boolean = false, val html: String = "")

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val response = client.newCall(GET(baseUrl, headers)).awaitSuccess()
        val document = response.asJsoup()
        val items = document.select(".block_area_trending .swiper-slide").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a.film-poster")!!
                title = link.attr("title").ifBlank { element.selectFirst(".film-title")?.text() ?: "" }
                url = link.safeRelativePath()
                thumbnail_url = element.selectFirst("img.film-poster-img")?.attr("abs:data-src") ?: element.selectFirst("img.film-poster-img")?.attr("abs:src")
            }
        }
        return AnimesPage(unifyAnimes(items), false)
    }

    private fun parseAnimesPage(document: Document): AnimesPage {
        val items = document.select("div.flw-item").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("h3.film-name a")!!
                title = link.text()
                url = link.safeRelativePath()
                thumbnail_url = element.selectFirst("img.film-poster-img")?.attr("abs:data-src") ?: element.selectFirst("img.film-poster-img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.select(".page-link").last()?.hasAttr("href") == true
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

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/xfsearch/statut/En%20Cours/page/$page/", headers)).awaitSuccess()
        return parseAnimesPage(response.asJsoup())
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.newCall(GET("$baseUrl/index.php?do=search&subaction=search&story=$query", headers)).awaitSuccess()
        return parseAnimesPage(response.asJsoup())
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
        val document = client.newCall(GET(baseUrl + targetUrl, headers)).awaitSuccess().asJsoup()

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

    private suspend fun discoverVersions(cleanedTitle: String, currentMap: AnimeUrl): AnimeUrl {
        val newMap = currentMap.copy()
        try {
            val searchResponse = client.newCall(GET("$baseUrl/index.php?do=search&subaction=search&story=$cleanedTitle", headers)).awaitSuccess()
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

        suspend fun fetch(path: String?, lang: String) {
            if (path != null) {
                val newsId = getNewsId(path)
                val ajaxUrl = "$baseUrl/engine/ajax/full-story.php?newsId=$newsId&d=${System.currentTimeMillis()}"
                val response = client.newCall(GET(ajaxUrl, headers.newBuilder().add("Referer", baseUrl + path).build())).awaitSuccess()
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
                scanlator = langUrls.keys.joinToString(", ") { it.uppercase() }

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

        val langGroupedHosters = mutableMapOf<String, MutableList<Pair<String, String>>>()

        urlMap.forEach { (lang, path) ->
            val langTag = lang.uppercase()
            val url = if (path.startsWith("http")) path else baseUrl + path
            val response = try {
                client.newCall(GET(url, headers)).awaitSuccess()
            } catch (_: Exception) {
                null
            } ?: return@forEach
            val document = response.asJsoup()

            val foundPlayers = mutableListOf<Pair<String, String>>()

            val findHosters = { doc: org.jsoup.nodes.Document ->
                doc.select("div.server-item").forEach { element ->
                    val serverId = element.attr("data-server-id").trim()
                    val serverName = element.text().trim().takeIf { it.isNotBlank() } ?: "Serveur"

                    // Look for specific player box (robust selector)
                    val playerBox = doc.selectFirst("[id=content_player_$serverId]")
                    val content = playerBox?.text()?.trim() ?: element.attr("data-embed")

                    if (content.isNotBlank() && content.length > 2) {
                        foundPlayers.add(content to serverName)
                    }
                }

                // Fallback for players not linked to a button
                doc.select(".player_box").forEach { element ->
                    val content = element.text().trim()
                    if (content.isNotBlank() && content.length > 5 && foundPlayers.none { it.first == content }) {
                        val name = getServerName(content) ?: "Serveur"
                        foundPlayers.add(content to name)
                    }
                }
            }

            findHosters(document)

            // Try AJAX
            try {
                val newsId = getNewsId(path)
                val ajaxUrl = "$baseUrl/engine/ajax/full-story.php?newsId=$newsId&d=${System.currentTimeMillis()}"
                val ajaxBody = client.newCall(GET(ajaxUrl, headers.newBuilder().add("Referer", url).build())).awaitSuccess().body.string()
                val html = if (ajaxBody.trim().startsWith("{")) json.decodeFromString<FullStoryResponse>(ajaxBody).html else ajaxBody
                findHosters(Jsoup.parse(html))
            } catch (_: Exception) {}

            if (foundPlayers.isNotEmpty()) {
                // Group by server name and take only 3 mirrors max
                val limitedPlayers = foundPlayers.groupBy {
                    when {
                        it.second.contains("Sibnet", true) -> "Sibnet"
                        it.second.contains("Vidmoly", true) -> "Vidmoly"
                        it.second.contains("Sendvid", true) -> "Sendvid"
                        else -> "UltraCDN"
                    }
                }.flatMap { (_, players) -> players.take(3) }

                langGroupedHosters.getOrPut(langTag) { mutableListOf() }.addAll(limitedPlayers)
            }
        }

        return langGroupedHosters.map { (lang, players) ->
            // Store as List of Pairs serialized to JSON
            // Unique by both URL and Name to avoid merging different servers with same generic embed
            val internalData = json.encodeToString(players.distinctBy { "${it.first}|${it.second}" }) + "|" + lang
            Hoster(hosterName = lang, internalData = internalData)
        }.coreSortHosters()
    }

    private fun getServerName(url: String): String? = when {
        url.contains("sibnet.ru") -> "Sibnet"
        url.contains("vidmoly") -> "Vidmoly"
        url.contains("sendvid.com") -> "Sendvid"
        url.contains("daisukianime.xyz") || url.contains("vidstream.pro") || url.contains("animesultra.org/player") || url.all { it.isDigit() } -> "UltraCDN"
        else -> null
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|")
        val players = json.decodeFromString<List<Pair<String, String>>>(data[0])
        val langTag = data[1]

        // Only process unique URLs to avoid overwhelming the server
        val uniquePlayers = players.distinctBy { it.first }

        val allVideos = uniquePlayers.parallelMap { (embedUrl, serverName) ->
            val absoluteUrl = when {
                // FORCE Sibnet direct URL for reliability
                serverName.contains("Sibnet", true) -> {
                    val id = NEWS_ID_REGEX.find(embedUrl)?.groupValues?.get(1) ?: embedUrl.filter { it.isDigit() }
                    "https://video.sibnet.ru/shell.php?videoid=$id"
                }

                embedUrl.startsWith("//") -> "https:$embedUrl"

                embedUrl.all { it.isDigit() } -> "https://lb.daisukianime.xyz/dist/embeds.html?id=$embedUrl"

                else -> embedUrl
            }

            android.util.Log.d("AnimesUltraDebug", "Processing $serverName: $absoluteUrl")

            try {
                extractVideos(absoluteUrl, langTag, supportedServers)
            } catch (e: Exception) {
                android.util.Log.e("AnimesUltraDebug", "Error extracting $serverName", e)
                emptyList()
            }
        }.flatten()

        val finalVideos = allVideos.map {
            val cleanedTitle = cleanQuality(it.videoTitle)
            it.copy(
                videoTitle = cleanedTitle,
                resolution = QUALITY_REGEX.find(cleanedTitle)?.groupValues?.get(1)?.toIntOrNull(),
            )
        }.distinctBy { it.videoUrl }

        if (finalVideos.isEmpty()) {
            android.util.Log.d("AnimesUltraDebug", "No videos found for $langTag")
        } else {
            android.util.Log.d("AnimesUltraDebug", "Final qualities for $langTag: ${finalVideos.map { it.videoTitle }}")
        }

        return finalVideos
    }

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(CLEAN_QUALITY_REGEX_1, "").replace(CLEAN_QUALITY_REGEX_2, "").replace(" - - ", " - ").trim().removeSuffix("-").trim()
        for (server in SERVERS) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server", RegexOption.IGNORE_CASE), server)
        }
        return cleaned.replace(WHITESPACE_REGEX, " ").replace(" - - ", " - ").trim()
    }
}
