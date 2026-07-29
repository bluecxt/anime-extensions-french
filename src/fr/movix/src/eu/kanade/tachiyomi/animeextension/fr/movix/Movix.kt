package eu.kanade.tachiyomi.animeextension.fr.movix

import android.util.Log
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.CpasmalRes
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixDramaResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixFstreamResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixImdbResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixMovieLinksResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixPurstreamResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixTmdbResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixTvLinksResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixWiflixResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.TmdbDetailResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.TmdbMainResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.TmdbResult
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.TmdbSeasonDetail
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parallelMap
import fr.bluecxt.core.MOVIX_LOG

import keiyoushi.core.BuildConfig
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class Movix : BaseMovix("Movix") {

    // ========================= TMDB CONSTANTS =========================

    private val tmdbBase = "https://api.themoviedb.org/3"
    private val tmdbKey = BuildConfig.TMDB_API
    private val tmdbLang = "fr-FR"
    private val tmdbImg500 = "https://image.tmdb.org/t/p/w500"

    // ========================= POPULAR =========================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        // Films populaires en salle
        val moviesUrl = "$tmdbBase/movie/now_playing?api_key=$tmdbKey&language=$tmdbLang&page=$page"
        val moviesRes = client.newCall(GET(moviesUrl)).awaitSuccess()
        val movies = json.decodeFromString<TmdbMainResponse>(moviesRes.body.string())

        // Séries diffusées en ce moment
        val tvUrl = "$tmdbBase/tv/on_the_air?api_key=$tmdbKey&language=$tmdbLang&page=$page"
        val tvRes = client.newCall(GET(tvUrl)).awaitSuccess()
        val tvShows = json.decodeFromString<TmdbMainResponse>(tvRes.body.string())

        val animes = (
            movies.results.map { it.toSAnime("movie") } +
                tvShows.results.map { it.toSAnime("tv") }
            ).filterNotNull()

        return AnimesPage(animes, movies.totalPages?.let { page < it } ?: false)
    }

    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    // ========================= LATEST =========================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        // Films récents (sortie FR)
        val moviesUrl = "$tmdbBase/discover/movie?api_key=$tmdbKey&language=$tmdbLang" +
            "&sort_by=release_date.desc&watch_region=FR&page=$page&include_adult=false"
        val moviesRes = client.newCall(GET(moviesUrl)).awaitSuccess()
        val movies = json.decodeFromString<TmdbMainResponse>(moviesRes.body.string())

        // Séries récentes
        val tvUrl = "$tmdbBase/discover/tv?api_key=$tmdbKey&language=$tmdbLang" +
            "&sort_by=first_air_date.desc&watch_region=FR&page=$page"
        val tvRes = client.newCall(GET(tvUrl)).awaitSuccess()
        val tvShows = json.decodeFromString<TmdbMainResponse>(tvRes.body.string())

        val animes = (
            movies.results.map { it.toSAnime("movie") } +
                tvShows.results.map { it.toSAnime("tv") }
            ).filterNotNull()

        return AnimesPage(animes, movies.totalPages?.let { page < it } ?: false)
    }

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    // ========================= SEARCH =========================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return AnimesPage(emptyList(), false)

        if (trimmed.startsWith(PREFIX_SEARCH)) {
            val id = trimmed.removePrefix(PREFIX_SEARCH).trim()
            val type = if (id.startsWith("movie")) "movie" else "tv"
            val tmdbId = id.substringAfterLast("/")
            val url = "$tmdbBase/$type/$tmdbId?api_key=$tmdbKey&language=$tmdbLang"
            val res = client.newCall(GET(url)).awaitSuccess()
            val detail = json.decodeFromString<TmdbDetailResponse>(res.body.string())
            val anime = detail.toSAnime(type) ?: return AnimesPage(emptyList(), false)
            return AnimesPage(listOf(anime), false)
        }

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val url = "$tmdbBase/search/multi?api_key=$tmdbKey&query=$encoded&language=$tmdbLang&page=$page"
        val res = client.newCall(GET(url)).awaitSuccess()
        val data = json.decodeFromString<TmdbMainResponse>(res.body.string())

        val animes = data.results.mapNotNull { result ->
            val type = result.mediaType ?: return@mapNotNull null
            if (type == "person") return@mapNotNull null
            result.toSAnime(type)
        }

        return AnimesPage(animes, data.totalPages?.let { page < it } ?: false)
    }

    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw UnsupportedOperationException()

    // ========================= URL =========================

    override fun getAnimeUrl(anime: SAnime): String {
        val cleanUrl = anime.url.substringBefore("#")
        return "$baseUrl/$cleanUrl"
    }

    // ========================= DETAILS =========================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (type, tmdbId) = parseAnimeUrl(anime.url)
        val url = "$tmdbBase/$type/$tmdbId?api_key=$tmdbKey&language=$tmdbLang"
        val res = client.newCall(GET(url)).awaitSuccess()
        val detail = json.decodeFromString<TmdbDetailResponse>(res.body.string())
        anime.description = buildString {
            val date = (detail.releaseDate ?: detail.firstAirDate)?.split("-")?.first()
            if (!date.isNullOrBlank()) append("Date de sortie : $date\n\n")
            if (!detail.overview.isNullOrBlank()) append(detail.overview)
        }
        anime.thumbnail_url = detail.posterPath?.let { "$tmdbImg500$it" } ?: anime.thumbnail_url
        anime.genre = detail.genres?.mapNotNull { it.name }?.joinToString(", ")
        anime.author = detail.credits?.cast?.take(3)?.mapNotNull { it.name }?.joinToString(", ")
        anime.status = when (detail.status) {
            "Returning Series", "In Production", "Planned" -> SAnime.ONGOING
            "Canceled", "Ended" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        if (type == "tv") {
            val seasonsCount = detail.seasons?.count { (it.seasonNumber ?: 0) > 0 } ?: 0
            if (seasonsCount > 1) {
                anime.coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Seasons)
            } else {
                anime.coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
            }
        }

        anime.initialized = true
        return anime
    }

    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()

    // ========================= SEASONS =========================

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val (type, tmdbId) = parseAnimeUrl(anime.url)
        if (type != "tv") return emptyList()

        val url = "$tmdbBase/$type/$tmdbId?api_key=$tmdbKey&language=$tmdbLang"
        val res = client.newCall(GET(url)).awaitSuccess()
        val detail = json.decodeFromString<TmdbDetailResponse>(res.body.string())

        val seasons = detail.seasons?.filter { (it.seasonNumber ?: 0) > 0 } ?: return emptyList()

        return seasons.mapIndexed { index, season ->
            val sn = season.seasonNumber!!
            val seasonTitle = if (sn > 1) {
                "${anime.title} Saison $sn"
            } else {
                anime.title
            }

            SAnime.create().apply {
                title = seasonTitle
                this.url = "tv/$tmdbId#s$sn"
                thumbnail_url = season.posterPath?.let { "$tmdbImg500$it" } ?: anime.thumbnail_url
                description = anime.description
                status = if (index < seasons.size - 1) SAnime.COMPLETED else anime.status

                coreOptimizeDisplayTitle(this.title, anime.title)
                coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
                coreSetSeasonNumber(-2.0)
                initialized = true
            }
        }
    }

    // ========================= EPISODES =========================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (type, tmdbId) = parseAnimeUrl(anime.url)

        if (type == "movie") {
            return listOf(
                SEpisode.create().apply {
                    name = "[Movie] ${anime.title}"
                    episode_number = 1f
                    url = anime.url
                },
            )
        }

        val specificSeasonStr = anime.url.substringAfter("#s", "").substringBefore("|")
        val specificSeason = specificSeasonStr.toIntOrNull()

        // Séries : fetch saisons depuis TMDB
        val tmdbUrl = "$tmdbBase/$type/$tmdbId?api_key=$tmdbKey&language=$tmdbLang"
        val res = client.newCall(GET(tmdbUrl)).awaitSuccess()
        val detail = json.decodeFromString<TmdbDetailResponse>(res.body.string())

        var seasons = detail.seasons?.filter { (it.seasonNumber ?: 0) > 0 } ?: return emptyList()

        if (specificSeason != null) {
            seasons = seasons.filter { it.seasonNumber == specificSeason }
        }

        return seasons.flatMap { season ->
            val sn = season.seasonNumber ?: return@flatMap emptyList()
            val seasonUrl = "$tmdbBase/$type/$tmdbId/season/$sn?api_key=$tmdbKey&language=$tmdbLang"
            try {
                val seasonRes = client.newCall(GET(seasonUrl)).awaitSuccess()
                val seasonDetail = json.decodeFromString<TmdbSeasonDetail>(seasonRes.body.string())
                seasonDetail.episodes?.map { ep ->
                    val en = ep.episodeNumber ?: 0
                    SEpisode.create().apply {
                        name = buildString {
                            if (sn > 1) append("[S$sn] ")

                            append("Épisode $en")

                            val hasTitle = !ep.name.isNullOrBlank() && !ep.name.matches(Regex("(?i)Episode \\d+"))
                            if (hasTitle) {
                                append(" - ")
                                append(ep.name)
                            }
                        }
                        episode_number = (en).toFloat()
                        scanlator = "Saison $sn"
                        url = "tv/$tmdbId-$sn-$en"
                        preview_url = ep.stillPath?.let { "$tmdbImg500$it" }
                        summary = ep.overview
                    }
                } ?: emptyList()
            } catch (e: Exception) {
                Log.d(MOVIX_LOG, "Season fetch error S$sn: ${e.message}")
                emptyList()
            }
        }.asReversed()
    }

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    // ========================= HOSTERS =========================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val (type, tmdbId) = parseAnimeUrl(episode.url)
        val parts = episode.url.split("-")
        val season = parts.getOrNull(1)
        val ep = parts.getOrNull(2)
        val query = if (type == "tv" && season != null && ep != null) "?season=$season&episode=$ep" else ""

        val endpoints = buildList {
            add(Hoster(hosterName = "Movix", internalData = "links/$type/$tmdbId$query|$type|$tmdbId|$season|$ep"))
            add(Hoster(hosterName = "MovixTmdb", internalData = "tmdb/$type/$tmdbId$query|$type|$tmdbId|$season|$ep"))
            add(Hoster(hosterName = "IMDB", internalData = "imdb/$type/$tmdbId|$type|$tmdbId|$season|$ep"))
            if (type == "movie") {
                add(Hoster(hosterName = "FStream", internalData = "fstream/$type/$tmdbId|$type|$tmdbId|$season|$ep"))
                add(Hoster(hosterName = "Wiflix", internalData = "wiflix/$type/$tmdbId|$type|$tmdbId|$season|$ep"))
                add(Hoster(hosterName = "Cpasmal", internalData = "cpasmal/$type/$tmdbId|$type|$tmdbId|$season|$ep"))
            } else {
                add(Hoster(hosterName = "FStream", internalData = "fstream/$type/$tmdbId/season/$season|$type|$tmdbId|$season|$ep"))
                add(Hoster(hosterName = "Wiflix", internalData = "wiflix/$type/$tmdbId/$season|$type|$tmdbId|$season|$ep"))
                add(Hoster(hosterName = "Cpasmal", internalData = "cpasmal/$type/$tmdbId/$season/$ep|$type|$tmdbId|$season|$ep"))
                add(Hoster(hosterName = "Drama", internalData = "drama/$type/$tmdbId$query|$type|$tmdbId|$season|$ep"))
            }
            add(
                Hoster(
                    hosterName = "Purstream",
                    internalData = if (type == "movie") {
                        "purstream/movie/$tmdbId/stream|$type|$tmdbId|$season|$ep"
                    } else {
                        "purstream/tv/$tmdbId/stream$query|$type|$tmdbId|$season|$ep"
                    },
                ),
            )
        }

        return endpoints
    }

    override fun hosterListParse(response: Response) = throw UnsupportedOperationException()

    // ========================= VIDEOS =========================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.internalData.split("|")
        val apiPath = parts[0]
        val targetUrl = "$apiUrl/api/$apiPath"
        val type = parts.getOrNull(1) ?: "movie"
        val episode = parts.getOrNull(4)
        val brand = hoster.hosterName

        Log.d(MOVIX_LOG, "[$brand] → $targetUrl")

        return try {
            val res = client.newCall(
                Request.Builder()
                    .url(targetUrl)
                    .headers(apiHeaders)
                    .build(),
            ).awaitSuccess()

            var responseText = res.body.string()

            if (res.code in 301..302) {
                val location = res.header("location")
                if (!location.isNullOrBlank()) {
                    responseText = client.newCall(
                        Request.Builder().url(location).headers(apiHeaders).build(),
                    ).awaitSuccess().body.string()
                }
            }

            if (!isValidResponse(responseText)) {
                Log.d(MOVIX_LOG, "[$brand] Réponse invalide")
                return emptyList()
            }

            val links = extractLinks(brand, responseText, type, episode)
            Log.d(MOVIX_LOG, "[$brand] ${links.size} liens trouvés")

            links.parallelMap { link ->
                try {
                    extractVideos(link, lang = null, allowedServers = supportedServers)
                } catch (e: Exception) {
                    Log.d(MOVIX_LOG, "[$brand] Erreur extraction $link: ${e.message}")
                    emptyList()
                }
            }.flatten().distinctBy { it.videoUrl }
        } catch (e: Exception) {
            Log.d(MOVIX_LOG, "[$brand] Erreur: ${e.message}")
            emptyList()
        }
    }

    override fun videoListParse(response: Response, hoster: Hoster) = throw UnsupportedOperationException()

    // ========================= HELPERS =========================

    private fun parseAnimeUrl(url: String): Pair<String, String> {
        val type = if (url.startsWith("movie")) "movie" else "tv"
        val cleanUrl = url.substringBefore("#")
        val tmdbId = cleanUrl.substringAfter("/").substringBefore("-")
        return type to tmdbId
    }

    private fun TmdbResult.toSAnime(type: String): SAnime? {
        val titleText = title ?: name ?: originalTitle ?: originalName ?: return null
        val poster = (posterPath ?: backdropPath)?.let { "$tmdbImg500$it" }
        return SAnime.create().apply {
            this.title = titleText
            thumbnail_url = poster
            url = "$type/$id"
            initialized = false
        }
    }

    private fun TmdbDetailResponse.toSAnime(type: String): SAnime? {
        val titleText = title ?: name ?: originalTitle ?: originalName ?: return null
        return SAnime.create().apply {
            this.title = titleText
            thumbnail_url = posterPath?.let { "$tmdbImg500$it" }
            url = "$type/$id"
            initialized = false
        }
    }
}
