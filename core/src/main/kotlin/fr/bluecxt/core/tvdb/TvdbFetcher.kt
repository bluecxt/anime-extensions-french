package fr.bluecxt.core.tvdb

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.Source
import fr.bluecxt.core.TVDB_LOG
import fr.bluecxt.core.tmdb.utils.sanitizeTitle
import fr.bluecxt.core.tvdb.dto.TvdbAuthRequest
import fr.bluecxt.core.tvdb.dto.TvdbAuthResponse
import fr.bluecxt.core.tvdb.dto.TvdbEpisodesResponse
import fr.bluecxt.core.tvdb.dto.TvdbExtendedResponse
import fr.bluecxt.core.tvdb.dto.TvdbSearchResponse
import fr.bluecxt.core.tvdb.dto.TvdbTranslationResponse
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
private val DEFAULT_TVDB_API_KEY = BuildConfig.TVDB_API

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

    if (response.code == 401) {
        Log.w(TVDB_LOG, "TVDB 401 Unauthorized on '$url', refreshing JWT token and retrying...")
        val freshToken = getTvdbToken(apiKey, forceRefresh = true) ?: return null
        val retryReq = GET(url, Headers.headersOf("Authorization", "Bearer $freshToken", "Accept", "application/json"))
        return try {
            val retryRes = client.newCall(retryReq).await()
            if (retryRes.isSuccessful) retryRes.body.string() else null
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
): TvdbMetadata? {
    val sanitizedTitle = sanitizeTitle(title)
    val cacheKey = "$sanitizedTitle:$season:$lang"

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
        Log.d(TVDB_LOG, "fetchTvdbMetadata: searching '$sanitizedTitle' (original: '$title')")

        val (tvdbId, posterUrl, isMovie, frenchOverview) = try {
            val response = executeTvdbRequest(searchUrl, apiKey) ?: return@withLock null
            val searchDto = tvdbJson.decodeFromString<TvdbSearchResponse>(response)
            val jpnResult = searchDto.data.find { it.primaryLanguage == "jpn" || it.country == "jpn" }
                ?: searchDto.data.firstOrNull()
                ?: run {
                    Log.w(TVDB_LOG, "TVDB Search returned 0 results for '$sanitizedTitle'")
                    return@withLock null
                }
            val id = jpnResult.tvdbId.ifBlank { jpnResult.objectId.substringAfter("-") }
            val poster = jpnResult.imageUrl?.takeIf { it.isNotBlank() }?.let {
                if (it.startsWith("http")) it else "$TVDB_ARTWORK_BASE_URL$it"
            }
            val movieType = jpnResult.type == "movie" || jpnResult.objectId.startsWith("movie-")
            val overview = jpnResult.overviews[lang] ?: jpnResult.overview
            Quadruple(id, poster, movieType, overview)
        } catch (e: Exception) {
            Log.e(TVDB_LOG, "TVDB Search failed for '$sanitizedTitle': ${e.message}")
            return@withLock null
        }

        Log.d(TVDB_LOG, "TVDB Search found tvdbId='$tvdbId', isMovie=$isMovie, posterUrl='$posterUrl'")

        val mediaType = if (isMovie) "movies" else "series"

        // Fetch Extended Details for backdrop image
        val extendedUrl = "$TVDB_BASE_URL/$mediaType/$tvdbId/extended"
        val backdropUrl = try {
            val response = executeTvdbRequest(extendedUrl, apiKey)
            if (response != null) {
                val extDto = tvdbJson.decodeFromString<TvdbExtendedResponse>(response)
                extDto.data?.artworks?.find { it.type == 3 || it.type == 15 }?.image?.takeIf { it.isNotBlank() }?.let {
                    if (it.startsWith("http")) it else "$TVDB_ARTWORK_BASE_URL$it"
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        // Fetch Episodes (up to 3 pages) for series
        val epMap = mutableMapOf<Int, Triple<String?, String?, String?>>()

        if (!isMovie) {
            for (page in 0..2) {
                val episodesUrl = "$TVDB_BASE_URL/series/$tvdbId/episodes/default/$lang?page=$page"
                try {
                    val response = executeTvdbRequest(episodesUrl, apiKey) ?: break
                    val epDto = tvdbJson.decodeFromString<TvdbEpisodesResponse>(response)
                    val episodes = epDto.data?.episodes.orEmpty()
                    if (episodes.isEmpty()) break

                    val seasonEps = episodes.filter { it.seasonNumber == season }
                    for (ep in seasonEps) {
                        val epNum = ep.number
                        if (epNum > 0 && !epMap.containsKey(epNum)) {
                            val fullImgUrl = ep.image?.takeIf { it.isNotBlank() }?.let {
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

        Log.d(TVDB_LOG, "TVDB fetched ${epMap.size} episodes for season $season, backdropUrl=$backdropUrl")

        val metadata = TvdbMetadata(
            summary = frenchOverview,
            releaseDate = null,
            mainPosterUrl = posterUrl,
            seasonPosterUrl = posterUrl,
            backdropUrl = backdropUrl,
            author = null,
            artist = null,
            status = 0,
            genre = null,
            episodeSummaries = epMap,
            episodeOffset = 0,
        )

        tvdbMetadataCache[cacheKey] = Pair(metadata, System.currentTimeMillis())
        metadata
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
