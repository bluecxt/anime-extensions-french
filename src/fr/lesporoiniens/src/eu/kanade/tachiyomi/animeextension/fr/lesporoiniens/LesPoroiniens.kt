package eu.kanade.tachiyomi.animeextension.fr.lesporoiniens

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

class LesPoroiniens : AnimeHttpSource() {

    override val name = "Les Poroïniens"
    override val baseUrl = "https://lesporoiniens.org"
    override val lang = "fr"
    override val supportsLatest = false

    private val json: Json by injectLazy()

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
                        if (epArray == null || epArray.isEmpty()) return@async null

                        val seriesTitle = seriesJson["title"]?.jsonPrimitive?.content ?: ""
                        val animeInfo = seriesJson["anime"]?.jsonArray?.firstOrNull()?.jsonObject

                        SAnime.create().apply {
                            title = seriesTitle
                            url = "/${slugify(seriesTitle)}/episodes"
                            thumbnail_url = seriesJson["cover"]?.jsonPrimitive?.content
                            status = parseStatus(animeInfo?.get("status_an")?.jsonPrimitive?.content ?: seriesJson["release_status"]?.jsonPrimitive?.content)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Non utilisé")
    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("Non utilisé")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = popularAnimeRequest(page)
    override fun searchAnimeParse(response: Response): AnimesPage {
        val query = response.request.url.queryParameter("query")?.lowercase() ?: ""
        val page = popularAnimeParse(response)
        if (query.isBlank()) return page
        return AnimesPage(page.animes.filter { it.title.lowercase().contains(query) }, false)
    }

    // --- Détails ---
    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}")

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val jsonString = document.select("script#series-data-placeholder").first()?.data() ?: throw Exception("Données introuvables")
        val obj = json.decodeFromString<JsonObject>(jsonString)
        val animeInfo = obj["anime"]?.jsonArray?.firstOrNull()?.jsonObject

        return SAnime.create().apply {
            title = obj["title"]?.jsonPrimitive?.content ?: ""
            description = (animeInfo?.get("description") ?: obj["description"])?.jsonPrimitive?.content?.removeHtml()
            genre = obj["tags"]?.jsonArray?.joinToString { it.jsonPrimitive.content }
            status = parseStatus(animeInfo?.get("status_an")?.jsonPrimitive?.content ?: obj["release_status"]?.jsonPrimitive?.content)
            author = obj["author"]?.jsonPrimitive?.content
            artist = obj["artist"]?.jsonPrimitive?.content
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val jsonString = document.select("script#series-data-placeholder").first()?.data() ?: return emptyList()
        val obj = json.decodeFromString<JsonObject>(jsonString)
        val episodes = mutableListOf<SEpisode>()

        obj["episodes"]?.jsonArray?.forEach { epElement ->
            val ep = epElement.jsonObject
            val num = ep["indice_ep"]?.jsonPrimitive?.content ?: "0"
            val title = ep["title_ep"]?.jsonPrimitive?.content ?: ""

            episodes.add(
                SEpisode.create().apply {
                    episode_number = num.toFloatOrNull() ?: 0f

                    val cleanTitle = title.replace("Épisode", "Episode", true)
                    val displayNum = num.replace(".", ",")
                    val prefix = when {
                        cleanTitle.contains("OAV", true) -> "Episode OAV $displayNum"
                        cleanTitle.contains("ONA", true) -> "Episode ONA $displayNum"
                        cleanTitle.contains("Special", true) || cleanTitle.contains("Spécial", true) -> "Episode Special $displayNum"
                        cleanTitle.contains("Film", true) -> if ((obj["episodes"]?.jsonArray?.size ?: 0) > 1) "Film $displayNum" else "Film"
                        else -> "Episode $displayNum"
                    }
                    val epTitle = ep["title_ep"]?.jsonPrimitive?.content ?: ""
                    name = if (epTitle.isNotBlank() && !epTitle.contains("Épisode", true) && !epTitle.contains("Episode", true)) {
                        "$prefix : $epTitle"
                    } else {
                        prefix
                    }

                    url = "/video?type=${ep["type"]?.jsonPrimitive?.content}&id=${ep["id"]?.jsonPrimitive?.content}"
                    date_upload = parseDate(ep["date_ep"]?.jsonPrimitive?.content)
                },
            )
        }
        return episodes.sortedByDescending { it.episode_number }
    }

    // --- Vidéos ---
    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url
        val type = url.queryParameter("type") ?: return emptyList()
        val id = url.queryParameter("id") ?: return emptyList()

        return when (type) {
            "gdrive" -> {
                gdriveExtractor.videosFromUrl(id, "(VOSTFR) Google Drive -")
                    .map { video ->
                        Video(video.url, cleanQuality(video.quality), video.videoUrl, video.headers, video.subtitleTracks, video.audioTracks)
                    }
            }

            "vidmoly" -> {
                val vidmolyUrl = "https://vidmoly.to/embed-$id.html"
                vidmolyExtractor.videosFromUrl(vidmolyUrl, "(VOSTFR) Vidmoly -")
                    .map { video ->
                        Video(video.url, cleanQuality(video.quality), video.videoUrl, video.headers, video.subtitleTracks, video.audioTracks)
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
    } catch (e: Exception) {
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
