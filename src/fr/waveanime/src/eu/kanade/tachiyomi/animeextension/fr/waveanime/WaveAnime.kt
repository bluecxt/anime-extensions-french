package eu.kanade.tachiyomi.animeextension.fr.waveanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class WaveAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "WaveAnime"

    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!
    }

    override val lang = "fr"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://waveanime.fr"

        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Langue préférée"
        private val LANG_ENTRIES = arrayOf("VOSTFR", "VF")
        private val LANG_VALUES = arrayOf("VOSTFR", "VF")
        private const val PREF_LANG_DEFAULT = "VOSTFR"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualité préférée"
        private val QUALITY_ENTRIES = arrayOf("Highest", "1440p", "1080p", "720p", "480p", "360p")
        private val QUALITY_VALUES = arrayOf("Highest", "1440", "1080", "720", "480", "360")
        private const val PREF_QUALITY_DEFAULT = "Highest"
    }

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

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Préférence des voix"
            entries = LANG_ENTRIES
            entryValues = LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_LANG_KEY, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = QUALITY_ENTRIES
            entryValues = QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_QUALITY_KEY, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    @Serializable
    data class TracksResponse(
        val audios: Map<String, Int?>,
        val subtitles: Map<String, Int?>,
    )

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/catalog")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("div.component.serie-card").map { element ->
            val link = element.selectFirst("a")!!
            SAnime.create().apply {
                setUrlWithoutDomain(link.attr("href"))
                title = element.attr("title")
                thumbnail_url = baseUrl + element.selectFirst("img")!!.attr("src")
            }
        }.sortedBy { it.title }

        val allSeasons = items.flatMap { fetchSeasons(it) }
        return AnimesPage(allSeasons, false)
    }

    private fun fetchSeasons(anime: SAnime): List<SAnime> {
        return try {
            val response = client.newCall(GET(baseUrl + anime.url, headers)).execute()
            val document = response.asJsoup()
            val seasonGrids = document.select("div.component.episode-card-grid")

            if (seasonGrids.isEmpty()) return listOf(anime)

            seasonGrids.map { grid ->
                val seasonNum = grid.attr("data-season")
                SAnime.create().apply {
                    title = if (seasonGrids.size > 1) "${anime.title} S$seasonNum" else anime.title
                    url = if (anime.url.contains("?")) "${anime.url}&s=$seasonNum" else "${anime.url}?s=$seasonNum"
                    thumbnail_url = anime.thumbnail_url
                    initialized = true
                }
            }
        } catch (e: Exception) {
            listOf(anime)
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val seasonNum = response.request.url.queryParameter("s") ?: "1"
        return SAnime.create().apply {
            val info = document.selectFirst("div.serie-info")
            val baseTitle = info?.selectFirst("h1")?.text() ?: ""
            title = if (document.select("div.component.episode-card-grid").size > 1) "$baseTitle S$seasonNum" else baseTitle
            description = document.selectFirst("div.synopsis p")?.text()
            genre = document.select("div.metadata .item:contains(Genres) .value").text()
            status = parseStatus(document.select("div.metadata .item:contains(Statut) .value").text())
            author = document.select("div.metadata .item:contains(Studio) .value").text()
            thumbnail_url = baseUrl + document.selectFirst("div.poster img")?.attr("src")
        }
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("En cours", true) -> SAnime.ONGOING
        status.contains("Terminé", true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val seasonNum = response.request.url.queryParameter("s") ?: "1"
        val episodes = mutableListOf<SEpisode>()

        document.select("div.component.episode-card-grid[data-season=$seasonNum]").forEach { seasonGrid ->
            seasonGrid.select("div.component.episode-card").forEach { element ->
                episodes.add(
                    SEpisode.create().apply {
                        val link = element.selectFirst("a")!!.attr("href")
                        setUrlWithoutDomain(link)
                        val epName = element.selectFirst("h4")?.text() ?: ""
                        val epNum = element.selectFirst("h5")?.text() ?: ""
                        // Remove season prefix (e.g., "S1 E1" -> "E1")
                        val cleanEpNum = if (epNum.contains("E")) epNum.substringAfter("E").let { "E$it" } else epNum
                        name = if (cleanEpNum.isNotEmpty()) "$cleanEpNum - $epName" else epName
                        episode_number = epNum.substringAfter("E").toFloatOrNull() ?: 0f
                    },
                )
            }
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val html = document.toString()

        val episodeId = response.request.url.queryParameter("v")
            ?: response.request.url.encodedPathSegments.lastOrNull()
            ?: return emptyList()

        val playbackPath = Regex("""/playback/([^/]+)/master\.mpd""").find(html)?.value
            ?: return emptyList()

        val videoUrl = baseUrl + playbackPath

        // Fetch tracks (subtitles)
        val tracks = mutableListOf<Track>()
        try {
            val tracksResponse = client.newCall(GET("$baseUrl/api/episodes/tracks?episodeId=$episodeId", headers)).execute()
            if (tracksResponse.isSuccessful) {
                val data = json.decodeFromString<TracksResponse>(tracksResponse.body.string())
                data.subtitles.forEach { (key, value) ->
                    if (value == 1) {
                        val label = when (key) {
                            "fr_full" -> "Français (Complets)"
                            "fr_forced" -> "Français (Forcés)"
                            else -> key
                        }
                        val suffix = key.replace("_", "-")
                        tracks.add(Track("$baseUrl/assets/subtitles/$episodeId-$suffix.ass", label))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Parse MPD to get qualities
        val videoList = mutableListOf<Video>()
        val prefix = "(DASH) WavePlayer - "

        try {
            val mpdResponse = client.newCall(GET(videoUrl, headers)).execute()
            val mpdBody = mpdResponse.body.string()

            val repRegex = Regex("""<Representation[^>]+width="(\d+)"[^>]+height="(\d+)"[^>]*>""")
            val qualities = repRegex.findAll(mpdBody).map {
                val h = it.groupValues[2].toInt()
                val w = it.groupValues[1].toInt()
                val label = when {
                    h >= 2160 || w >= 3840 -> "2160p (4K)"
                    h >= 1440 || w >= 2560 -> "1440p (2K)"
                    h >= 1080 || w >= 1920 -> "1080p"
                    h >= 720 || w >= 1280 -> "720p"
                    h >= 480 || w >= 854 -> "480p"
                    h >= 360 || w >= 640 -> "360p"
                    else -> "${h}p"
                }
                label
            }.distinct().toList()

            if (qualities.isEmpty()) {
                videoList.add(Video(videoUrl, cleanQuality("${prefix}DASH"), videoUrl, subtitleTracks = tracks))
            } else {
                qualities.forEach { label ->
                    videoList.add(Video(videoUrl, cleanQuality("$prefix$label"), videoUrl, subtitleTracks = tracks))
                }
            }
        } catch (e: Exception) {
            videoList.add(Video(videoUrl, cleanQuality("${prefix}DASH"), videoUrl, subtitleTracks = tracks))
        }

        return videoList
    }

    private fun cleanQuality(quality: String): String = quality.replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s"), "")
        .replace(Regex("\\s*\\(\\d+x\\d+\\)"), "")
        .replace(" - - ", " - ")
        .trim()
        .removeSuffix("-")
        .trim()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        val sortedList = this.sortedWith(
            compareByDescending {
                Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            },
        )

        if (quality == "Highest") return sortedList

        return sortedList.sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending {
                    Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }
}
