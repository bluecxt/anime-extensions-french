package eu.kanade.tachiyomi.animeextension.fr.lesporoiniens

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.network.GET
import fr.bluecxt.core.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.Source
import fr.bluecxt.core.extractors.GoogleDriveExtractor
import fr.bluecxt.core.fetchTmdbMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

class LesPoroiniens :
    Source(),
    CommonPreferences {

    override val name = "Les Poroïniens"

    override val defaultBaseUrl = "https://lesporoiniens.org"
    override val supportedServers = listOf("Google Drive")
    override val forceShowQualityPreference = false

    override val lang = "fr"
    override val supportsLatest = false

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)
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
                        val seriesResponse = client.newCall(GET("$baseUrl/data/series/$fileName")).awaitSuccess()
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
        val pageData = client.newCall(popularAnimeRequest(page)).awaitSuccess().use { popularAnimeParse(it) }
        if (query.isBlank()) return pageData
        return AnimesPage(pageData.animes.filter { it.title.contains(query, ignoreCase = true) }, false)
    }

    // --- Détails ---
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}")).awaitSuccess()
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
        val response = client.newCall(GET("$baseUrl${anime.url}")).awaitSuccess()
        val document = response.asJsoup()
        val jsonString = document.select("script#series-data-placeholder").first()?.data() ?: return emptyList()
        val obj = json.decodeFromString<JsonObject>(jsonString)
        val episodes = mutableListOf<SEpisode>()
        val episodeJsonList = obj["episodes"]?.jsonArray?.map { it.jsonObject } ?: emptyList()

        fun JsonObject.getSeasonNumber(): Int {
            val raw = listOf("saison_ep", "season_ep", "saison", "season")
                .firstNotNullOfOrNull { key -> this[key]?.jsonPrimitive?.contentOrNull }
                ?.trim()
                .orEmpty()
            return raw.toIntOrNull() ?: 1
        }
        val seasonNumbers = episodeJsonList.map { it.getSeasonNumber() }
        val hasSeasonDiversity = seasonNumbers.distinct().size > 1
        val hasSeasonResetSignal = episodeJsonList.zipWithNext().any { (prev, curr) ->
            val prevSeason = prev.getSeasonNumber()
            val currSeason = curr.getSeasonNumber()
            val prevNum = prev["indice_ep"]?.jsonPrimitive?.content?.replace(",", ".")?.toFloatOrNull() ?: -1f
            val currNum = curr["indice_ep"]?.jsonPrimitive?.content?.replace(",", ".")?.toFloatOrNull() ?: -1f
            currSeason > prevSeason && currNum > 0f && prevNum > 0f && currNum <= prevNum
        }
        val hasMultipleSeasons = hasSeasonDiversity && hasSeasonResetSignal

        val seriesTitle = obj["title"]?.jsonPrimitive?.content ?: ""
        val tmdbSeason1 = fetchTmdbMetadata(seriesTitle, 1)?.episodeSummaries.orEmpty()
        val tmdbSeason0 = fetchTmdbMetadata(seriesTitle, 0)?.episodeSummaries.orEmpty()

        val adjustedEpisodeNumbers = run {
            var offset = 0
            episodeJsonList.map { ep ->
                val normalized = ep["indice_ep"]?.jsonPrimitive?.content
                    ?.replace(",", ".")
                    ?.toFloatOrNull() ?: 0f
                val isHalf = ((normalized * 10).toInt() % 10) == 5
                if (isHalf) {
                    val adjusted = kotlin.math.floor(normalized.toDouble()).toInt() + 1 + offset
                    offset += 1
                    adjusted.toFloat()
                } else {
                    normalized + offset
                }
            }
        }
        fun detectSpecialPrefix(title: String): String = when {
            title.contains("OAV", true) || title.contains("OVA", true) -> "[OVA] "
            title.contains("ONA", true) -> "[ONA] "
            title.contains("Special", true) || title.contains("Spécial", true) -> "[Special] "
            title.contains("Film", true) || title.contains("Movie", true) -> "[Movie] "
            else -> ""
        }
        val hasAnySpecial = episodeJsonList.any { ep ->
            val t = ep["title_ep"]?.jsonPrimitive?.content.orEmpty()
            detectSpecialPrefix(t).isNotBlank()
        }

        var specialSeason0Counter = 0
        episodeJsonList.forEachIndexed { idx, ep ->
            val numStr = ep["indice_ep"]?.jsonPrimitive?.content ?: "0"
            val normalizedNum = numStr.replace(",", ".").toFloatOrNull() ?: 0f
            val adjustedNum = adjustedEpisodeNumbers[idx]
            val originalTmdbEpisodeNum = normalizedNum.toInt()
            val isHalfEpisode = ((normalizedNum * 10).toInt() % 10) == 5
            val tmdbSeason0EpisodeNum = if (isHalfEpisode) {
                specialSeason0Counter += 1
                specialSeason0Counter
            } else {
                0
            }

            episodes.add(
                SEpisode.create().apply {
                    episode_number = adjustedNum
                    val displayNum = normalizedNum.toString().removeSuffix(".0")
                    val epTitle = ep["title_ep"]?.jsonPrimitive?.content ?: ""
                    val cleanedEpTitle = epTitle.replace("Épisode", "", true).replace("Episode", "", true).trim()
                    val specialPrefix = detectSpecialPrefix(cleanedEpTitle)
                    val seasonPrefix = if (specialPrefix.isBlank() && hasAnySpecial) "[S1] " else ""
                    name = if (cleanedEpTitle.isNotBlank()) {
                        "${seasonPrefix}${specialPrefix}Episode $displayNum - $cleanedEpTitle"
                    } else {
                        "${seasonPrefix}${specialPrefix}Episode $displayNum"
                    }

                    url = "/video?type=${ep["type"]?.jsonPrimitive?.content}&id=${ep["id"]?.jsonPrimitive?.content}"
                    date_upload = parseDate(ep["date_ep"]?.jsonPrimitive?.content)

                    // Metadata from TMDB
                    val epMeta = if (isHalfEpisode) tmdbSeason0[tmdbSeason0EpisodeNum] else tmdbSeason1[originalTmdbEpisodeNum]
                    preview_url = epMeta?.second
                    summary = epMeta?.third
                },
            )
        }
        return episodes.sortedByDescending { it.episode_number }
    }

    // --- Vidéos ---
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val type = episode.url.substringAfter("type=").substringBefore("&")
        val hosterName = when (type) {
            "gdrive" -> "Google Drive"
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
                GoogleDriveExtractor(client).videosFromUrl(id)
                    .map { it.buildFromSource("VOSTFR", "Google Drive") }
            }

            else -> emptyList()
        }
    }

    // --- Utils ---
    private fun slugify(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(SLUG_REGEX_1, "")
        .lowercase().replace(SLUG_REGEX_2, "_").replace(SLUG_REGEX_3, "_").trim('_')

    private fun parseStatus(status: String?): Int = when (status?.lowercase()?.trim()) {
        "en cours", "en cou" -> SAnime.ONGOING
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
        private val SLUG_REGEX_1 = Regex("[\\u0300-\\u036f]")
        private val SLUG_REGEX_2 = Regex("[^a-z0-9]")
        private val SLUG_REGEX_3 = Regex("_+")
        private val HTML_TAG_REGEX = Regex("<[^>]*>")
    }
}
