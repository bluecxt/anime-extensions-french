// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.anilist

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import fr.bluecxt.core.ANIMESAMA_LOG
import fr.bluecxt.core.source.Source
import fr.bluecxt.core.utils.normalize
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

data class AniListEpisodeMetadata(
    val title: String?,
    val thumbnail: String?,
    val summary: String? = null,
)

data class AniListMetadata(
    val title: String?,
    val summary: String?,
    val releaseDate: String?,
    val mainPosterUrl: String?,
    val seasonPosterUrl: String?,
    val backdropUrl: String?,
    val author: String?,
    val artist: String?,
    val status: Int,
    val genre: String?,
    val episodeSummaries: Map<Int, AniListEpisodeMetadata>,
    val episodeCount: Int,
    val seasonEpisodeCounts: Map<Int, Int>,
    val seriesId: Long?,
    val episodeOffset: Int = 0,
)

/** AniList client and short lived metadata cache shared by extensions. */
class AniListFetcher(private val source: Source) {
    private val cache = ConcurrentHashMap<Int, Pair<AniListMetadata, Long>>()
    private val seasonIds = ConcurrentHashMap<Pair<Int, Int>, Int>()
    private val seasonCounts = ConcurrentHashMap<Int, Map<Int, Int>>()
    private val requestMutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun fetch(title: String, titles: Set<String>, linkedId: Int? = null, seasonOrdinal: Int = 1): AniListMetadata? {
        if (linkedId != null) {
            val id = if (seasonOrdinal <= 1) linkedId else resolveSeasonId(linkedId, seasonOrdinal)
            if (id != null) {
                cached(id)?.let { return it.copy(seasonEpisodeCounts = seasonCounts[linkedId].orEmpty()) }
                mediaById(id)?.let { media ->
                    val metadata = parse(media).copy(seasonEpisodeCounts = seasonCounts[linkedId].orEmpty())
                    cache[id] = metadata to System.currentTimeMillis()
                    return metadata
                }
            }
        }
        val expected = (titles + title).map { it.normalize() }.filter(String::isNotBlank).toSet()
        for (candidate in (listOf(title) + titles).map(String::trim).filter(String::isNotBlank).distinct()) {
            val query = """query (${'$'}search: String) { Page(page: 1, perPage: 10) { media(search: ${'$'}search, type: ANIME, isAdult: false) { id title { romaji english native } description(asHtml: false) coverImage { extraLarge large } bannerImage startDate { year } episodes genres studios { nodes { name } } status streamingEpisodes { title thumbnail } } } }"""
            val root = request(query, buildJsonObject { put("search", candidate) }) ?: continue
            val media = root["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media")?.jsonArray.orEmpty()
            val best = media.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .map { it to score(it, expected) }.maxByOrNull { it.second }
                ?.takeIf { it.second >= 85 }?.first ?: continue
            return parse(best)
        }
        return null
    }

    private fun cached(id: Int): AniListMetadata? {
        val item = cache[id] ?: return null
        if (System.currentTimeMillis() - item.second > CACHE_TTL) {
            cache.remove(id, item)
            return null
        }
        return item.first
    }

    private suspend fun mediaById(id: Int): JsonObject? {
        val query = """query (${'$'}id: Int!) { Media(id: ${'$'}id, type: ANIME) { id title { romaji english native } description(asHtml: false) coverImage { extraLarge large } bannerImage startDate { year } episodes genres studios { nodes { name } } status streamingEpisodes { title thumbnail } } }"""
        return request(query, buildJsonObject { put("id", id) })?.get("data")?.jsonObject?.get("Media")?.jsonObject
    }

    private suspend fun resolveSeasonId(root: Int, ordinal: Int): Int? {
        seasonIds[root to ordinal]?.let { return it }
        var id = root
        var season = 1
        val ordinalIds = mutableMapOf(1 to root)
        val counts = mutableMapOf<Int, Int>()
        repeat(12) {
            if (season >= ordinal) return@repeat
            val query = """query (${'$'}id: Int!) { Media(id: ${'$'}id, type: ANIME) { episodes relations { edges { relationType node { id format episodes startDate { year } } } } } }"""
            val media = request(query, buildJsonObject { put("id", id) })?.get("data")?.jsonObject?.get("Media")?.jsonObject ?: return null
            val ordered = media["relations"]?.jsonObject?.get("edges")?.jsonArray.orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .filter { it["relationType"]?.jsonPrimitive?.contentOrNull == "SEQUEL" }
                .mapNotNull { it["node"]?.jsonObject }.sortedBy { it["startDate"]?.jsonObject?.get("year")?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
            val tv = ordered.firstOrNull { it["format"]?.jsonPrimitive?.contentOrNull in TV_FORMATS }
            val next = tv ?: ordered.firstOrNull() ?: return null
            id = next["id"]?.jsonPrimitive?.intOrNull ?: return null
            if (tv != null) {
                counts[season] = media["episodes"]?.jsonPrimitive?.intOrNull ?: 0
                season++
                ordinalIds[season] = id
            }
        }
        if (season < ordinal) return null
        ordinalIds.forEach { (number, value) -> seasonIds[root to number] = value }
        seasonCounts[root] = counts.filterValues { it > 0 }
        return id
    }

    private suspend fun request(query: String, variables: JsonObject): JsonObject? = requestMutex.withLock {
        val body = buildJsonObject { put("query", query); put("variables", variables) }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        for (attempt in 0..3) {
            val wait = MIN_INTERVAL - (System.currentTimeMillis() - lastRequestAt)
            if (wait > 0) delay(wait)
            try {
                val response = source.client.newCall(POST(URL, source.headersBuilder().build(), body)).await()
                val responseCode = response.code
                lastRequestAt = System.currentTimeMillis()
                if (responseCode == 429 || responseCode >= 500) {
                    response.close()
                    if (attempt < 3) {
                        delay((1L shl attempt) * 1000L)
                        continue
                    }
                    Log.w(ANIMESAMA_LOG, "AniList returned HTTP $responseCode")
                    return@withLock null
                }
                response.use {
                    if (!it.isSuccessful) { Log.w(ANIMESAMA_LOG, "AniList returned HTTP ${it.code}"); return@withLock null }
                    return@withLock Json.parseToJsonElement(it.body.string()).jsonObject
                }
            } catch (e: Exception) {
                Log.w(ANIMESAMA_LOG, "AniList GraphQL request failed: ${e.message}")
                return@withLock null
            }
        }
        null
    }

    private fun parse(media: JsonObject): AniListMetadata {
        val title = media["title"]?.jsonObject
        val episodes = media["streamingEpisodes"]?.jsonArray.orEmpty().mapNotNull { element ->
            val ep = element.jsonObject; val text = ep["title"]?.jsonPrimitive?.contentOrNull
            val n = text?.let { Regex("(?i)(?:episode|ep\\.?|#)\\s*(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            if (n == null) null else n to AniListEpisodeMetadata(text, ep["thumbnail"]?.jsonPrimitive?.contentOrNull)
        }.toMap()
        val studios = media["studios"]?.jsonObject?.get("nodes")?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.filter(String::isNotBlank).joinToString(", ").ifBlank { null }
        val year = media["startDate"]?.jsonObject?.get("year")?.jsonPrimitive?.intOrNull?.toString()
        return AniListMetadata(
            title = title?.get("english")?.jsonPrimitive?.contentOrNull ?: title?.get("romaji")?.jsonPrimitive?.contentOrNull,
            summary = media["description"]?.jsonPrimitive?.contentOrNull?.let { org.jsoup.Jsoup.parseBodyFragment(it).text() },
            releaseDate = year, mainPosterUrl = media["coverImage"]?.jsonObject?.let { it["extraLarge"]?.jsonPrimitive?.contentOrNull ?: it["large"]?.jsonPrimitive?.contentOrNull },
            seasonPosterUrl = media["coverImage"]?.jsonObject?.let { it["extraLarge"]?.jsonPrimitive?.contentOrNull ?: it["large"]?.jsonPrimitive?.contentOrNull },
            backdropUrl = media["bannerImage"]?.jsonPrimitive?.contentOrNull, author = studios, artist = studios,
            status = when (media["status"]?.jsonPrimitive?.contentOrNull) { "FINISHED" -> SAnime.COMPLETED; "RELEASING", "NOT_YET_RELEASED", "HIATUS" -> SAnime.ONGOING; else -> SAnime.UNKNOWN },
            genre = media["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.joinToString(", ")?.ifBlank { null },
            episodeSummaries = episodes, episodeCount = media["episodes"]?.jsonPrimitive?.intOrNull ?: 0,
            seasonEpisodeCounts = emptyMap(), seriesId = media["id"]?.jsonPrimitive?.longOrNull,
            episodeOffset = 0,
        )
    }

    private fun score(media: JsonObject, expected: Set<String>): Int {
        val names = media["title"]?.jsonObject?.values.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.map { it.normalize() }
        return when { names.any { it in expected } -> 100; names.any { n -> n.length >= 8 && expected.any { e -> e.length >= 8 && (e.startsWith(n) || n.startsWith(e)) } } -> 85; else -> 0 }
    }

    companion object { private const val URL = "https://graphql.anilist.co"; private const val MIN_INTERVAL = 700L; private const val CACHE_TTL = 15 * 60 * 1000L; private val TV_FORMATS = setOf("TV", "TV_SHORT", "ONA") }
}
