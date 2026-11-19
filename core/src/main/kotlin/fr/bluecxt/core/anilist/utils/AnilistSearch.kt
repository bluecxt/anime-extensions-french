package fr.bluecxt.core.anilist.utils

import fr.bluecxt.core.anilist.AnilistFormat
import fr.bluecxt.core.anilist.AnilistMetadata
import fr.bluecxt.core.anilist.dto.AnilistMediaDto
import fr.bluecxt.core.utils.normalize
import java.util.Collections
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Cache mémoire centralisé avec indexation croisée (ID AniList, ID MAL, clé de recherche).
 */
internal object AnilistCache {
    private val TTL_MS = 6.hours.inWholeMilliseconds
    private val NEGATIVE_TTL_MS = 15.minutes.inWholeMilliseconds

    private data class CacheEntry<T>(val value: T?, val timestamp: Long)

    private val byAnilistId = Collections.synchronizedMap(LinkedHashMap<Int, CacheEntry<AnilistMetadata>>(CACHE_MAX_BY_ANILIST_ID, CACHE_LOAD_FACTOR, true))
    private val byMalId = Collections.synchronizedMap(LinkedHashMap<Int, CacheEntry<AnilistMetadata>>(CACHE_MAX_BY_ANILIST_ID, CACHE_LOAD_FACTOR, true))
    private val bySearchKey = Collections.synchronizedMap(LinkedHashMap<String, CacheEntry<AnilistMetadata>>(CACHE_MAX_BY_SEARCH_KEY, CACHE_LOAD_FACTOR, true))

    fun getByAnilistId(id: Int): Pair<Boolean, AnilistMetadata?> = checkEntry(byAnilistId[id])
    fun getByMalId(idMal: Int): Pair<Boolean, AnilistMetadata?> = checkEntry(byMalId[idMal])
    fun getBySearchKey(key: String): Pair<Boolean, AnilistMetadata?> = checkEntry(bySearchKey[key])

    private fun <T> checkEntry(entry: CacheEntry<T>?): Pair<Boolean, T?> {
        if (entry == null) return false to null
        val ttl = if (entry.value == null) NEGATIVE_TTL_MS else TTL_MS
        if (System.currentTimeMillis() - entry.timestamp > ttl) return false to null
        return true to entry.value
    }

    fun put(metadata: AnilistMetadata, searchKey: String? = null) {
        val entry = CacheEntry(metadata, System.currentTimeMillis())
        byAnilistId[metadata.anilistId] = entry
        metadata.malId?.let { byMalId[it] = entry }
        searchKey?.let { bySearchKey[it] = entry }
    }

    fun putNegativeByAnilistId(id: Int) {
        byAnilistId[id] = CacheEntry(null, System.currentTimeMillis())
    }
    fun putNegativeByMalId(idMal: Int) {
        byMalId[idMal] = CacheEntry(null, System.currentTimeMillis())
    }
    fun putNegativeBySearchKey(key: String) {
        bySearchKey[key] = CacheEntry(null, System.currentTimeMillis())
    }

    fun clear() {
        byAnilistId.clear()
        byMalId.clear()
        bySearchKey.clear()
    }
}

/** Facteur de charge des caches LRU. */
private const val CACHE_LOAD_FACTOR = 0.75f

/** Capacités des caches d'AniListSearch. */
private const val CACHE_MAX_BY_ANILIST_ID = 300
private const val CACHE_MAX_BY_SEARCH_KEY = 400

/** Similarité minimale pour accepter un résultat de recherche. */
private const val MIN_SEARCH_SIMILARITY = 0.25

/** Ajustements de score appliqués par le classement heuristique. */
private const val SCORE_SEASON_MATCH_BONUS = 0.18
private const val SCORE_SEASON_MISMATCH_PENALTY = 0.25
private const val SCORE_SEASON_ABSENT_PENALTY = 0.15
private const val SCORE_YEAR_EXACT_BONUS = 0.15
private const val SCORE_YEAR_ADJACENT_BONUS = 0.05
private const val SCORE_YEAR_MISMATCH_PENALTY = 0.15
private const val SCORE_FORMAT_MATCH_BONUS = 0.10
private const val SCORE_FORMAT_PREFERRED_BONUS = 0.04
private const val SCORE_FORMAT_MUSIC_PENALTY = 0.20

/** Poids du logarithme de popularité dans le score. */
private const val SCORE_POPULARITY_LOG_WEIGHT = 0.004

/** Pondération des deux mesures de similarité de titre. */
private const val SIMILARITY_DICE_WEIGHT = 0.65
private const val SIMILARITY_TOKEN_WEIGHT = 0.35

/**
 * Nettoyage des suffixes FR et scoring de pertinence appuyé sur `String.normalize()`.
 */
internal object AnilistTitleMatcher {
    private val FR_NOISE_REGEX = Regex(
        """\b(vostfr|vost|vf|vff|vfq|multi|blu-?ray|bd|uncensored|non\s+censur[eé]|streaming|complet)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val BRACKETS_REGEX =
        Regex("""\[[^\]]*]|\([^)]*(?:vostfr|vf|uncensored|tv)[^)]*\)""", RegexOption.IGNORE_CASE)
    private val SAISON_FR_REGEX = Regex("""\bsaison\s*(\d+)\b""", RegexOption.IGNORE_CASE)
    private val SEASON_EN_REGEX =
        Regex("""\b(?:season\s*(\d+)|(\d+)(?:nd|rd|th)\s+season|s(\d+))\b""", RegexOption.IGNORE_CASE)
    private val PARTIE_FR_REGEX = Regex("""\bpartie\s*(\d+)\b""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    data class CleanedQuery(
        val searchTitle: String,
        val normalizedBase: String,
        val extractedSeason: Int?,
    )

    fun cleanQuery(rawTitle: String): CleanedQuery {
        var working = rawTitle
            .replace(BRACKETS_REGEX, " ")
            .replace(FR_NOISE_REGEX, " ")

        val seasonFrMatch = SAISON_FR_REGEX.find(working)
        val seasonEnMatch = SEASON_EN_REGEX.find(working)
        val extractedSeason = seasonFrMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: seasonEnMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()

        working = working
            .replace(SAISON_FR_REGEX) { match ->
                val s = match.groupValues[1].toIntOrNull() ?: 1
                if (s == 1) "" else "Season $s"
            }
            .replace(PARTIE_FR_REGEX, "Part $1")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

        val finalSearchTitle = working.ifEmpty { rawTitle.trim() }

        return CleanedQuery(
            searchTitle = finalSearchTitle,
            normalizedBase = finalSearchTitle.normalize(),
            extractedSeason = extractedSeason,
        )
    }

    fun selectBestMatch(
        candidates: List<AnilistMediaDto>,
        cleanedQuery: CleanedQuery,
        targetYear: Int?,
        targetFormat: AnilistFormat?,
    ): AnilistMediaDto? {
        if (candidates.isEmpty()) return null

        return candidates
            .map { media -> media to scoreCandidate(media, cleanedQuery, targetYear, targetFormat) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= MIN_SEARCH_SIMILARITY }
            ?.first
    }

    private fun scoreCandidate(
        media: AnilistMediaDto,
        query: CleanedQuery,
        targetYear: Int?,
        targetFormat: AnilistFormat?,
    ): Double {
        val candidateTitles = buildCandidateTitles(media)

        if (candidateTitles.isEmpty()) return 0.0

        val bestTitleScore = candidateTitles.maxOf { candidateTitle ->
            computeSimilarity(query.normalizedBase, candidateTitle.normalize())
        }

        var score = bestTitleScore

        val candidateCombinedTitles = candidateTitles.joinToString(" ")
        val candidateSeason = detectCandidateSeason(candidateCombinedTitles)

        score = applySeasonAdjustment(score, query, candidateSeason, candidateCombinedTitles)

        val mediaYear = media.seasonYear ?: media.startDate?.year
        score = applyYearAdjustment(score, mediaYear, targetYear)

        score = applyFormatAdjustment(score, media.format, targetFormat)

        val popBonus = media.popularity?.takeIf { it > 0 }?.let { ln(it.toDouble()) * SCORE_POPULARITY_LOG_WEIGHT } ?: 0.0
        return score + popBonus
    }

    private fun buildCandidateTitles(media: AnilistMediaDto): List<String> = buildList {
        media.title?.romaji?.let(::add)
        media.title?.english?.let(::add)
        media.title?.native?.let(::add)
        addAll(media.synonyms)
    }

    private fun detectCandidateSeason(candidateCombinedTitles: String): Int? {
        val candidateSeasonMatch = SEASON_EN_REGEX.find(candidateCombinedTitles)
            ?: Regex("""\bii+\b""", RegexOption.IGNORE_CASE).find(candidateCombinedTitles)
        return candidateSeasonMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
    }

    private fun applySeasonAdjustment(
        score: Double,
        query: CleanedQuery,
        candidateSeason: Int?,
        candidateCombinedTitles: String,
    ): Double {
        var adjusted = score

        if (query.extractedSeason != null && query.extractedSeason > 1) {
            if (candidateSeason == query.extractedSeason ||
                candidateCombinedTitles.contains(query.extractedSeason.toString())
            ) {
                adjusted += SCORE_SEASON_MATCH_BONUS
            } else {
                adjusted -= SCORE_SEASON_MISMATCH_PENALTY
            }
        } else if (query.extractedSeason == null || query.extractedSeason == 1) {
            if (candidateSeason != null && candidateSeason > 1) {
                adjusted -= SCORE_SEASON_ABSENT_PENALTY
            }
        }

        return adjusted
    }

    private fun applyYearAdjustment(score: Double, mediaYear: Int?, targetYear: Int?): Double {
        var adjusted = score

        if (targetYear != null && mediaYear != null) {
            when {
                mediaYear == targetYear -> adjusted += SCORE_YEAR_EXACT_BONUS
                abs(mediaYear - targetYear) == 1 -> adjusted += SCORE_YEAR_ADJACENT_BONUS
                else -> adjusted -= SCORE_YEAR_MISMATCH_PENALTY
            }
        }

        return adjusted
    }

    private fun applyFormatAdjustment(score: Double, mediaFormat: String?, targetFormat: AnilistFormat?): Double {
        var adjusted = score

        if (targetFormat != null && mediaFormat == targetFormat.name) {
            adjusted += SCORE_FORMAT_MATCH_BONUS
        } else if (targetFormat == null) {
            when (mediaFormat) {
                "TV", "ONA", "MOVIE" -> adjusted += SCORE_FORMAT_PREFERRED_BONUS
                "MUSIC" -> adjusted -= SCORE_FORMAT_MUSIC_PENALTY
            }
        }

        return adjusted
    }

    private fun computeSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0

        val dice = bigramsSimilarity(a, b)
        val tokensA = a.split(" ").filter { it.isNotBlank() }.toSet()
        val tokensB = b.split(" ").filter { it.isNotBlank() }.toSet()
        val tokenOverlap = if (tokensA.isNotEmpty() && tokensB.isNotEmpty()) {
            val intersection = tokensA.intersect(tokensB).size.toDouble()
            intersection / max(tokensA.size, tokensB.size)
        } else {
            0.0
        }

        return (dice * SIMILARITY_DICE_WEIGHT) + (tokenOverlap * SIMILARITY_TOKEN_WEIGHT)
    }

    private fun bigramsSimilarity(s1: String, s2: String): Double {
        if (s1.length < 2 || s2.length < 2) return if (s1 == s2) 1.0 else 0.0
        val bg1 = (0 until s1.length - 1).map { s1.substring(it, it + 2) }
        val bg2 = (0 until s2.length - 1).map { s2.substring(it, it + 2) }.toMutableList()
        var matches = 0
        for (pair in bg1) {
            val idx = bg2.indexOf(pair)
            if (idx != -1) {
                matches++
                bg2.removeAt(idx)
            }
        }
        return (2.0 * matches) / (bg1.size + (s2.length - 1))
    }
}
