// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.frenchstream

import android.util.Log
import eu.kanade.tachiyomi.animeextension.fr.frenchstream.dto.CatalogDto
import eu.kanade.tachiyomi.animeextension.fr.frenchstream.dto.DetailsItemDto
import eu.kanade.tachiyomi.animeextension.fr.frenchstream.dto.EpisodeUrlDto
import eu.kanade.tachiyomi.animeextension.fr.frenchstream.dto.MovieDto
import eu.kanade.tachiyomi.animeextension.fr.frenchstream.dto.SeriesDataDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.FRENCHSTREAM_LOG
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.Source
import fr.bluecxt.core.tmdb.TmdbMetadata
import fr.bluecxt.core.tmdb.fetchTmdbMetadataById
import fr.bluecxt.core.tvdb.fetchTvdbMetadata
import fr.bluecxt.core.utils.JsoupExtensions
import fr.bluecxt.core.utils.runCatchingCancelable
import fr.bluecxt.core.utils.safeRelativePath
import keiyoushi.utils.get
import keiyoushi.utils.parallelFlatMap
import keiyoushi.utils.parallelMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl

const val MOVIE_EP_NUMBER = -1

class FrenchStream :
    Source(),
    CommonPreferences,
    JsoupExtensions {

    override val name = "French Stream"
    override val defaultBaseUrl = "https://french-stream.one"

    override val supportedServers = listOf(
        "Vidzy",
        "Uqload",
        "Filemoon",
        "Vidara",
        "Voe",
        "Dood",
        "Lulu",
        "FSVid",
        "Kakaflix",
        "Kokoflix",
    )
    override val supportedVoices: Array<String> = arrayOf("VOSTFR", "VF", "VQF", "VO")
    override val lang = "fr"
    override val supportsLatest = true

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/index.php?newsid=${anime.url}"

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage = CatalogDto.from(client.get("$baseUrl/index.php?cstart=$page", headers).useAsJsoup())

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage = CatalogDto.from(client.get("$baseUrl/index.php?do=cat&category=s-tv&cstart=$page", headers).useAsJsoup())

    // ============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith(PREFIX_SEARCH)) {
            val mediaId = query.removePrefix(PREFIX_SEARCH).trim()
            val anime = SAnime.create().apply {
                url = mediaId
            }
            return AnimesPage(listOf(getAnimeDetails(anime)), false)
        }
        val url = "$baseUrl/engine/ajax/search.php"
        val formBody = FormBody.Builder().apply {
            add("query", query)
            add("page", page.toString())
        }.build()
        return CatalogDto.from(client.post(url, headers, formBody).useAsJsoup(), isSearch = true)
    }

    // ============================== Anime Details ===============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = DetailsItemDto.from(client.get("$baseUrl/index.php?newsid=${anime.url}").useAsJsoup()).populate(anime)

    // ============================== Episodes ===============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = coroutineScope {
        val mediaId = anime.url

        val movieDeferred = async {
            runCatchingCancelable {
                val movieDto: MovieDto = client.get("$baseUrl/engine/ajax/film_api.php?id=$mediaId", headers).parseAs()
                if (movieDto.error != null) return@async null
                movieDto
            }
        }
        val seriesDeferred = async {
            runCatchingCancelable {
                val seriesDto: SeriesDataDto = client.get("$baseUrl/static/series/$mediaId.js", headers).parseAs()
                if (seriesDto.allEpisodes.isEmpty()) return@async null
                seriesDto
            }
        }

        val seriesEpisodes = seriesDeferred.await()
        val movieEpisodes = movieDeferred.await()

        Log.d(FRENCHSTREAM_LOG, "incompleteEpisodeData = ${seriesEpisodes?.incompleteEpisodeData}")
        if (seriesEpisodes?.incompleteEpisodeData ?: false) {
            fillingMissingData(
                seriesEpisodes.toEpisodeList(mediaId),
                movieEpisodes?.meta?.tmdbId,
                anime.title,
            )?.run { return@coroutineScope this }
        }

        seriesEpisodes?.toEpisodeList(mediaId)
            ?: movieEpisodes?.toEpisodeList(mediaId)
            ?: emptyList()
    }

    private suspend fun fillingMissingData(episodes: List<SEpisode>, tmdbId: String?, animeName: String): List<SEpisode>? {
        val extractedId = tmdbId?.substringAfter("-")?.toIntOrNull() ?: return null
        val rawType = tmdbId.substringBefore("-")
        val type = if (rawType == "f") "movie" else "tv"
        val season = animeName.substringAfter("Saison").filter { it.isDigit() }.toIntOrNull() ?: 1

        val tmdbMetadata: TmdbMetadata = fetchTmdbMetadataById(extractedId, type, season, "fr-FR") ?: return null
        return episodes.map { episode ->
            val epNumber = episode.episode_number.toInt()
            val episodeMetaData = tmdbMetadata.episodeSummaries[epNumber] ?: return@map episode
            val metaName = episodeMetaData.first
            val metaThumb = episodeMetaData.second
            val metaSummary = episodeMetaData.third
            episode.apply {
                if (!name.contains("-")) {
                    name = buildString {
                        append("Épisode $epNumber")
                        if (metaName != null) append(" - $metaName")
                    }
                }
                preview_url = preview_url ?: metaThumb
                summary = summary ?: metaSummary
            }
            episode
        }
    }

    // ============================== Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val episodeDto: EpisodeUrlDto = episode.url.parseAs()
        val isMovie = episodeDto.epNum == MOVIE_EP_NUMBER.toString()

        return if (isMovie) {
            val movieDto: MovieDto = client.get("$baseUrl/engine/ajax/film_api.php?id=${episodeDto.mediaId}", headers).parseAs()
            movieDto.toHosterList(episodeDto.mediaId, episodeDto.epNum, episodeDto.langs)
        } else {
            val seriesDto: SeriesDataDto = client.get("$baseUrl/static/series/${episodeDto.mediaId}.js", headers).parseAs()
            seriesDto.toHosterList(episodeDto.mediaId, episodeDto.epNum, episodeDto.langs)
        }
    }

    // ============================== Videos ===============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val links: List<String> = hoster.hosterUrl.parseAs()

        return links.parallelFlatMap { link ->
            extractVideos(link, hoster.hosterName, supportedServers)
        }
    }
    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
