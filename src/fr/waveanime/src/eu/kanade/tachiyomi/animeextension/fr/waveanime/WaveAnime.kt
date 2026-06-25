package eu.kanade.tachiyomi.animeextension.fr.waveanime

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
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.Source
import fr.bluecxt.core.TmdbMetadata
import fr.bluecxt.core.extractors.WaveplayerExtractor
import fr.bluecxt.core.fetchTmdbMetadata
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Element

class WaveAnime :
    Source(),
    CommonPreferences {

    override val name = "WaveAnime"

    // CONFIGURATION
    override val defaultBaseUrl = "https://waveanime.fr"
    override val supportedServers = listOf("WavePlayer")
    override val forceShowQualityPreference = true
    override val supportedQualities = arrayOf("Highest", "1440", "1080", "720", "480", "360")

    override val lang = "fr"

    override val supportsLatest = true

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

    private fun Element.safeUrl(): String {
        val url = this.attr("abs:href").toHttpUrlOrNull() ?: return ""
        val query = url.encodedQuery
        return if (query == null) url.encodedPath else "${url.encodedPath}?$query"
    }

    private fun parseAnimePage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("div.component.serie-card").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            SAnime.create().apply {
                url = link.safeUrl()
                title = element.attr("title").ifBlank { "Titre inconnu" }
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }.distinctBy { it.url }

        return AnimesPage(items, false)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl")).execute()
        return parseAnimePage(response)
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

        // Map site format to TMDB types
        val tmdbType = tmdbTypeFromFormat(format)

        // TMDB Metadata with type precision
        val tmdbMetadata = fetchTmdbMetadata(mainTitle, type = tmdbType)
        tmdbMetadata?.summary?.let { anime.description = it }

        // Prepend release date to description
        tmdbMetadata?.releaseDate?.let { date ->
            anime.description = "Date de sortie : $date\n\n${anime.description ?: ""}"
        }

        tmdbMetadata?.posterUrl?.let { anime.thumbnail_url = it }
        tmdbMetadata?.author?.let { anime.author = it }
        tmdbMetadata?.artist?.let { anime.artist = it }
        tmdbMetadata?.status?.let { anime.status = it }

        return anime
    }

    private fun tmdbTypeFromFormat(format: String?): String? = when (format?.uppercase()) {
        "FILM" -> "movie"
        "TV", "ONA", "OAV", "SPECIAL" -> "tv"
        else -> null
    }

    private suspend fun fetchTmdbMetadataWithType(
        title: String,
        season: Int = 1,
        format: String?,
    ): TmdbMetadata? = fetchTmdbMetadata(title, season, type = tmdbTypeFromFormat(format))

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET(baseUrl + anime.url, headers)).execute()
        val document = response.asJsoup()
        val format = document.selectFirst("div.row:contains(Format)>span.value")?.text()
        val episodes = mutableListOf<SEpisode>()
        val seasonGrids = document.select("div.component.episode-card-grid")

        seasonGrids.forEach { seasonGrid ->
            val seasonNumStr = seasonGrid.attr("data-season")
            val seasonNum = seasonNumStr.toIntOrNull() ?: 1
            val tmdbMetadata = fetchTmdbMetadataWithType(anime.title, seasonNum, format)

            seasonGrid.select("div.component.episode-card").forEach { element ->
                val fullTitle = element.attr("title").ifBlank { return@forEach }
                // Exemple: "S1 E2 - Sans nom n°2" ou "E3 - Titre"
                val epMatch = Regex("""E(\d+(?:\.\d+)?)""").find(fullTitle)
                val epActualNumStr = epMatch?.groupValues?.get(1) ?: "0"
                val epActualNum = epActualNumStr.toIntOrNull() ?: 0
                val epName = fullTitle.substringAfter("-").trim()

                episodes.add(
                    SEpisode.create().apply {
                        val link = element.selectFirst("a") ?: return@forEach
                        url = link.safeUrl()

                        // Metadata from TMDB
                        val epMeta = tmdbMetadata?.episodeSummaries?.get(epActualNum)
                        val tmdbName = epMeta?.first

                        // GEMINI.md Rules: [S1] Episode Y - [Titre]
                        val sPrefix = if (seasonGrids.size > 1) "[S$seasonNum] " else ""
                        val baseName = "Episode $epActualNumStr" + if (epName.contains("Sans nom", true) || epName.isBlank()) {
                            if (tmdbName != null) " - $tmdbName" else ""
                        } else {
                            " - $epName"
                        }
                        name = "$sPrefix$baseName"

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

    private val waveplayerExtractor by lazy { WaveplayerExtractor(client, headers) }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val episodeUrl = hoster.internalData
        val response = client.newCall(GET(baseUrl + episodeUrl, headers)).execute()
        val document = response.asJsoup()
        val html = document.toString()

        val episodeId = episodeUrl.substringAfter("?v=").substringBefore("&")
            .takeIf { it != episodeUrl } ?: episodeUrl.substringAfterLast("/")

        val playbackPath = Regex("""/playback/([^/]+)/master\.mpd""").find(html)?.value
            ?: return emptyList()

        val masterUrl = baseUrl + playbackPath

        // Fetch tracks (subtitles) via API WaveAnime
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

        // Utilise l'extracteur du Core (maintenant simplifié)
        val rawSources = waveplayerExtractor.videosFromUrl(masterUrl, baseUrl + episodeUrl, tracks)

        return rawSources.map { source ->
            source.buildFromSource(lang = null, name = "(DASH) WavePlayer")
        }
    }

    override fun List<Video>.sortVideos(): List<Video> = this
}
