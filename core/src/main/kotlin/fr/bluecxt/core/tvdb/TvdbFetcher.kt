// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.tvdb

import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.post
import fr.bluecxt.core.TVDB_LOG
import fr.bluecxt.core.monitoring.ErrorWebhook
import fr.bluecxt.core.source.Source
import fr.bluecxt.core.tmdb.utils.sanitizeTitle
import fr.bluecxt.core.tvdb.dto.TvdbArtworkDto
import fr.bluecxt.core.tvdb.dto.TvdbAuthRequest
import fr.bluecxt.core.tvdb.dto.TvdbAuthResponse
import fr.bluecxt.core.tvdb.dto.TvdbEpisodeDto
import fr.bluecxt.core.tvdb.dto.TvdbEpisodesResponse
import fr.bluecxt.core.tvdb.dto.TvdbMovieExtendedData
import fr.bluecxt.core.tvdb.dto.TvdbMovieExtendedResponse
import fr.bluecxt.core.tvdb.dto.TvdbRemoteIdResponse
import fr.bluecxt.core.tvdb.dto.TvdbRemoteMedia
import fr.bluecxt.core.tvdb.dto.TvdbSearchResponse
import fr.bluecxt.core.tvdb.dto.TvdbSearchResult
import fr.bluecxt.core.tvdb.dto.TvdbSeasonExtendedResponse
import fr.bluecxt.core.tvdb.dto.TvdbSeriesExtendedData
import fr.bluecxt.core.tvdb.dto.TvdbSeriesExtendedResponse
import fr.bluecxt.core.tvdb.dto.TvdbTranslationResponse
import fr.bluecxt.core.utils.containsAny
import fr.bluecxt.core.utils.normalize
import fr.bluecxt.core.utils.runCatchingCancellable
import keiyoushi.core.BuildConfig
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Type d'artwork TVDB : poster de saison. */
private const val ARTWORK_TYPE_SEASON_POSTER = 7

/** Type d'artwork TVDB : bannière de saison. */
private const val ARTWORK_TYPE_SEASON_BANNER = 8

/** Longueur minimale d'un titre pour accepter un match partiel. */
private const val MIN_PARTIAL_MATCH_LENGTH = 3

/** Codes de langue TVDB utilisés pour départager les visuels. */
private const val LANGUAGE_JAPANESE = "jpn"
private const val LANGUAGE_ENGLISH = "eng"

/** Scores de ressemblance et ajustements par préférence de média. */
private const val SCORE_EXACT_TITLE = 100
private const val SCORE_CONTAINS_BASE = 85
private const val BONUS_ANIMATION_GENRE = 45
private const val PENALTY_NO_ANIMATION_GENRE = 60
private const val BONUS_ANIME_SLUG = 30
private const val PENALTY_LIVE_ACTION_SLUG = 40
private const val BONUS_JAPANESE_ORIGIN = 15
private const val PENALTY_WESTERN_ORIGIN = 25

enum class MediaPreference {
    ANIME,
    DRAMA,
    ANY,
}

data class TvdbSearchResultData(
    val id: String,
    val title: String?,
    val posterUrl: String?,
    val isMovie: Boolean,
    val overview: String?,
    val releaseDate: String?,
    val score: Int = 0,
    val status: String? = null,
)

enum class ExternalSourceId(val sourceName: String) {
    TMDB("TheMovieDB.com"),
    IMDB("IMDB"),
    EIDR("EIDR"),
}

private const val TVDB_BASE_URL = "https://api4.thetvdb.com/v4"
private const val TVDB_ARTWORK_BASE_URL = "https://artworks.thetvdb.com"
private const val DEFAULT_TVDB_API_KEY = BuildConfig.TVDB_API

private var tokenTimestamp: Long = 0L
private val tokenMutex by lazy { Mutex() }
private const val TOKEN_LIFETIME_MS = 28 * 24 * 3600 * 1000L // 28 Days

// In-Memory Metadata Cache (1 hour TTL)
private val tvdbMetadataCache = ConcurrentHashMap<String, Pair<TvdbMetadata, Long>>()
private val requestMutexes = ConcurrentHashMap<String, Mutex>()
private const val METADATA_CACHE_LIFETIME_MS = 3600 * 1000L

private fun ConcurrentHashMap<String, Pair<TvdbMetadata, Long>>.getCache(cacheKey: String): TvdbMetadata? {
    val cached = this[cacheKey]
    if (cached != null && System.currentTimeMillis() - cached.second < METADATA_CACHE_LIFETIME_MS) {
        Log.d(TVDB_LOG, "TVDB Cache hit for '$cacheKey'")
        return cached.first
    }
    return null
}

private val liveActionSlugRegex = Regex("""(?i)(?:^|-)(?:dorama|drama|live-action|j-drama|k-drama)(?:$|-|\d)""")
private val animeSlugRegex = Regex("""(?i)(?:^|-)(?:anime)(?:$|-|\d)""")

private const val TVDB_CACHE_TOKEN_KEY = "tvdb_jwt_token"

class TvdbFetcher(
    private val source: Source,
    private val lang: String = "fra",
    private val apiKey: String = DEFAULT_TVDB_API_KEY,
) {
    private val tvdbJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val preferences: SharedPreferences by source.getPreferencesLazy()

    private var cachedToken: String?
        get() = preferences.getString(TVDB_CACHE_TOKEN_KEY, null)
        set(value) {
            preferences.edit().putString(TVDB_CACHE_TOKEN_KEY, value).apply()
        }

    private fun sendWebhook(url: String, context: String, exception: Throwable?) = ErrorWebhook.sendWebhook(
        baseUrl = TVDB_BASE_URL,
        url = url,
        context = context,
        exception = exception,
        extensionName = source.currentName,
        extensionVersion = source.currentVersion,
    )

    private suspend fun getTvdbToken(forceRefresh: Boolean = false): String? = if (
        !forceRefresh && cachedToken != null &&
        System.currentTimeMillis() - tokenTimestamp < TOKEN_LIFETIME_MS
    ) {
        cachedToken
    } else {
        tokenMutex.withLock {
            if (!forceRefresh && cachedToken != null && System.currentTimeMillis() - tokenTimestamp < TOKEN_LIFETIME_MS) {
                return@withLock cachedToken
            }

            val authPayload = tvdbJson.encodeToString(TvdbAuthRequest(apikey = apiKey))
                .toRequestBody("application/json".toMediaType())

            runCatchingCancellable {
                val response =
                    source.client.post("$TVDB_BASE_URL/login", body = authPayload).use { it.body.string() }
                val authDto = tvdbJson.decodeFromString<TvdbAuthResponse>(response)
                val newToken = authDto.data?.token
                newToken?.takeIf { it.isNotBlank() }?.also {
                    cachedToken = it
                    tokenTimestamp = System.currentTimeMillis()
                    Log.d(TVDB_LOG, "TVDB Auth: successfully acquired new JWT token (valid 28 days)")
                }
            }.getOrElse { e ->
                Log.e(TVDB_LOG, "Error authenticating with TVDB API v4: ${e.message}")
                sendWebhook(
                    url = "$TVDB_BASE_URL/login",
                    context = "Échec d'authentification TVDB v4 (Clé API invalide ou révoquée)",
                    exception = e,
                )
                null
            }
        }
    }

    private suspend inline fun <reified T> fetchTvdb(url: String): T? {
        val token = getTvdbToken() ?: return null
        val headers: Headers = Headers.headersOf(
            "Authorization",
            "Bearer $token",
            "Accept",
            "application/json",
        )
        val response = runCatchingCancellable {
            source.client.newCall(GET(url, headers)).await()
        }.getOrNull() ?: return null

        if (response.isSuccessful) {
            return response.body.source().use { source ->
                tvdbJson.decodeFromBufferedSource<T>(source)
            }
        }
        if (response.code != 401 && response.code != 403) return null
        Log.w(TVDB_LOG, "TVDB ${response.code} Unauthorized on '$url', refreshing JWT token and retrying...")

        val freshToken = getTvdbToken(forceRefresh = true) ?: return null
        val newHeaders = headers.newBuilder()
            .set("Authorization", "Bearer $freshToken")
            .build()

        return runCatchingCancellable {
            val retryRes = source.client.newCall(GET(url, newHeaders)).await()
            if (retryRes.isSuccessful) {
                retryRes.body.source().use { source ->
                    tvdbJson.decodeFromBufferedSource<T>(source)
                }
            } else {
                sendWebhook(
                    url = url,
                    context = "API TVDB v4 inaccessible (HTTP ${retryRes.code}) après rafraîchissement du token",
                    exception = IllegalStateException("TVDB API v4 returned HTTP ${retryRes.code} for $url"),
                )
                null
            }
        }.getOrNull()
    }

    /**
     * High-level helper function to fetch TVDB metadata for external ID
     */
    suspend fun fetchTvdbMetadataFromExternal(
        remoteId: Long,
        source: ExternalSourceId? = null,
        season: Int = 1,
        expectedNames: Set<String> = emptySet(),
        movieWanted: Boolean? = null,
        seasonOrderType: String = "official",
        episodeOrderType: String? = null,
        preferJapanesePoster: Boolean = false,
    ): TvdbMetadata? {
        val sortedNamesKey = expectedNames.sorted().joinToString(";")
        val cacheKey = "$remoteId:$source:$season:$sortedNamesKey:$movieWanted:$seasonOrderType:$episodeOrderType:$preferJapanesePoster"
        tvdbMetadataCache.getCache(cacheKey)?.let {
            return it
        }

        val mutex = requestMutexes.computeIfAbsent(cacheKey) { Mutex() }
        return mutex.withLock {
            tvdbMetadataCache.getCache(cacheKey)?.let {
                return it
            }

            val remoteMedia: TvdbRemoteMedia = resolveRemoteMedia(remoteId, expectedNames, movieWanted)
                ?: return@withLock null

            if (remoteMedia.isMovie) {
                val movieExtended = fetchMovieExtended(remoteMedia.id) ?: return@withLock null
                if (expectedNames.isNotEmpty()) {
                    val candidateTitles = sequence {
                        yield(movieExtended.name)
                        movieExtended.translations?.nameTranslations?.forEach { yield(it.name) }
                    }
                    if (expectedNames.none { expected -> matchesExpectedTitle(expected, candidateTitles) > 0 }) {
                        return@withLock null
                    }
                }
                return@withLock movieExtended.toTvdbMetadata()
            }

            val extendedMetadata: TvdbSeriesExtendedData = fetchSeriesExtended(remoteMedia.id) ?: return@withLock null

            if (expectedNames.isNotEmpty()) {
                val candidateTitles = sequence {
                    yield(extendedMetadata.name)
                    extendedMetadata.translations?.nameTranslations?.forEach { yield(it.name) }
                }
                if (expectedNames.none { expected -> matchesExpectedTitle(expected, candidateTitles) > 0 }) {
                    return@withLock null
                }
            }

            populateSeriesMetadata(
                extendedMetadata = extendedMetadata,
                season = season,
                seasonOrderType = seasonOrderType,
                episodeOrderType = episodeOrderType,
                preferJapanesePoster = preferJapanesePoster,
                matchScore = 100,
            )
        }?.also { result ->
            tvdbMetadataCache[cacheKey] = result to System.currentTimeMillis()
        }
    }

    suspend fun fetchTvdbMetadataFromExternal(
        remoteId: Long,
        source: ExternalSourceId? = null,
        season: Int = 1,
        expectedName: String,
        movieWanted: Boolean? = null,
        seasonOrderType: String = "official",
        episodeOrderType: String? = null,
        preferJapanesePoster: Boolean = false,
    ): TvdbMetadata? = fetchTvdbMetadataFromExternal(
        remoteId = remoteId,
        source = source,
        season = season,
        expectedNames = if (expectedName.isNotBlank()) setOf(expectedName) else emptySet(),
        movieWanted = movieWanted,
        seasonOrderType = seasonOrderType,
        episodeOrderType = episodeOrderType,
        preferJapanesePoster = preferJapanesePoster,
    )

    private suspend fun populateSeriesMetadata(
        extendedMetadata: TvdbSeriesExtendedData,
        season: Int,
        seasonOrderType: String = "official",
        episodeOrderType: String? = null,
        preferJapanesePoster: Boolean = false,
        matchScore: Int = 100,
    ): TvdbMetadata {
        val targetSeasonId = extendedMetadata.seasons
            .firstOrNull { it.number == season && (it.type?.type == seasonOrderType || it.type?.altName == seasonOrderType) }?.id
            ?: extendedMetadata.seasons.firstOrNull { it.number == season }?.id

        val effectiveEpisodeOrder = episodeOrderType ?: seasonOrderType
        val effectiveEpisodeSeasonNumber = if (effectiveEpisodeOrder == "absolute") 1 else season

        return coroutineScope {
            val seasonArtworkAndOverviewDeferred: Deferred<TvdbSeasonDetails?> = async {
                targetSeasonId?.takeIf { it > 0 }?.let { fetchSeasonArtworkAndOverview(it, preferJapanesePoster) }
            }
            val seasonSeriesEpisodesDeferred: Deferred<TvdbSeriesEpisode> = async {
                fetchSeriesEpisodes(extendedMetadata.id, effectiveEpisodeSeasonNumber, effectiveEpisodeOrder)
            }

            val seasonArtworkAndOverview = seasonArtworkAndOverviewDeferred.await()
            val seasonSeriesEpisodes = seasonSeriesEpisodesDeferred.await()

            TvdbMetadata(
                title = extendedMetadata.localizedName,
                summary = seasonArtworkAndOverview?.overview ?: extendedMetadata.localizedOverview,
                releaseDate = seasonSeriesEpisodes.seasonReleaseDate ?: extendedMetadata.firstAired,
                mainPosterUrl = extendedMetadata.fullPosterUrl,
                seasonPosterUrl = seasonArtworkAndOverview?.posterUrl ?: extendedMetadata.fullPosterUrl,
                backdropUrl = seasonArtworkAndOverview?.backdropUrl ?: extendedMetadata.backdropUrl,
                author = null,
                artist = extendedMetadata.companies
                    .mapNotNullTo(linkedSetOf()) { it.name?.trim()?.takeIf(String::isNotBlank) }
                    .joinToString(", ")
                    .takeIf(String::isNotBlank),
                status = parseTvdbStatus(extendedMetadata.status?.name ?: extendedMetadata.status?.recordType),
                genre = extendedMetadata.genres
                    .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
                    .joinToString(", ")
                    .takeIf(String::isNotBlank),
                episodeSummaries = seasonSeriesEpisodes.episodesMap,
                matchScore = matchScore,
                seasonEpisodeCounts = seasonSeriesEpisodes.seasonCounts,
                seriesId = extendedMetadata.id,
            )
        }
    }

    private suspend fun fetchMovieExtended(movieId: Long): TvdbMovieExtendedData? = runCatchingCancellable {
        fetchTvdb<TvdbMovieExtendedResponse>("$TVDB_BASE_URL/movies/$movieId/extended?meta=translations")?.data
    }.getOrNull()

    private suspend fun fetchSeriesExtended(seriesId: Long): TvdbSeriesExtendedData? = runCatchingCancellable {
        fetchTvdb<TvdbSeriesExtendedResponse>("$TVDB_BASE_URL/series/$seriesId/extended?meta=translations")?.data
    }.getOrNull()

    private suspend fun fetchSeasonArtworkAndOverview(
        seasonId: Long,
        preferJapanesePoster: Boolean = false,
    ): TvdbSeasonDetails? = coroutineScope {
        val sDtoDeferred = async {
            runCatchingCancellable {
                fetchTvdb<TvdbSeasonExtendedResponse>("$TVDB_BASE_URL/seasons/$seasonId/extended")?.data
            }.getOrNull()
        }
        val trDtoDeferred = async {
            runCatchingCancellable {
                fetchTvdb<TvdbTranslationResponse>("$TVDB_BASE_URL/seasons/$seasonId/translations/$lang")?.data
            }.getOrNull()
        }

        val sDto = sDtoDeferred.await()
        val trDto = trDtoDeferred.await()

        if (sDto == null && trDto == null) return@coroutineScope null

        val artworks = sDto?.artwork.orEmpty()
        val rawSeasonPoster = artworks
            .filter { it.type == ARTWORK_TYPE_SEASON_POSTER && !it.image.isNullOrBlank() }
            .pickSeasonPosterImage(lang, preferJapanesePoster) ?: sDto?.image

        val rawSeasonBackdrop = artworks.pickSeasonBackdropImage(lang)

        TvdbSeasonDetails(
            posterUrl = rawSeasonPoster.toFullArtworkUrl(),
            backdropUrl = rawSeasonBackdrop.toFullArtworkUrl(),
            overview = trDto?.overview?.takeIf { it.isNotBlank() },
        )
    }

    private data class TvdbSeasonDetails(
        val posterUrl: String?,
        val backdropUrl: String? = null,
        val overview: String?,
    )

    private suspend fun fetchSeriesEpisodes(
        tvdbId: Long,
        seasonNumber: Int,
        seasonOrderType: String = "official",
        maxPageNumber: Int = 4,
    ): TvdbSeriesEpisode {
        val epMap = mutableMapOf<Int, TvdbEpisode>()
        val seasonCounts = mutableMapOf<Int, Int>()
        var seasonReleaseDate: String? = null

        val order = if (seasonOrderType.isNotBlank() && seasonOrderType != "official") seasonOrderType else "default"

        page@ for (page in 0..maxPageNumber) {
            val episodes = fetchEpisodesPage(tvdbId, order, page) ?: break@page

            seasonCounts.mergeSeasonCounts(episodes)

            val seasonEps = episodes.filter { it.seasonNumber == seasonNumber }
            if (seasonReleaseDate == null) {
                seasonReleaseDate = seasonEps.firstOrNull { !it.aired.isNullOrBlank() }?.aired
                seasonReleaseDate = seasonEps.firstOrNull { !it.aired.isNullOrBlank() }?.aired
            }

            epMap.collectSeasonEpisodes(seasonEps)
        }

        return TvdbSeriesEpisode(
            episodesMap = epMap,
            seasonCounts,
            seasonReleaseDate,
        )
    }

    /** Récupère une page d'épisodes ; `null` interrompt la pagination (page vide ou erreur). */
    private suspend fun fetchEpisodesPage(
        tvdbId: Long,
        order: String,
        page: Int,
    ): List<TvdbEpisodeDto>? {
        val episodesUrl = "$TVDB_BASE_URL/series/$tvdbId/episodes/$order/$lang?page=$page"
        val epDto = runCatchingCancellable {
            fetchTvdb<TvdbEpisodesResponse>(episodesUrl) ?: return null
        }.getOrElse { return null }

        return epDto.data?.episodes?.takeIf { it.isNotEmpty() }
    }
    private data class TvdbSeriesEpisode(
        val episodesMap: Map<Int, TvdbEpisode>,
        val seasonCounts: Map<Int, Int>,
        val seasonReleaseDate: String?,
    )

    /**
     * Calculates a matching score between a target title and candidate title variants.
     * Calculates a matching score between a target title and candidate title variants.
     *
     * @param expectedName Target title to match against.
     * @param candidateTitles Sequence of possible title representations (e.g. aliases, translations, slug).
     * @return 2 for an exact normalized match, 1 for mutual inclusion (with a minimum length threshold), or 0 for no match.
     */
    private fun matchesExpectedTitle(
        expectedName: String,
        candidateTitles: Sequence<String?>,
    ): Int {
        if (expectedName.isBlank()) return 1
        val target = expectedName.normalize()
        if (target.isBlank()) return 1

        var hasPartialMatch = false

        for (rawTitle in candidateTitles) {
            val norm = rawTitle?.normalize()?.takeIf { it.isNotBlank() } ?: continue
            if (norm == target) return 2 // Immediate exact match (short-circuit)

            if (!hasPartialMatch) {
                val minLen = MIN_PARTIAL_MATCH_LENGTH
                if (norm.contains(target) || (norm.length >= minLen && target.contains(norm))) {
                    hasPartialMatch = true
                }
            }
        }

        return if (hasPartialMatch) 1 else 0
    }

    private suspend fun resolveRemoteMedia(
        remoteId: Long,
        expectedNames: Set<String>,
        movieWanted: Boolean? = null,
    ): TvdbRemoteMedia? {
        val remoteIdSearchUrl = "$TVDB_BASE_URL/search/remoteid/$remoteId"
        Log.d(TVDB_LOG, "resolveRemoteMedia: searching from id $remoteId")

        val remoteIdDto = runCatchingCancellable {
            fetchTvdb<TvdbRemoteIdResponse>(remoteIdSearchUrl) ?: return null
        }.getOrElse { return null }

        return remoteIdDto.data.mapNotNull { wrapper ->
            when (movieWanted) {
                true -> wrapper.movie?.copy(isMovie = true)
                false -> wrapper.series
                null -> wrapper.series ?: wrapper.movie?.copy(isMovie = true)
            }
        }.pickBestCandidate(expectedNames)
    }

    private fun List<TvdbRemoteMedia>.pickBestCandidate(expectedNames: Set<String>): TvdbRemoteMedia? {
        if (isEmpty()) return null
        if (expectedNames.isEmpty()) return singleOrNull()

        var bestCandidate: TvdbRemoteMedia? = null
        var bestScore = 0

        for (candidate in this) {
            val titles = sequence {
                yield(candidate.name)
                yield(candidate.slug)
                candidate.aliases.forEach { yield(it.name) }
            }
            val candidateMaxScore = expectedNames.maxOfOrNull { expected ->
                matchesExpectedTitle(expected, titles)
            } ?: 0

            if (candidateMaxScore > bestScore) {
                bestScore = candidateMaxScore
                bestCandidate = candidate
                if (bestScore == 2) break // Exact match found, short-circuit
            }
        }

        // Fallback: if only one media candidate matches the requested type from the exact remote ID, accept it
        return bestCandidate ?: singleOrNull()
    }

    // ======================================================== scrap ======================================================================

    /**
     * High-level helper function to fetch TVDB metadata for a series and season.
     */
    suspend fun fetchTvdbMetadata(
        title: String,
        season: Int = 1,
        isMovie: Boolean = false,
        preference: MediaPreference = MediaPreference.ANIME,
        seasonOrderType: String = "official",
        targetTvdbId: Long? = null,
        preferJapanesePoster: Boolean = false,
        episodeOrderType: String? = null,
    ): TvdbMetadata? {
        val sanitizedTitle = source.sanitizeTitle(title)
        val cacheKey = "${targetTvdbId ?: sanitizedTitle}:$season:$lang:$isMovie:${preference.name}:$seasonOrderType:$episodeOrderType:$preferJapanesePoster"

        tvdbMetadataCache.getCache(cacheKey)?.let {
            return it
        }

        val mutex = requestMutexes.computeIfAbsent(cacheKey) { Mutex() }
        return mutex.withLock {
            tvdbMetadataCache.getCache(cacheKey)?.let {
                return it
            }

            val searchData = resolveSearchData(
                title = title,
                sanitizedTitle = sanitizedTitle,
                isMovie = isMovie,
                preference = preference,
                targetTvdbId = targetTvdbId,
            ) ?: return@withLock null

            resolveMetadataFromSearchData(
                searchData = searchData,
                season = season,
                seasonOrderType = seasonOrderType,
                episodeOrderType = episodeOrderType,
                preferJapanesePoster = preferJapanesePoster,
            )
        }?.also { result ->
            tvdbMetadataCache[cacheKey] = result to System.currentTimeMillis()
        }
    }

    /** Résout les données de recherche : cible TVDB explicite ou recherche par titre. */
    private suspend fun resolveSearchData(
        title: String,
        sanitizedTitle: String,
        isMovie: Boolean,
        preference: MediaPreference,
        targetTvdbId: Long?,
    ): TvdbSearchResultData? = if (targetTvdbId != null && targetTvdbId > 0) {
        TvdbSearchResultData(
            id = targetTvdbId.toString(),
            title = title,
            posterUrl = null,
            isMovie = isMovie,
            overview = null,
            releaseDate = null,
            score = 100,
            status = null,
        )
    } else {
        searchTvdbByTitle(title, sanitizedTitle, isMovie, preference)
    }

    private suspend fun searchTvdbByTitle(
        title: String,
        sanitizedTitle: String,
        isMovie: Boolean,
        preference: MediaPreference,
    ): TvdbSearchResultData? {
        val searchUrl = "$TVDB_BASE_URL/search?q=${URLEncoder.encode(sanitizedTitle, "UTF-8")}"
        Log.d(
            TVDB_LOG,
            "fetchTvdbMetadata: searching '$sanitizedTitle' (original: '$title', isMovie=$isMovie, pref=$preference)",
        )

        return try {
            val searchDto = fetchTvdb<TvdbSearchResponse>(searchUrl) ?: return null
            toSearchResultData(searchDto, sanitizedTitle, isMovie, preference)
        } catch (e: Exception) {
            Log.e(TVDB_LOG, "TVDB Search failed for '$sanitizedTitle': ${e.message}")
            null
        }
    }

    /** Sélectionne le meilleur résultat de recherche et projette les champs utiles. */
    private fun toSearchResultData(
        searchDto: TvdbSearchResponse,
        sanitizedTitle: String,
        isMovie: Boolean,
        preference: MediaPreference,
    ): TvdbSearchResultData? {
        val targetType = if (isMovie) "movie" else "series"
        val candidatePool = searchDto.data.filter { it.type == targetType }

        val jpnResult = candidatePool.maxByOrNull { scoreTvdbResult(it, sanitizedTitle, preference) }
            ?: candidatePool.firstOrNull()
            ?: run {
                Log.w(TVDB_LOG, "TVDB Search returned 0 results for '$sanitizedTitle'")
                return null
            }
        val bestScore = scoreTvdbResult(jpnResult, sanitizedTitle, preference)
        val id = jpnResult.tvdbId.ifBlank { jpnResult.objectId.substringAfter("-") }
        val poster = jpnResult.imageUrl.toFullArtworkUrl()
        val officialTitle = jpnResult.translations["fra"] ?: jpnResult.name
        val movieType = jpnResult.type == "movie" || jpnResult.objectId.startsWith("movie-")
        val overview = jpnResult.overviews[lang] ?: jpnResult.overview
        val releaseDate =
            jpnResult.firstAirTime?.takeIf { it.isNotBlank() } ?: jpnResult.year?.takeIf { it.isNotBlank() }
        return TvdbSearchResultData(
            id = id,
            title = officialTitle,
            posterUrl = poster,
            isMovie = movieType,
            overview = overview,
            releaseDate = releaseDate,
            score = bestScore,
            status = jpnResult.status,
        )
    }

    /** Charge les métadonnées (film ou série) correspondant au résultat de recherche. */
    private suspend fun resolveMetadataFromSearchData(
        searchData: TvdbSearchResultData,
        season: Int,
        seasonOrderType: String,
        episodeOrderType: String?,
        preferJapanesePoster: Boolean,
    ): TvdbMetadata? {
        val tvdbIdLong = searchData.id.toLongOrNull() ?: return null
        val isMovieResolved = searchData.isMovie

        Log.d(
            TVDB_LOG,
            "TVDB resolved tvdbId='$tvdbIdLong', title='${searchData.title}', isMovie=$isMovieResolved",
        )

        if (isMovieResolved) {
            return fetchMovieExtended(tvdbIdLong)?.toTvdbMetadata()?.copy(matchScore = searchData.score)
        }

        val extended = fetchSeriesExtended(tvdbIdLong) ?: return null
        return populateSeriesMetadata(
            extendedMetadata = extended,
            season = season,
            seasonOrderType = seasonOrderType,
            episodeOrderType = episodeOrderType,
            preferJapanesePoster = preferJapanesePoster,
            matchScore = searchData.score,
        )
    }

    companion object {
        private val ONGOING_KEYWORDS = arrayOf("continuing", "air-dated", "ongoing", "in production")
        private val COMPLETED_KEYWORDS = arrayOf("ended", "released", "completed")
    }

    private fun parseTvdbStatus(statusStr: String?): Int {
        if (statusStr.isNullOrEmpty()) return SAnime.UNKNOWN
        return when {
            statusStr.containsAny(ONGOING_KEYWORDS) -> SAnime.ONGOING
            statusStr.containsAny(COMPLETED_KEYWORDS) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun scoreTvdbResult(
        result: TvdbSearchResult,
        query: String,
        preference: MediaPreference = MediaPreference.ANIME,
    ): Int {
        val normQuery = query.normalize()
        val candidateTitles = buildCandidateTitles(result)

        if (candidateTitles.isEmpty() || normQuery.isBlank()) return 0

        var maxScore = 0

        for (candidate in candidateTitles) {
            val currentScore = similarityScore(candidate, normQuery)
            if (currentScore > maxScore) {
                maxScore = currentScore
            }
        }

        if (maxScore == 0) return 0

        val isAnimation = result.genres.any {
            it.equals("Anime", ignoreCase = true) || it.equals("Animation", ignoreCase = true)
        }

        val slug = result.slug.orEmpty()
        val isLiveActionSlug = liveActionSlugRegex.containsMatchIn(slug)
        val isAnimeSlug = animeSlugRegex.containsMatchIn(slug)

        maxScore += preferenceAdjustment(result, preference, isAnimation, isLiveActionSlug, isAnimeSlug)

        return maxOf(0, maxScore)
    }
}

/**
 * Sélectionne l'affiche de saison par ordre de préférence :
 * langue préférée, langue alternative (jpn/lang), anglais, puis meilleur score.
 */
private fun List<TvdbArtworkDto>.pickSeasonPosterImage(
    lang: String,
    preferJapanesePoster: Boolean,
): String? {
    val primaryLanguage = if (preferJapanesePoster) LANGUAGE_JAPANESE else lang
    val fallbackLanguage = if (preferJapanesePoster) lang else LANGUAGE_JAPANESE
    return maxImageByLanguage(primaryLanguage)
        ?: maxImageByLanguage(fallbackLanguage)
        ?: maxImageByLanguage(LANGUAGE_ENGLISH)
        ?: maxByOrNull { it.score }?.image
}

/** Bannière de saison : langue préférée d'abord, puis la première disponible. */
private fun List<TvdbArtworkDto>.pickSeasonBackdropImage(lang: String): String? = firstOrNull { it.type == ARTWORK_TYPE_SEASON_BANNER && it.language?.equals(lang, ignoreCase = true) == true }?.image
    ?: firstOrNull { it.type == ARTWORK_TYPE_SEASON_BANNER }?.image

private fun List<TvdbArtworkDto>.maxImageByLanguage(language: String): String? = filter { it.language?.equals(language, ignoreCase = true) == true }.maxByOrNull { it.score }?.image

/** Complète une URL d'artwork relative avec la base TVDB. */
private fun String?.toFullArtworkUrl(): String? = this?.takeIf { it.isNotBlank() }?.let {
    if (it.startsWith("http")) it else "$TVDB_ARTWORK_BASE_URL$it"
}

private fun MutableMap<Int, Int>.mergeSeasonCounts(episodes: List<TvdbEpisodeDto>) {
    for (ep in episodes) {
        if (ep.number <= 0) continue
        merge(ep.seasonNumber, ep.number, ::maxOf)
    }
}

private fun MutableMap<Int, TvdbEpisode>.collectSeasonEpisodes(seasonEps: List<TvdbEpisodeDto>) {
    for (ep in seasonEps) {
        val epNum = ep.number
        if (epNum <= 0 || containsKey(epNum)) continue
        this[epNum] = TvdbEpisode(ep.name, ep.episodeImage.toFullArtworkUrl(), ep.overview)
    }
}

/** Titres candidats normalisés : nom, traductions, slug et alias. */
private fun buildCandidateTitles(result: TvdbSearchResult): List<String> = buildList {
    result.name?.let { add(it) }
    result.translations["fra"]?.let { add(it) }
    result.translations["eng"]?.let { add(it) }
    result.slug?.let { add(it) }
    addAll(result.aliases)
}.map { it.normalize() }.filter { it.isNotBlank() }

/** Score de ressemblance d'un titre candidat avec la requête normalisée. */
private fun similarityScore(candidate: String, normQuery: String): Int = when {
    candidate == normQuery -> SCORE_EXACT_TITLE

    candidate.contains(normQuery) || normQuery.contains(candidate) -> {
        val longer = maxOf(candidate.length, normQuery.length)
        val shorter = minOf(candidate.length, normQuery.length)
        val ratio = shorter.toDouble() / longer
        (SCORE_CONTAINS_BASE * ratio).toInt()
    }

    else -> 0
}

/** Ajustements de score liés à la préférence de média. */
private fun preferenceAdjustment(
    result: TvdbSearchResult,
    preference: MediaPreference,
    isAnimation: Boolean,
    isLiveActionSlug: Boolean,
    isAnimeSlug: Boolean,
): Int = when (preference) {
    MediaPreference.ANIME -> animePreferenceAdjustment(result, isAnimation, isLiveActionSlug, isAnimeSlug)
    MediaPreference.DRAMA -> dramaPreferenceAdjustment(result, isAnimation, isLiveActionSlug, isAnimeSlug)
    MediaPreference.ANY -> anyPreferenceAdjustment(result)
}

private fun animePreferenceAdjustment(
    result: TvdbSearchResult,
    isAnimation: Boolean,
    isLiveActionSlug: Boolean,
    isAnimeSlug: Boolean,
): Int {
    var delta = 0

    if (isAnimation) {
        delta += BONUS_ANIMATION_GENRE
    } else if (result.genres.isNotEmpty()) {
        delta -= PENALTY_NO_ANIMATION_GENRE
    }

    if (isAnimeSlug) delta += BONUS_ANIME_SLUG
    if (isLiveActionSlug) delta -= PENALTY_LIVE_ACTION_SLUG

    if (result.primaryLanguage == "jpn" || result.country == "jpn") {
        delta += BONUS_JAPANESE_ORIGIN
    }
    if ((result.country == "usa" || result.country == "gbr") && !isAnimation) {
        delta -= PENALTY_WESTERN_ORIGIN
    }

    return delta
}

private fun dramaPreferenceAdjustment(
    result: TvdbSearchResult,
    isAnimation: Boolean,
    isLiveActionSlug: Boolean,
    isAnimeSlug: Boolean,
): Int {
    var delta = 0

    if (isAnimation) {
        delta -= PENALTY_NO_ANIMATION_GENRE
    }
    if (isLiveActionSlug) delta += PENALTY_LIVE_ACTION_SLUG
    if (isAnimeSlug) delta -= PENALTY_LIVE_ACTION_SLUG
    if (result.primaryLanguage == "jpn" || result.country == "jpn" || result.country == "kor") {
        delta += BONUS_JAPANESE_ORIGIN
    }

    return delta
}

private fun anyPreferenceAdjustment(result: TvdbSearchResult): Int {
    var delta = 0

    if (result.primaryLanguage == "jpn" || result.country == "jpn") {
        delta += 15
    }

    return delta
}

suspend fun Source.fetchTvdbMetadata(
    title: String,
    season: Int = 1,
    isMovie: Boolean = false,
    preference: MediaPreference = MediaPreference.ANIME,
    seasonOrderType: String = "official",
    targetTvdbId: Long? = null,
    preferJapanesePoster: Boolean = false,
    episodeOrderType: String? = null,
): TvdbMetadata? = TvdbFetcher(this).fetchTvdbMetadata(
    title = title,
    season = season,
    isMovie = isMovie,
    preference = preference,
    seasonOrderType = seasonOrderType,
    targetTvdbId = targetTvdbId,
    preferJapanesePoster = preferJapanesePoster,
    episodeOrderType = episodeOrderType,
)

suspend fun Source.fetchTvdbMetadataFromExternal(
    remoteId: Long,
    source: ExternalSourceId? = null,
    season: Int = 1,
    expectedNames: Set<String> = emptySet(),
    movieWanted: Boolean? = null,
    seasonOrderType: String = "official",
): TvdbMetadata? = TvdbFetcher(this).fetchTvdbMetadataFromExternal(
    remoteId = remoteId,
    source = source,
    season = season,
    expectedNames = expectedNames,
    movieWanted = movieWanted,
    seasonOrderType = seasonOrderType,
)

suspend fun Source.fetchTvdbMetadataFromExternal(
    remoteId: Long,
    source: ExternalSourceId? = null,
    season: Int = 1,
    expectedName: String,
    movieWanted: Boolean? = null,
    seasonOrderType: String = "official",
): TvdbMetadata? = TvdbFetcher(this).fetchTvdbMetadataFromExternal(
    remoteId = remoteId,
    source = source,
    season = season,
    expectedName = expectedName,
    movieWanted = movieWanted,
    seasonOrderType = seasonOrderType,
)
