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
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)

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
            episodes.add(
                SEpisode.create().apply {
                    episode_number = num.toFloatOrNull() ?: 0f
                    name = "Épisode $num : ${ep["title_ep"]?.jsonPrimitive?.content ?: ""}"
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
                val gDriveHeaders = Headers.Builder().add("User-Agent", "Mozilla/5.0").build()
                GoogleDriveExtractor(client, gDriveHeaders).videosFromUrl(id, "(VOSTFR) Google Drive -")
                    .map { video ->
                        val cleanQuality = video.quality.replace(" - (", " - ").removeSuffix(")")
                        Video(video.url, cleanQuality, video.videoUrl, video.headers, video.subtitleTracks, video.audioTracks)
                    }
            }

            "vidmoly" -> {
                val vidmolyUrl = "https://vidmoly.to/embed-$id.html"
                VidMolyExtractor(client, headers).videosFromUrl(vidmolyUrl, "(VOSTFR) Vidmoly - ")
                    .map { video ->
                        val cleanQuality = video.quality.replace(" - (", " - ").removeSuffix(")")
                        Video(video.url, cleanQuality, video.videoUrl, video.headers, video.subtitleTracks, video.audioTracks)
                    }
            }

            else -> emptyList()
        }
    }

    // --- Utils ---
    private fun slugify(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), "")
        .lowercase().replace(Regex("[^a-z0-9]"), "_").replace(Regex("_+"), "_").trim('_')

    private fun parseStatus(status: String?): Int = when (status?.lowercase()?.trim()) {
        "en cours" -> SAnime.ONGOING
        "terminé", "fini" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    private fun String.removeHtml(): String = this.replace(Regex("<[^>]*>"), "").trim()

    private fun parseDate(date: String?): Long = try {
        dateFormat.parse(date ?: "")?.time ?: 0L
    } catch (e: Exception) {
        date?.toLongOrNull()?.let { it * 1000 } ?: 0L
    }
}
