package eu.kanade.tachiyomi.animeextension.fr.lesporoiniens

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

class LesPoroiniens : Source() {

    override val name = "Les Poroïniens"
    override val baseUrl = "https://lesporoiniens.org"
    override val lang = "fr"
    override val supportsLatest = false

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    override val json: Json by injectLazy()

    private val gdriveExtractor by lazy { GoogleDriveExtractor(client, Headers.Builder().add("User-Agent", "Mozilla/5.0").build()) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // --- Catalogue (Scan Parallèle pour la performance) ---
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/data/config.json")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val configObj = json.decodeFromString<JsonObject>(response.body.string())
        val fileList = configObj["LOCAL_SERIES_FILES"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

        val animes = runBlocking(Dispatchers.IO) {
            fileList.map { fileName ->
                async {
                    try {
                        val seriesResponse = client.newCall(GET("$baseUrl/data/series/$fileName")).execute()
                        val seriesJson = json.decodeFromString<JsonObject>(seriesResponse.body.string())
                        val epArray = seriesJson["episodes"]?.jsonArray
                        if (epArray.isNullOrEmpty()) return@async null

                        val seriesTitle = seriesJson["title"]?.jsonPrimitive?.content ?: ""
                        val animeInfo = seriesJson["anime"]?.jsonArray?.firstOrNull()?.jsonObject

                        SAnime.create().apply {
                            title = seriesTitle
                            url = "/${slugify(seriesTitle)}/episodes"
                            thumbnail_url = seriesJson["cover"]?.jsonPrimitive?.content
                            status = parseStatus(animeInfo?.get("status_an")?.jsonPrimitive?.content ?: seriesJson["release_status"]?.jsonPrimitive?.content)
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return AnimesPage(animes, false)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val pageData = client.newCall(popularAnimeRequest(page)).execute().use { popularAnimeParse(it) }
        if (query.isBlank()) return pageData
        return AnimesPage(pageData.animes.filter { it.title.contains(query, ignoreCase = true) }, false)
    }

    // --- Détails ---
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}")).execute()
        val document = response.asJsoup()
        val jsonString = document.select("script#series-data-placeholder").first()?.data() ?: throw Exception("Données introuvables")
        val obj = json.decodeFromString<JsonObject>(jsonString)
        val animeInfo = obj["anime"]?.jsonArray?.firstOrNull()?.jsonObject

        return anime.apply {
            title = obj["title"]?.jsonPrimitive?.content ?: ""
            description = (animeInfo?.get("description") ?: obj["description"])?.jsonPrimitive?.content?.removeHtml()
            genre = obj["tags"]?.jsonArray?.joinToString { it.jsonPrimitive.content }
            status = parseStatus(animeInfo?.get("status_an")?.jsonPrimitive?.content ?: obj["release_status"]?.jsonPrimitive?.content)
            author = obj["author"]?.jsonPrimitive?.content
            artist = obj["artist"]?.jsonPrimitive?.content
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}")).execute()
        val document = response.asJsoup()
        val jsonString = document.select("script#series-data-placeholder").first()?.data() ?: return emptyList()
        val obj = json.decodeFromString<JsonObject>(jsonString)
        val episodes = mutableListOf<SEpisode>()

        val seriesTitle = obj["title"]?.jsonPrimitive?.content ?: ""
        val tmdbId = fetchTmdbId(seriesTitle)
        val tmdbData = tmdbId?.let { fetchTmdbSeasonData(it, 1) }

        obj["episodes"]?.jsonArray?.forEach { epElement ->
            val ep = epElement.jsonObject
            val numStr = ep["indice_ep"]?.jsonPrimitive?.content ?: "0"
            val num = numStr.toDoubleOrNull()?.toInt() ?: 0
            val title = ep["title_ep"]?.jsonPrimitive?.content ?: ""

            episodes.add(
                SEpisode.create().apply {
                    episode_number = numStr.toFloatOrNull() ?: 0f

                    val cleanTitle = title.replace("Épisode", "Episode", true)
                    val displayNum = numStr.replace(".", ",")
                    val sPrefix = when {
                        cleanTitle.contains("OAV", true) || cleanTitle.contains("OVA", true) -> "[OVA] "
                        cleanTitle.contains("ONA", true) -> "[ONA] "
                        cleanTitle.contains("Special", true) || cleanTitle.contains("Spécial", true) -> "[Special] "
                        cleanTitle.contains("Film", true) || cleanTitle.contains("Movie", true) -> "[Movie] "
                        else -> ""
                    }
                    val epTitle = ep["title_ep"]?.jsonPrimitive?.content ?: ""
                    val baseName = if (epTitle.isNotBlank() && !epTitle.contains("Épisode", true) && !epTitle.contains("Episode", true)) {
                        "Episode $displayNum - $epTitle"
                    } else {
                        "Episode $displayNum"
                    }
                    name = "$sPrefix$baseName"

                    url = "/video?type=${ep["type"]?.jsonPrimitive?.content}&id=${ep["id"]?.jsonPrimitive?.content}"
                    date_upload = parseDate(ep["date_ep"]?.jsonPrimitive?.content)

                    // Metadata from TMDB
                    val epMeta = tmdbData?.get(num)
                    preview_url = epMeta?.second
                    summary = epMeta?.third
                },
            )
        }
        return episodes.sortedByDescending { it.episode_number }
    }

    private fun fetchTmdbId(title: String): Int? {
        val url = "https://api.themoviedb.org/3/search/tv?api_key=24621da8ae19dce721e59eff2ab479bb&query=${URLEncoder.encode(title, "UTF-8")}&language=fr-FR"
        return try {
            client.newCall(GET(url)).execute().use { response ->
                val json = JSONObject(response.body.string())
                val results = json.getJSONArray("results")
                if (results.length() > 0) results.getJSONObject(0).getInt("id") else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchTmdbSeasonData(tmdbId: Int, season: Int): Map<Int, Triple<String?, String?, String?>> {
        val url = "https://api.themoviedb.org/3/tv/$tmdbId/season/$season?api_key=24621da8ae19dce721e59eff2ab479bb&language=fr-FR"
        return try {
            client.newCall(GET(url)).execute().use { response ->
                val json = JSONObject(response.body.string())
                val episodesJson = json.getJSONArray("episodes")
                val map = mutableMapOf<Int, Triple<String?, String?, String?>>()
                for (i in 0 until episodesJson.length()) {
                    val ep = episodesJson.getJSONObject(i)
                    val epName = ep.optString("name").takeIf { it.isNotBlank() }
                    val thumb = ep.optString("still_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
                    val summary = ep.optString("overview").takeIf { it.isNotBlank() }
                    map[ep.getInt("episode_number")] = Triple(epName, thumb, summary)
                }
                map
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // --- Vidéos ---
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val type = episode.url.substringAfter("type=").substringBefore("&")
        val hosterName = when (type) {
            "gdrive" -> "Google Drive"
            "vidmoly" -> "Vidmoly"
            else -> type.replaceFirstChar { it.uppercase() }
        }
        return listOf(Hoster(hosterName = hosterName, internalData = episode.url))
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.internalData
        val type = url.substringAfter("type=").substringBefore("&")
        val id = url.substringAfter("id=")

        return when (type) {
            "gdrive" -> {
                gdriveExtractor.videosFromUrl(id, "(VOSTFR) Google Drive -")
                    .map { video ->
                        Video(videoUrl = video.videoUrl, videoTitle = cleanQuality(video.videoTitle), headers = video.headers, subtitleTracks = video.subtitleTracks, audioTracks = video.audioTracks)
                    }
            }

            "vidmoly" -> {
                val vidmolyUrl = "https://vidmoly.to/embed-$id.html"
                vidmolyExtractor.videosFromUrl(vidmolyUrl, "(VOSTFR) Vidmoly -")
                    .map { video ->
                        Video(videoUrl = video.videoUrl, videoTitle = cleanQuality(video.videoTitle), headers = video.headers, subtitleTracks = video.subtitleTracks, audioTracks = video.audioTracks)
                    }
            }

            else -> emptyList()
        }
    }

    private fun cleanQuality(quality: String): String = quality.replace(" - (", " - ")
        .replace(QUALITY_CLEAN_REGEX, "")
        .replace(" - - ", " - ")
        .trim()

    // --- Utils ---
    private fun slugify(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(SLUG_REGEX_1, "")
        .lowercase().replace(SLUG_REGEX_2, "_").replace(SLUG_REGEX_3, "_").trim('_')

    private fun parseStatus(status: String?): Int = when (status?.lowercase()?.trim()) {
        "en cours" -> SAnime.ONGOING
        "terminé", "fini" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    private fun String.removeHtml(): String = this.replace(HTML_TAG_REGEX, "").trim()

    private fun parseDate(date: String?): Long = try {
        DATE_FORMAT.parse(date ?: "")?.time ?: 0L
    } catch (_: Exception) {
        date?.toLongOrNull()?.let { it * 1000 } ?: 0L
    }

    companion object {
        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE) }
        private val QUALITY_CLEAN_REGEX = Regex("""(?<=\d{3,4}p)\)""")
        private val SLUG_REGEX_1 = Regex("[\\u0300-\\u036f]")
        private val SLUG_REGEX_2 = Regex("[^a-z0-9]")
        private val SLUG_REGEX_3 = Regex("_+")
        private val HTML_TAG_REGEX = Regex("<[^>]*>")
    }
}
