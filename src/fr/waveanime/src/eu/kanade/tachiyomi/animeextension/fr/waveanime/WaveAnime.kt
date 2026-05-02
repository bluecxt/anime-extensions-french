package eu.kanade.tachiyomi.animeextension.fr.waveanime

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class WaveAnime : Source() {

    override val name = "WaveAnime"

    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!
    }

    override val lang = "fr"

    override val supportsLatest = true

    override val json: Json by injectLazy()

    companion object {
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://waveanime.fr"

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
                true
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
                true
            }
        }.also(screen::addPreference)
    }

    @Serializable
    data class TracksResponse(
        val audios: Map<String, Int?>,
        val subtitles: Map<String, Int?>,
    )

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/catalog")).execute()
        return parseAnimePage(response)
    }

    private fun parseAnimePage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("div.component.serie-card").map { element ->
            val link = element.selectFirst("a")!!
            SAnime.create().apply {
                setUrlWithoutDomain(link.attr("href"))
                title = element.attr("title")
                thumbnail_url = element.selectFirst("div.poster img")?.attr("abs:src")
            }
        }.sortedBy { it.title }

        return AnimesPage(items, false)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl")).execute()
        return parsePopularPage(response)
    }

    private fun parsePopularPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("div.component.serie-card").map { element ->
            val link = element.selectFirst("a")!!
            SAnime.create().apply {
                setUrlWithoutDomain(link.attr("href"))
                title = element.attr("title")
                thumbnail_url = element.selectFirst("div.poster img")?.attr("abs:src")
            }
        }.distinctBy { it.url }

        return AnimesPage(items, false)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = "$baseUrl/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimePage(response)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET(baseUrl + anime.url, headers)).execute()
        val document = response.asJsoup()

        val info = document.selectFirst("div.serie-info")
        val format = document.selectFirst("div.row:contains(Format)>span.value")?.text()
        val mainTitle = info?.selectFirst("h1")?.text() ?: anime.title
        val releaseDate = document.selectFirst("div.row:contains(Date de sortie)>span.value")?.text()

        anime.title = mainTitle
        anime.description = buildString {
            if (releaseDate != null) append("Date de sortie : $releaseDate\n\n")
            if (format != null) append("format $format\n")
            append(document.selectFirst(".serie-details > p")?.text() ?: "")
        }
        anime.genre = document.select(".genres span").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        anime.author = document.select("div.metadata .item:contains(Studio) .value").text()
        anime.thumbnail_url = document.selectFirst(".poster img")?.attr("abs:src")

        // TMDB Metadata
        val tmdbMetadata = fetchTmdbMetadata(mainTitle)
        tmdbMetadata?.summary?.let { anime.description = it }

        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET(baseUrl + anime.url, headers)).execute()
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        val seasonGrids = document.select("div.component.episode-card-grid")

        val tmdbMetadata = fetchTmdbMetadata(anime.title)

        seasonGrids.forEach { seasonGrid ->
            val seasonNumStr = seasonGrid.attr("data-season")

            seasonGrid.select("div.component.episode-card").forEach { element ->
                val epActualNumStr = element.selectFirst("h5")?.text()?.substringAfter("E")?.trim() ?: "0"
                val epActualNum = epActualNumStr.toIntOrNull() ?: 0
                val epName = element.selectFirst("h4")?.text() ?: ""

                episodes.add(
                    SEpisode.create().apply {
                        val link = element.selectFirst("a")!!.attr("href")
                        setUrlWithoutDomain(link)

                        // Metadata from TMDB
                        val epMeta = tmdbMetadata?.episodeSummaries?.get(epActualNum)
                        val tmdbName = epMeta?.first

                        // GEMINI.md Rules
                        name = "Episode $epActualNumStr" + if (epName.contains("Sans nom", true)) {
                            " - $tmdbName"
                        } else {
                            " - $epName"
                        }

                        episode_number = epActualNumStr.toFloatOrNull() ?: 0f
                        scanlator = "Season $seasonNumStr"

                        preview_url = epMeta?.second
                        summary = epMeta?.third
                    },
                )
            }
        }

        if (episodes.size == 1) {
            episodes[0].name = "Film"
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterName = "WavePlayer (DASH)", internalData = episode.url))

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val episodeUrl = hoster.internalData
        val response = client.newCall(GET(baseUrl + episodeUrl, headers)).execute()
        val document = response.asJsoup()
        val html = document.toString()

        val episodeId = episodeUrl.substringAfter("?v=").substringBefore("&")
            .takeIf { it != episodeUrl } ?: episodeUrl.substringAfterLast("/")

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
        } catch (_: Exception) {}

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
                videoList.add(Video(videoUrl = videoUrl, videoTitle = cleanQuality("${prefix}DASH"), subtitleTracks = tracks))
            } else {
                qualities.forEach { label ->
                    videoList.add(Video(videoUrl = videoUrl, videoTitle = cleanQuality("$prefix$label"), subtitleTracks = tracks))
                }
            }
        } catch (_: Exception) {
            videoList.add(Video(videoUrl = videoUrl, videoTitle = cleanQuality("${prefix}DASH"), subtitleTracks = tracks))
        }

        return videoList.sortVideos()
    }

    private val qualityCleanRegex = Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s")
    private val qualityResRegex = Regex("\\s*\\(\\d+x\\d+\\)")
    private val qualityExtraSpaceRegex = Regex("\\s+")
    private val qualityRegex = Regex("""(\d+)p""")

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(qualityCleanRegex, "")
            .replace(qualityResRegex, "")
            .replace(" - - ", " - ")
            .trim()
            .removeSuffix("-")
            .trim()

        cleaned = cleaned.replace(Regex("(?i)WavePlayer\\s*-\\s*WavePlayer", RegexOption.IGNORE_CASE), "WavePlayer")
        return cleaned.replace(qualityExtraSpaceRegex, " ").replace(" - - ", " - ").trim()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val sortedList = this.sortedWith(
            compareByDescending {
                qualityRegex.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            },
        )
        if (quality == "Highest") return sortedList
        return sortedList.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending {
                    qualityRegex.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }
}
