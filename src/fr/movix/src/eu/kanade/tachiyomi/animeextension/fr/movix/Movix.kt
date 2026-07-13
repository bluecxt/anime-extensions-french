package eu.kanade.tachiyomi.animeextension.fr.movix

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.CpasmalRes
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.ImdbSeries
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixDramaResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixFstreamResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixImdbResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixMovieLinksResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixPlayerLink
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixPurstreamResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixTmdbResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixTvLinksResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixWiflixResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.TmdbDetailResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.TmdbMainResponse
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
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.MOVIX_LOG
import fr.bluecxt.core.Source
import fr.bluecxt.core.withDefaultHeaders
import keiyoushi.core.BuildConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class Movix :
    Source(),
    CommonPreferences {

    override val name = "Movix"

    override val defaultBaseUrl = "https://movix.chat"

    override val supportedServers = listOf(
        "Sibnet", "Sendvid", "Vidmoly", "Filemoon", "Dood", "Streamtape",
        "Vidoza", "Voe", "Minochinos", "Embed4me", "Lulu", "Uqload",
        "Okru", "Mymail", "Vidara", "Streamix",
    )

    override val defaultServer = "Vidmoly"

    override val supportedVoices = arrayOf("VOSTFR", "VF", "VA")
    override val lang = "fr"

    override val supportsLatest = true

    override val json: Json by injectLazy()

    // ========================= URL DYNAMIQUE =========================

    private var dynamicBaseUrl: String? = null

    override val baseUrl: String
        get() {
            // Préférences toujours prioritaires sur le cache mémoire
            val prefUrl = currentBaseUrl
            if (!prefUrl.isNullOrEmpty() && prefUrl != "https://movix.online") {
                dynamicBaseUrl = prefUrl
                return prefUrl
            }
            // Cache mémoire si aucune préférence valide
            dynamicBaseUrl?.let { return it }
            // Auto-détection via movix.online
            return fetchAndSaveRealUrl()
        }

    override val baseUrlSummary: String
        get() = "Laissez vide pour trouver automatiquement le domaine actif via movix.online. Actuel: $baseUrl"

    private fun fetchAndSaveRealUrl(): String = try {
        val response = client.newCall(GET("https://movix.online/")).execute()
        val html = response.body.string()
        val regex = Regex("""La seule adresse active de Movix est <a href="(https://[^"]+)"""")
        val match = regex.find(html)
        if (match != null) {
            val domain = match.groupValues[1].removeSuffix("/")
            preferences.edit().putString(CommonPreferences.PREF_URL_KEY, domain).apply()
            dynamicBaseUrl = domain
            domain
        } else {
            defaultBaseUrl
        }
    } catch (e: Exception) {
        defaultBaseUrl
    }

    private val domain: String
        get() = baseUrl.toHttpUrl().host

    private val apiUrl: String
        get() = "https://api.$domain/api"

    // ========================= HEADERS =========================

    override fun headersBuilder() = super.headersBuilder()

    private val apiHeaders
        get() = headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("User-Agent", DEFAULT_USER_AGENT)
            .removeAll("Origin")
            .build()

    // ========================= TMDB CONSTANTS =========================

    private val tmdbBase = "https://api.themoviedb.org/3"
    private val tmdbKey = BuildConfig.TMDB_API
    private val tmdbLang = "fr-FR"
    private val tmdbImg500 = "https://image.tmdb.org/t/p/w500"

    companion object {
        const val PREFIX_SEARCH = "id:"
    }

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

    // ========================= DETAILS =========================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (type, tmdbId) = parseAnimeUrl(anime.url)
        val url = "$tmdbBase/$type/$tmdbId?api_key=$tmdbKey&language=$tmdbLang"
        val res = client.newCall(GET(url)).awaitSuccess()
        val detail = json.decodeFromString<TmdbDetailResponse>(res.body.string())

        anime.title = detail.title ?: detail.name ?: detail.originalTitle ?: detail.originalName ?: anime.title
        anime.description = buildString {
            val date = (detail.releaseDate ?: detail.firstAirDate)?.split("-")?.first()
            if (!date.isNullOrBlank()) appendLine("📅 $date\n")
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
        anime.initialized = true
        return anime
    }

    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()

    // ========================= EPISODES =========================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (type, tmdbId) = parseAnimeUrl(anime.url)

        if (type == "movie") {
            return listOf(
                SEpisode.create().apply {
                    name = anime.title
                    episode_number = 1f
                    url = anime.url
                },
            )
        }

        // Séries : fetch saisons depuis TMDB
        val tmdbUrl = "$tmdbBase/$type/$tmdbId?api_key=$tmdbKey&language=$tmdbLang"
        val res = client.newCall(GET(tmdbUrl)).awaitSuccess()
        val detail = json.decodeFromString<TmdbDetailResponse>(res.body.string())

        val seasons = detail.seasons?.filter { (it.seasonNumber ?: 0) > 0 } ?: return emptyList()

        return seasons.flatMap { season ->
            val sn = season.seasonNumber ?: return@flatMap emptyList()
            val seasonUrl = "$tmdbBase/$type/$tmdbId/season/$sn?api_key=$tmdbKey&language=$tmdbLang"
            try {
                val seasonRes = client.newCall(GET(seasonUrl)).awaitSuccess()
                val seasonDetail = json.decodeFromString<TmdbSeasonDetail>(seasonRes.body.string())
                seasonDetail.episodes?.map { ep ->
                    val en = ep.episodeNumber ?: 0
                    SEpisode.create().apply {
                        name = if (!ep.name.isNullOrBlank() && !ep.name.matches(Regex("Episode \\d+"))) {
                            "[S$sn] E$en - ${ep.name}"
                        } else {
                            "[S$sn] Episode $en"
                        }
                        episode_number = (sn * 1000 + en).toFloat()
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

        // On stocke uniquement le CHEMIN dans internalData pour que
        // getVideoList puisse reconstruire l'URL depuis l'apiUrl courant (toujours frais)
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
        val apiPath = parts[0] // chemin seulement, ex: "links/movie/12345"
        // L'URL est reconstruite depuis l'apiUrl courant (baseUrl toujours frais)
        val targetUrl = "$apiUrl/$apiPath"
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

            // Suivre redirect manuel si nécessaire
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

    private fun isValidResponse(response: String): Boolean {
        val lower = response.lowercase()
        return listOf("success", "player_links", "iframe_src", "series", "sources", "players", "links", "purstream_id", "wiflix")
            .any { lower.contains(it) }
    }

    private fun extractLinks(brand: String, response: String, type: String, episode: String?): List<String> {
        val links = mutableListOf<String>()
        val fixedResponse = response.replace("\"players\":", "\"links\":")

        when (brand) {
            "Movix" -> {
                if (type == "movie") {
                    json.decodeFromString<MovixMovieLinksResponse>(response).data?.links?.let(links::addAll)
                } else {
                    json.decodeFromString<MovixTvLinksResponse>(response).data?.forEach { data ->
                        data.links?.let(links::addAll)
                    }
                }
            }

            "MovixTmdb" -> {
                json.decodeFromString<MovixTmdbResponse>(response).let { res ->
                    res.playerLinks?.forEach { it.decodedUrl?.let(links::add) }
                    res.currentEpisode?.playerLinks?.forEach { it.decodedUrl?.let(links::add) }
                    res.iframeSrc?.let(links::add)
                    res.currentEpisode?.iframeSrc?.let(links::add)
                }
            }

            "IMDB" -> {
                json.decodeFromString<MovixImdbResponse>(response).series?.forEach { series ->
                    series.seasons?.forEach { season ->
                        season.episodes?.filter {
                            episode == null || it.number == episode || it.number?.toIntOrNull() == episode.toIntOrNull()
                        }?.forEach { ep ->
                            ep.versions?.values?.forEach { version ->
                                version.players?.forEach { it.link?.let(links::add) }
                            }
                        }
                    }
                }
            }

            "FStream" -> {
                json.decodeFromString<MovixFstreamResponse>(fixedResponse).let { res ->
                    if (type == "movie") {
                        res.links?.values?.flatten()?.forEach { it.url?.let(links::add) }
                    } else {
                        val target = res.episodes?.entries?.find {
                            it.key == episode || it.key.toIntOrNull() == episode?.toIntOrNull()
                        }?.value
                        target?.languages?.values?.flatten()?.forEach { it.url?.let(links::add) }
                    }
                }
            }

            "Wiflix" -> {
                json.decodeFromString<MovixWiflixResponse>(response).let { res ->
                    if (type == "movie") {
                        res.movie?.values?.flatten()?.forEach { it.url?.let(links::add) }
                    } else {
                        val epData = res.episodes?.entries?.find {
                            it.key == episode || it.key.toIntOrNull() == episode?.toIntOrNull()
                        }?.value
                        epData?.vf?.forEach { it.url?.let(links::add) }
                        epData?.vostfr?.forEach { it.url?.let(links::add) }
                    }
                }
            }

            "Cpasmal" -> {
                json.decodeFromString<CpasmalRes>(fixedResponse).links?.values?.flatten()
                    ?.forEach { it.url?.let(links::add) }
            }

            "Drama" -> {
                json.decodeFromString<MovixDramaResponse>(response).data
                    ?.forEach { it.link?.let(links::add) }
            }

            "Purstream" -> {
                json.decodeFromString<MovixPurstreamResponse>(response).sources?.forEach { source ->
                    source.url?.takeIf { it.isNotBlank() }?.let(links::add)
                }
            }
        }

        return links.distinct().filter { it.isNotBlank() }
    }

    private fun parseAnimeUrl(url: String): Pair<String, String> {
        // URL format: "movie/12345" ou "tv/12345-1-3"
        val type = if (url.startsWith("movie")) "movie" else "tv"
        val tmdbId = url.substringAfter("/").substringBefore("-")
        return type to tmdbId
    }

    // ========================= SANIME BUILDERS =========================

    private fun eu.kanade.tachiyomi.animeextension.fr.movix.dto.TmdbResult.toSAnime(type: String): SAnime? {
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
