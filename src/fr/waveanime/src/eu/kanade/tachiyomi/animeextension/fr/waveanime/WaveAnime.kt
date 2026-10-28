// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.waveanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.Source
import fr.bluecxt.core.extractors.WaveplayerExtractor
import fr.bluecxt.core.tmdb.fetchTmdbMetadata
import fr.bluecxt.core.utils.runCatchingCancellable
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable

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
    data class WaveSerieSummary(
        val id: String,
        val title: String,
        val format: String? = null,
        val release_year: Int? = null,
    )

    @Serializable
    data class WaveGenre(val id: Int, val name: String)

    @Serializable
    data class WaveSerieInfo(
        val id: String,
        val title_jpn: String? = null,
        val title_eng: String? = null,
        val title_fra: String? = null,
        val format: String? = null,
    )

    @Serializable
    data class WaveEpisode(
        val id: String,
        val title: String? = null,
        val number: Int,
        val season_number: Int = 1,
        val has_thumbnail: Int = 0,
    )

    @Serializable
    data class WaveSeason(
        val id: String,
        val number: Int,
        val title: String? = null,
        val synopsis: String? = null,
        val episodes: List<WaveEpisode> = emptyList(),
    )

    @Serializable
    data class WaveSerieDetails(
        val serie: WaveSerieInfo,
        val genres: List<WaveGenre> = emptyList(),
        val themes: List<WaveGenre> = emptyList(),
        val seasons: List<WaveSeason> = emptyList(),
        val episodes: List<WaveEpisode> = emptyList(),
    )

    @Serializable
    data class WaveEpisodeTrack(
        val episode_id: String,
        val type: String,
        val language: String,
        val flags: Int = 0,
    )

    @Serializable
    data class WaveEpisodeResponse(
        val episode: WaveEpisode,
        val tracks: List<WaveEpisodeTrack> = emptyList(),
    )

    // =============================== Popular & Latest ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val series = client.get("$baseUrl/api/series", headers).parseAs<List<WaveSerieSummary>>(json)
        val animes = series.map { s ->
            SAnime.create().apply {
                url = "/series/${s.id}"
                title = s.title
                thumbnail_url = "$baseUrl/media/covers/${s.id}-small.jpeg"
            }
        }
        return AnimesPage(animes, false)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val series = client.get("$baseUrl/api/series", headers).parseAs<List<WaveSerieSummary>>(json)
        val trimmed = query.trim()
        val filtered = if (trimmed.isNotBlank()) {
            series.filter { it.title.contains(trimmed, ignoreCase = true) }
        } else {
            series
        }
        val animes = filtered.map { s ->
            SAnime.create().apply {
                url = "/series/${s.id}"
                title = s.title
                thumbnail_url = "$baseUrl/media/covers/${s.id}-small.jpeg"
            }
        }
        return AnimesPage(animes, false)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val id = anime.url.trim('/').substringAfterLast("/")
        val details = client.get("$baseUrl/api/series/$id", headers).parseAs<WaveSerieDetails>(json)

        val mainTitle = details.serie.title_fra
            ?: details.serie.title_jpn
            ?: details.serie.title_eng
            ?: anime.title

        anime.title = mainTitle
        anime.description = details.seasons.firstOrNull()?.synopsis ?: ""
        anime.genre = (details.genres + details.themes).joinToString { it.name }
        anime.status = SAnime.COMPLETED
        anime.thumbnail_url = "$baseUrl/media/covers/$id-small.jpeg"

        // TMDB Enrichment
        val tmdbType = tmdbTypeFromFormat(details.serie.format)
        val tmdbMetadata = fetchTmdbMetadata(mainTitle, type = tmdbType)
        tmdbMetadata?.summary?.let { anime.description = it }
        tmdbMetadata?.releaseDate?.let { date ->
            anime.description = "Date de sortie : $date\n\n${anime.description ?: ""}"
        }
        tmdbMetadata?.mainPosterUrl?.let { anime.thumbnail_url = it }
        tmdbMetadata?.author?.let { anime.author = it }
        tmdbMetadata?.artist?.let { anime.artist = it }
        tmdbMetadata?.status?.let { anime.status = it }

        return anime
    }

    private fun tmdbTypeFromFormat(format: String?): String? = when (format?.uppercase()) {
        "FILM" -> "movie"
        "SERIE", "TV", "ONA", "OAV", "SPECIAL" -> "tv"
        else -> null
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.trim('/').substringAfterLast("/")
        val details = client.get("$baseUrl/api/series/$id", headers).parseAs<WaveSerieDetails>(json)

        val episodes = mutableListOf<SEpisode>()
        val multipleSeasons = details.seasons.size > 1

        details.episodes.forEach { ep ->
            val sPrefix = if (multipleSeasons) "[S${ep.season_number}] " else ""
            val titlePart = if (!ep.title.isNullOrBlank()) " - ${ep.title}" else ""
            episodes.add(
                SEpisode.create().apply {
                    url = ep.id
                    name = "${sPrefix}Épisode ${ep.number}$titlePart"
                    episode_number = ep.number.toFloat()
                    scanlator = "Saison ${ep.season_number}"
                },
            )
        }

        if (episodes.size == 1 && details.serie.format.equals("film", ignoreCase = true)) {
            episodes[0].name = "Film"
        }

        return episodes.asReversed()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterName = "WavePlayer (DASH)", internalData = episode.url))

    private val waveplayerExtractor by lazy { WaveplayerExtractor(client, headers) }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val episodeId = hoster.internalData.trim('/').substringAfterLast("/").substringBefore(".mpd")
        val masterUrl = "$baseUrl/playback/$episodeId/manifest.mpd"

        val tracks = fetchSubtitles(episodeId)
        val rawSources = waveplayerExtractor.videosFromUrl(masterUrl, "$baseUrl/watch/$episodeId", tracks)

        return rawSources.map { source ->
            source.buildFromSource(lang = null, name = "(DASH) WavePlayer")
        }
    }

    private suspend fun fetchSubtitles(episodeId: String): List<Track> = runCatchingCancellable {
        val data = client.get("$baseUrl/api/episodes/$episodeId", headers).parseAs<WaveEpisodeResponse>(json)

        data.tracks.filter { it.type == "subtitle" }.map { track ->
            val flagSuffix = if (track.flags == 1) "forced" else "full"
            val trackUrl = "$baseUrl/playback/subtitles/${track.episode_id}-${track.language}-$flagSuffix.ass"
            val label = if (track.language == "fra") "Français (${if (track.flags == 1) "Forcés" else "Complets"})" else track.language
            Track(trackUrl, label)
        }
    }.getOrDefault(emptyList())
}
