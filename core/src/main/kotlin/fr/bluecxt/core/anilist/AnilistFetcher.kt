

package fr.bluecxt.core.anilist

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import fr.bluecxt.core.anilist.dto.AnilistGraphQlRequest
import fr.bluecxt.core.anilist.dto.AnilistGraphQlResponse
import fr.bluecxt.core.anilist.dto.AnilistMediaQueryData
import fr.bluecxt.core.anilist.dto.AnilistPageQueryData
import fr.bluecxt.core.anilist.utils.AnilistCache
import fr.bluecxt.core.anilist.utils.AnilistTitleMatcher
import fr.bluecxt.core.monitoring.ErrorWebhook.sendWebhook
import fr.bluecxt.core.source.Source
import fr.bluecxt.core.utils.runCatchingCancellable
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Taille de page de recherche et troncature des corps d'erreur. */
private const val SEARCH_PAGE_SIZE = 10
private const val MAX_ERROR_BODY_CHARS = 300

private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"
private val DEFAULT_RATE_LIMIT_DELAY: Duration = 1500.milliseconds
private val MAX_RATE_LIMIT_DELAY: Duration = 10.seconds
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

internal val anilistJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Recherche et retourne les métadonnées AniList à partir d'un titre d'anime.
 */
suspend fun Source.fetchAnilistMetadata(
    title: String,
    year: Int? = null,
    format: AnilistFormat? = null,
): AnilistMetadata? {
    if (title.isBlank()) return null

    val cleaned = AnilistTitleMatcher.cleanQuery(title)
    val cacheKey = "${cleaned.normalizedBase}|${cleaned.extractedSeason ?: 0}|${year ?: 0}|${format?.name.orEmpty()}"

    val (hit, cached) = AnilistCache.getBySearchKey(cacheKey)
    if (hit) return cached

    return runCatchingCancellable {
        val variables = buildJsonObject {
            put("search", JsonPrimitive(cleaned.searchTitle))
            put("perPage", JsonPrimitive(SEARCH_PAGE_SIZE))
            year?.let { put("year", JsonPrimitive(it)) }
            format?.let { put("format", JsonPrimitive(it.name)) }
        }

        val response = executeAnilistGraphQl<AnilistPageQueryData>(
            query = AnilistQueries.SEARCH_MEDIA_QUERY,
            variables = variables,
            context = "AniList search(title='$title', cleaned='${cleaned.searchTitle}')",
        )

        val candidates = response?.page?.media.orEmpty()
        val bestDto = AnilistTitleMatcher.selectBestMatch(candidates, cleaned, year, format)

        if (bestDto != null) {
            bestDto.toMetadata().also { metadata ->
                AnilistCache.put(metadata, searchKey = cacheKey)
            }
        } else {
            AnilistCache.putNegativeBySearchKey(cacheKey)
            null
        }
    }.onFailure { e ->
        sendWebhook(ANILIST_GRAPHQL_URL, "AniList search failure for title='$title'", e)
    }.getOrNull()
}

/**
 * Récupère les métadonnées AniList directement via son identifiant AniList (`id`).
 */
suspend fun Source.fetchAnilistMetadataById(anilistId: Int): AnilistMetadata? {
    if (anilistId <= 0) return null

    val (hit, cached) = AnilistCache.getByAnilistId(anilistId)
    if (hit) return cached

    return runCatchingCancellable {
        val variables = buildJsonObject {
            put("id", JsonPrimitive(anilistId))
        }

        val response = executeAnilistGraphQl<AnilistMediaQueryData>(
            query = AnilistQueries.MEDIA_BY_ID_QUERY,
            variables = variables,
            context = "AniList fetchById(id=$anilistId)",
            ignoreNotFound = true,
        )

        val dto = response?.media
        if (dto != null) {
            dto.toMetadata().also { AnilistCache.put(it) }
        } else {
            AnilistCache.putNegativeByAnilistId(anilistId)
            null
        }
    }.onFailure { e ->
        sendWebhook(ANILIST_GRAPHQL_URL, "AniList fetchById failure for id=$anilistId", e)
    }.getOrNull()
}

/**
 * Récupère les métadonnées AniList directement via l'identifiant MyAnimeList (`idMal`).
 */
suspend fun Source.fetchAnilistMetadataByMalId(malId: Int): AnilistMetadata? {
    if (malId <= 0) return null

    val (hit, cached) = AnilistCache.getByMalId(malId)
    if (hit) return cached

    return runCatchingCancellable {
        val variables = buildJsonObject {
            put("idMal", JsonPrimitive(malId))
        }

        val response = executeAnilistGraphQl<AnilistMediaQueryData>(
            query = AnilistQueries.MEDIA_BY_ID_QUERY,
            variables = variables,
            context = "AniList fetchByMalId(idMal=$malId)",
            ignoreNotFound = true,
        )

        val dto = response?.media
        if (dto != null) {
            dto.toMetadata().also { AnilistCache.put(it) }
        } else {
            AnilistCache.putNegativeByMalId(malId)
            null
        }
    }.onFailure { e ->
        sendWebhook(ANILIST_GRAPHQL_URL, "AniList fetchByMalId failure for idMal=$malId", e)
    }.getOrNull()
}

/**
 * Enrichit directement un [SAnime] via une recherche AniList et retourne les métadonnées associées.
 */
suspend fun Source.enrichAnimeWithAnilist(
    anime: SAnime,
    searchTitle: String = anime.title,
    year: Int? = null,
    format: AnilistFormat? = null,
    overrideThumbnail: Boolean = true,
): AnilistMetadata? {
    val metadata = fetchAnilistMetadata(searchTitle, year, format) ?: return null
    anime.applyAnilistMetadata(metadata, overrideThumbnail = overrideThumbnail)
    return metadata
}

/**
 * Exécute une requête POST GraphQL vers AniList avec gestion du Rate-Limit (HTTP 429 + Retry-After).
 */
internal suspend inline fun <reified T> Source.executeAnilistGraphQl(
    query: String,
    variables: JsonObject,
    context: String,
    ignoreNotFound: Boolean = false,
    maxRetries: Int = 1,
): T? {
    val payload = anilistJson.encodeToString(
        AnilistGraphQlRequest.serializer(),
        AnilistGraphQlRequest(query = query, variables = variables),
    )

    val headers = Headers.Builder()
        .add("Content-Type", "application/json")
        .add("Accept", "application/json")
        .build()

    for (attempt in 0..maxRetries) {
        val request = POST(
            url = ANILIST_GRAPHQL_URL,
            headers = headers,
            body = payload.toRequestBody(JSON_MEDIA_TYPE),
        )

        var retryDelay: Duration? = null

        val result: T? = client.newCall(request).await().use { response ->
            if (response.isRateLimited(attempt, maxRetries)) {
                retryDelay = response.readRateLimitDelay()
                return@use null
            }

            return@use parseGraphQlResponse<T>(response, context, ignoreNotFound)
        }

        val waitDuration = retryDelay
        if (waitDuration != null) {
            delay(waitDuration)
            continue
        }

        return result
    }

    return null
}

/**
 * Returns `true` when [this] response is a rate-limit answer that still has retries left.
 */
private fun Response.isRateLimited(attempt: Int, maxRetries: Int): Boolean = code == 429 && attempt < maxRetries

/**
 * Reads the delay announced by a rate-limited response, clamped to the allowed window.
 */
private fun Response.readRateLimitDelay(): Duration {
    val retryAfterSeconds = header("Retry-After")?.trim()?.toLongOrNull()
    return if (retryAfterSeconds != null && retryAfterSeconds > 0) {
        retryAfterSeconds.seconds.coerceIn(1.seconds, MAX_RATE_LIMIT_DELAY)
    } else {
        DEFAULT_RATE_LIMIT_DELAY
    }
}

/**
 * Reads and decodes one AniList GraphQL response, raising the same errors as the inline caller.
 */
private inline fun <reified T> parseGraphQlResponse(
    response: Response,
    context: String,
    ignoreNotFound: Boolean,
): T? {
    val rawBody = response.body.string()

    if (response.code == 404 && ignoreNotFound) {
        return null
    }

    if (!response.isSuccessful) {
        error(
            "HTTP ${response.code} from AniList [$context]: ${rawBody.take(MAX_ERROR_BODY_CHARS)}",
        )
    }

    if (rawBody.isBlank()) {
        error("Empty response body from AniList [$context]")
    }

    val graphQlResponse = anilistJson.decodeFromString<AnilistGraphQlResponse<T>>(rawBody)

    val errors = graphQlResponse.errors.orEmpty()
    if (errors.isNotEmpty()) {
        val isOnlyNotFound = ignoreNotFound && errors.all {
            it.status == 404 || it.message.contains("Not Found", ignoreCase = true)
        }
        if (isOnlyNotFound) return null

        val joinedMessages = errors.joinToString("; ") { "${it.message} (status=${it.status})" }
        error("GraphQL errors [$context]: $joinedMessages")
    }

    return graphQlResponse.data
}
