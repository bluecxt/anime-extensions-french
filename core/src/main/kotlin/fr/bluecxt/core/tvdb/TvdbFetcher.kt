// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.tvdb

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.Source
import fr.bluecxt.core.TVDB_LOG
import fr.bluecxt.core.monitoring.ErrorWebhook
import fr.bluecxt.core.tmdb.utils.sanitizeTitle
import fr.bluecxt.core.tvdb.dto.TvdbAuthRequest
import fr.bluecxt.core.tvdb.dto.TvdbAuthResponse
import fr.bluecxt.core.tvdb.dto.TvdbEpisodesResponse
import fr.bluecxt.core.tvdb.dto.TvdbExtendedResponse
import fr.bluecxt.core.tvdb.dto.TvdbSearchResponse
import fr.bluecxt.core.tvdb.dto.TvdbSearchResult
import fr.bluecxt.core.tvdb.dto.TvdbSeasonExtendedResponse
import fr.bluecxt.core.tvdb.dto.TvdbTranslationResponse
import fr.bluecxt.core.utils.normalize
import keiyoushi.core.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

private const val TVDB_BASE_URL = "https://api4.thetvdb.com/v4"
private const val TVDB_ARTWORK_BASE_URL = "https://artworks.thetvdb.com"
private const val DEFAULT_TVDB_API_KEY = BuildConfig.TVDB_API

private val tvdbJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private var cachedToken: String? = null
private var tokenTimestamp: Long = 0L
private val tokenMutex = Mutex()
private const val TOKEN_LIFETIME_MS = 28 * 24 * 3600 * 1000L // 28 Days

// In-Memory Metadata Cache (1 hour TTL) & Request Coalescing Mutexes
private val tvdbMetadataCache = ConcurrentHashMap<String, Pair<TvdbMetadata, Long>>()
private val requestMutexes = ConcurrentHashMap<String, Mutex>()
private const val METADATA_CACHE_LIFETIME_MS = 3600 * 1000L

private suspend fun Source.getTvdbToken(apiKey: String = DEFAULT_TVDB_API_KEY, forceRefresh: Boolean = false): String? {
    val currentTime = System.currentTimeMillis()
    if (!forceRefresh && cachedToken != null && currentTime - tokenTimestamp < TOKEN_LIFETIME_MS) {
        return cachedToken
    }

    return tokenMutex.withLock {
        if (!forceRefresh && cachedToken != null && System.currentTimeMillis() - tokenTimestamp < TOKEN_LIFETIME_MS) {
            return@withLock cachedToken
        }

        val authPayload = tvdbJson.encodeToString(TvdbAuthRequest(apikey = apiKey))
            .toRequestBody("application/json".toMediaType())

        val request = POST("$TVDB_BASE_URL/login", body = authPayload)
        try {
            val response = client.newCall(request).awaitSuccess().use { it.body.string() }
            val authDto = tvdbJson.decodeFromString<TvdbAuthResponse>(response)
            val newToken = authDto.data?.token
            if (!newToken.isNullOrBlank()) {
                cachedToken = newToken
                tokenTimestamp = System.currentTimeMillis()
                Log.d(TVDB_LOG, "TVDB Auth: successfully acquired new JWT token (valid 28 days)")
                newToken
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TVDB_LOG, "Error authenticating with TVDB API v4: ${e.message}")
            ErrorWebhook.sendWebhook(
                baseUrl = TVDB_BASE_URL,
                url = "$TVDB_BASE_URL/login",
                context = "Échec d'authentification TVDB v4 (Clé API invalide ou révoquée)",
                exception = e,
                extensionName = currentName,
                extensionVersion = currentVersion,
            )
            null
        }
    }
}

private suspend fun Source.executeTvdbRequest(url: String, apiKey: String): String? {
    val token = getTvdbToken(apiKey) ?: return null
    val req = GET(url, Headers.headersOf("Authorization", "Bearer $token", "Accept", "application/json"))
    val response = try {
        client.newCall(req).await()
    } catch (e: Exception) {
        return null
    }

    if (response.code == 401 || response.code == 403) {
        Log.w(TVDB_LOG, "TVDB ${response.code} Unauthorized on '$url', refreshing JWT token and retrying...")
        val freshToken = getTvdbToken(apiKey, forceRefresh = true) ?: return null
        val retryReq = GET(url, Headers.headersOf("Authorization", "Bearer $freshToken", "Accept", "application/json"))
        return try {
            val retryRes = client.newCall(retryReq).await()
            if (retryRes.isSuccessful) {
                retryRes.body.string()
            } else {
                ErrorWebhook.sendWebhook(
                    baseUrl = TVDB_BASE_URL,
                    url = url,
                    context = "API TVDB v4 inaccessible (HTTP ${retryRes.code}) après rafraîchissement du token",
                    exception = IllegalStateException("TVDB API v4 returned HTTP ${retryRes.code} for $url"),
                    extensionName = currentName,
                    extensionVersion = currentVersion,
                )
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    return if (response.isSuccessful) response.body.string() else null
}

/**
 * High-level helper function to fetch TVDB metadata for a series and season.
 */
suspend fun Source.fetchTvdbMetadata(
    title: String,
    season: Int = 1,
    lang: String = "fra",
    apiKey: String = DEFAULT_TVDB_API_KEY,
    isMovie: Boolean = false,
): TvdbMetadata? {
    val sanitizedTitle = sanitizeTitle(title)
    val cacheKey = "$sanitizedTitle:$season:$lang:$isMovie"

    // 1. Check in-memory cache
    val cached = tvdbMetadataCache[cacheKey]
    if (cached != null && System.currentTimeMillis() - cached.second < METADATA_CACHE_LIFETIME_MS) {
        Log.d(TVDB_LOG, "TVDB Cache hit for '$cacheKey'")
        return cached.first
    }

    val mutex = requestMutexes.computeIfAbsent(cacheKey) { Mutex() }
    return mutex.withLock {
        val cachedInside = tvdbMetadataCache[cacheKey]
        if (cachedInside != null && System.currentTimeMillis() - cachedInside.second < METADATA_CACHE_LIFETIME_MS) {
            Log.d(TVDB_LOG, "TVDB Cache hit (coalesced) for '$cacheKey'")
            return@withLock cachedInside.first
        }

        val searchUrl = "$TVDB_BASE_URL/search?q=${URLEncoder.encode(sanitizedTitle, "UTF-8")}"
        Log.d(TVDB_LOG, "fetchTvdbMetadata: searching '$sanitizedTitle' (original: '$title', isMovie=$isMovie)")

        val searchData = try {
            val response = executeTvdbRequest(searchUrl, apiKey) ?: return@withLock null
            val searchDto = tvdbJson.decodeFromString<TvdbSearchResponse>(response)

            val targetType = if (isMovie) "movie" else "series"
            val typeFiltered = searchDto.data.filter { it.type == targetType }
            val candidatePool = if (typeFiltered.isNotEmpty()) typeFiltered else searchDto.data.filter { it.type == "series" || it.type == "movie" }.ifEmpty { searchDto.data }

            val jpnResult = candidatePool.maxByOrNull { scoreTvdbResult(it, sanitizedTitle) }
                ?: candidatePool.firstOrNull()
                ?: run {
                    Log.w(TVDB_LOG, "TVDB Search returned 0 results for '$sanitizedTitle'")
                    return@withLock null
                }
            val bestScore = scoreTvdbResult(jpnResult, sanitizedTitle)
            val id = jpnResult.tvdbId.ifBlank { jpnResult.objectId.substringAfter("-") }
            val poster = jpnResult.imageUrl?.takeIf { it.isNotBlank() }?.let {
                if (it.startsWith("http")) it else "$TVDB_ARTWORK_BASE_URL$it"
            }
            val officialTitle = jpnResult.translations["fra"] ?: jpnResult.name
            val movieType = jpnResult.type == "movie" || jpnResult.objectId.startsWith("movie-")
            val overview = jpnResult.overviews[lang] ?: jpnResult.overview
            val releaseDate = jpnResult.firstAirTime?.takeIf { it.isNotBlank() } ?: jpnResult.year?.takeIf { it.isNotBlank() }
            TvdbSearchResultData(
                id = id,
                title = officialTitle,
                posterUrl = poster,
                isMovie = movieType,
                overview = overview,
                releaseDate = releaseDate,
                score = bestScore,
                status = jpnResult.status,
            )
        } catch (e: Exception) {
            Log.e(TVDB_LOG, "TVDB Search failed for '$sanitizedTitle': ${e.message}")
            return@withLock null
        }

        val tvdbId = searchData.id
        val tvdbTitle = searchData.title
        val posterUrl = searchData.posterUrl
        val isMovieResolved = searchData.isMovie
        val frenchOverview = searchData.overview
        val tvdbReleaseDate = searchData.releaseDate
        val tvdbMatchScore = searchData.score

        Log.d(TVDB_LOG, "TVDB Search found tvdbId='$tvdbId', title='$tvdbTitle', isMovie=$isMovieResolved, posterUrl='$posterUrl'")

        val mediaType = if (isMovieResolved) "movies" else "series"

        // Fetch Extended Details for backdrop image, status, season poster, genres and studios/companies
        val extendedUrl = "$TVDB_BASE_URL/$mediaType/$tvdbId/extended"
        var backdropUrl: String? = null
        var tvdbStatusName: String? = null
        var seasonPosterUrl: String? = null
        var targetSeasonId: Long? = null
        var tvdbGenres: String? = null
        var tvdbCompanies: String? = null
        var seasonOverview: String? = null

        try {
            val response = executeTvdbRequest(extendedUrl, apiKey)
            if (response != null) {
                val extDto = tvdbJson.decodeFromString<TvdbExtendedResponse>(response)
                backdropUrl = extDto.data?.artworks?.find { it.type == 15 || it.type == 14 || it.type == 3 }?.image?.takeIf { it.isNotBlank() }?.let {
                    if (it.startsWith("http")) it else "$TVDB_ARTWORK_BASE_URL$it"
                }
                tvdbStatusName = extDto.data?.status?.name ?: extDto.data?.status?.recordType
                val targetSeasonObj = extDto.data?.seasons?.find { it.number == season }
                targetSeasonId = targetSeasonObj?.id
                var rawSeasonPoster: String? = null
                if (targetSeasonId != null && targetSeasonId > 0) {
                    try {
                        val seasonUrl = "$TVDB_BASE_URL/seasons/$targetSeasonId/extended"
                        val sResp = executeTvdbRequest(seasonUrl, apiKey)
                        if (sResp != null) {
                            val sDto = tvdbJson.decodeFromString<TvdbSeasonExtendedResponse>(sResp)
                            val artworks = sDto.data?.artwork.orEmpty()
                            val langPoster = artworks.find { it.language?.equals(lang, ignoreCase = true) == true }?.image
                            val bestArt = langPoster ?: artworks.firstOrNull()?.image
                            rawSeasonPoster = bestArt ?: sDto.data?.image
                        }
                    } catch (_: Exception) {}
                    try {
                        val translationUrl = "$TVDB_BASE_URL/seasons/$targetSeasonId/translations/$lang"
                        val trResp = executeTvdbRequest(translationUrl, apiKey)
                        if (trResp != null) {
                            val trDto = tvdbJson.decodeFromString<TvdbTranslationResponse>(trResp)
                            seasonOverview = trDto.data?.overview?.takeIf { it.isNotBlank() }
                        }
                    } catch (_: Exception) {}
                }
                if (rawSeasonPoster == null) {
                    rawSeasonPoster = targetSeasonObj?.image
                }
                seasonPosterUrl = rawSeasonPoster?.takeIf { it.isNotBlank() }?.let {
                    if (it.startsWith("http")) it else "$TVDB_ARTWORK_BASE_URL$it"
                }
                tvdbGenres = extDto.data?.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }?.joinToString(", ")?.takeIf { it.isNotBlank() }
                tvdbCompanies = extDto.data?.companies?.mapNotNull { it.name }?.filter { it.isNotBlank() }?.distinct()?.joinToString(", ")?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {}

        // Fetch Episodes (up to 3 pages) for series
        val epMap = mutableMapOf<Int, Triple<String?, String?, String?>>()
        val seasonCounts = mutableMapOf<Int, Int>()
        var seasonReleaseDate: String? = null

        if (!isMovieResolved) {
            for (page in 0..2) {
                val episodesUrl = "$TVDB_BASE_URL/series/$tvdbId/episodes/default/$lang?page=$page"
                try {
                    val response = executeTvdbRequest(episodesUrl, apiKey) ?: break
                    val epDto = tvdbJson.decodeFromString<TvdbEpisodesResponse>(response)
                    val episodes = epDto.data?.episodes.orEmpty()
                    if (episodes.isEmpty()) break

                    for (ep in episodes) {
                        val sNum = ep.seasonNumber
                        if (ep.number > 0) {
                            seasonCounts[sNum] = maxOf(seasonCounts[sNum] ?: 0, ep.number)
                        }
                    }

                    val seasonEps = episodes.filter { it.seasonNumber == season }
                    if (seasonReleaseDate == null) {
                        seasonReleaseDate = seasonEps.firstOrNull { !it.aired.isNullOrBlank() }?.aired
                    }

                    for (ep in seasonEps) {
                        val epNum = ep.number
                        if (epNum > 0 && !epMap.containsKey(epNum)) {
                            val fullImgUrl = ep.episodeImage?.let {
                                if (it.startsWith("http")) it else "$TVDB_ARTWORK_BASE_URL$it"
                            }
                            epMap[epNum] = Triple(ep.name, fullImgUrl, ep.overview)
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        val finalStatus = parseTvdbStatus(tvdbStatusName ?: searchData.status)

        val selectedPoster = seasonPosterUrl ?: posterUrl
        Log.d(TVDB_LOG, "Poster selected for '$title' (season $season): selected='$selectedPoster', seasonPoster='$seasonPosterUrl', mainPoster='$posterUrl', targetSeasonId=$targetSeasonId")
        Log.d(TVDB_LOG, "TVDB fetched ${epMap.size} episodes for season $season, backdropUrl=$backdropUrl, seasonPosterUrl=$seasonPosterUrl, seasonReleaseDate=$seasonReleaseDate, status=$finalStatus")

        val metadata = TvdbMetadata(
            title = tvdbTitle,
            summary = seasonOverview ?: frenchOverview,
            releaseDate = seasonReleaseDate ?: tvdbReleaseDate,
            mainPosterUrl = posterUrl,
            seasonPosterUrl = seasonPosterUrl ?: posterUrl,
            backdropUrl = backdropUrl,
            author = tvdbCompanies,
            artist = null,
            status = finalStatus,
            genre = tvdbGenres,
            episodeSummaries = epMap,
            episodeOffset = 0,
            matchScore = tvdbMatchScore,
            seasonEpisodeCounts = seasonCounts,
        )

        tvdbMetadataCache[cacheKey] = Pair(metadata, System.currentTimeMillis())
        metadata
    }
}

private data class TvdbSearchResultData(
    val id: String,
    val title: String?,
    val posterUrl: String?,
    val isMovie: Boolean,
    val overview: String?,
    val releaseDate: String?,
    val score: Int = 0,
    val status: String? = null,
)

private fun parseTvdbStatus(statusStr: String?): Int {
    val norm = statusStr.orEmpty().lowercase()
    return when {
        norm.contains("continuing") || norm.contains("air-dated") || norm.contains("ongoing") || norm.contains("in production") -> 1
        norm.contains("ended") || norm.contains("released") || norm.contains("completed") -> 2
        else -> 0
    }
}

private fun scoreTvdbResult(result: TvdbSearchResult, query: String): Int {
    val normQuery = query.normalize()
    val candidateTitles = buildList {
        result.name?.let { add(it) }
        result.translations["fra"]?.let { add(it) }
        result.translations["eng"]?.let { add(it) }
        result.slug?.let { add(it) }
        addAll(result.aliases)
    }.map { it.normalize() }.filter { it.isNotBlank() }

    if (candidateTitles.isEmpty() || normQuery.isBlank()) return 0

    var maxScore = 0

    for (candidate in candidateTitles) {
        var currentScore = 0
        when {
            candidate == normQuery -> currentScore += 100

            candidate.contains(normQuery) || normQuery.contains(candidate) -> {
                val longer = maxOf(candidate.length, normQuery.length)
                val shorter = minOf(candidate.length, normQuery.length)
                val ratio = shorter.toDouble() / longer
                currentScore += (85 * ratio).toInt()
            }
        }

        if (currentScore > maxScore) {
            maxScore = currentScore
        }
    }

    if (result.primaryLanguage == "jpn" || result.country == "jpn") {
        maxScore += 15
    }

    return maxScore
}
