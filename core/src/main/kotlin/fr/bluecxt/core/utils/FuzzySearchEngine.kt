// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.utils

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Holds the extracted textual fields of an item [T] used for search scoring and deduplication.
 *
 * @property primaryTitle The main title of the item (weighted highest during matching).
 * @property alternativeTitles Secondary or synonymous titles (e.g., romaji, english, or short names).
 * @property tags Associated categories or genres (weighted lowest during substring matching).
 * @property distinctId Optional unique identifier used to deduplicate results in the final output.
 */
class SearchMetadata(
    val primaryTitle: String,
    val alternativeTitles: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val distinctId: Any? = null,
)

/**
 * Functional adapter that projects an arbitrary item of type [T] into [SearchMetadata].
 *
 * @param T The type of items being searched.
 */
fun interface SearchableAdapter<T> {
    /**
     * Extracts searchable metadata from the given [item].
     */
    fun extract(item: T): SearchMetadata
}

/** Longueur de token sous laquelle aucune faute de frappe n'est tolérée. */
private const val TOKEN_TOLERANCE_NONE_MAX_LENGTH = 3

/** Longueur de token sous laquelle une seule faute est tolérée. */
private const val TOKEN_TOLERANCE_ONE_MAX_LENGTH = 6

/** Tolérance par défaut pour un token long. */
private const val TOKEN_TOLERANCE_DEFAULT = 2

/** Longueur de requête sous laquelle la tolérance globale est nulle. */
private const val QUERY_TOLERANCE_NONE_MAX_LENGTH = 4

/** Longueur de requête sous laquelle la tolérance globale vaut 1. */
private const val QUERY_TOLERANCE_ONE_MAX_LENGTH = 8

/** Longueur de requête sous laquelle la tolérance globale vaut 2. */
private const val QUERY_TOLERANCE_TWO_MAX_LENGTH = 14

/** Tolérance globale par défaut. */
private const val QUERY_TOLERANCE_DEFAULT = 3

/** Longueur minimale de requête pour lancer la passe fuzzy. */
private const val MIN_LENGTH_FOR_FUZZY = 3

/** Capacité initiale des collections de travail. */
private const val EXACT_MATCH_CAPACITY = 32
private const val FUZZY_CANDIDATE_CAPACITY = 128
private const val FUZZY_MATCH_CAPACITY = 16

/** Scores de pertinence de la passe 1. */
private const val SCORE_EXACT_TITLE = 120
private const val SCORE_TITLE_PREFIX = 105
private const val SCORE_TITLE_MATCH = 95
private const val SCORE_SIMILAR_TITLE = 80
private const val SCORE_TAG_MATCH = 60

/** Similarité minimale pour retenir un candidat de la passe 2. */
private const val MIN_FUZZY_SIMILARITY = 0.6

/** Poids de la distance d'édition dans le score fuzzy. */
private const val FUZZY_DISTANCE_PENALTY = 0.6

/** Dernier code ASCII : au-delà, le caractère est non-ASCII. */
private const val ASCII_LIMIT = 127

/** Écart entre un caractère ASCII majuscule et son équivalent minuscule. */
private const val ASCII_LOWERCASE_OFFSET = 32

/**
 * High-performance polymorphic search engine implementing a two-pass weighted substring
 * and bounded Damerau-Levenshtein fuzzy matching algorithm.
 *
 * Pass 1 performs fast O(N) normalized substring matching across titles, alternative titles, and tags.
 * Pass 2 (fuzzy fallback) is only executed if Pass 1 yields no results and the normalized query
 * is at least 3 characters long.
 *
 * @param T The type of elements to search through.
 * @property adapter The adapter used to extract [SearchMetadata] from each element.
 */
class FuzzySearchEngine<T>(
    private val defaultLimit: Int = 20,
    private val adapter: SearchableAdapter<T>,
) {

    /**
     * Internal wrapper pairing a matched item with its computed relevance score.
     */
    private class ScoredEntry<T>(
        val item: T,
        val primaryTitle: String,
        val distinctId: Any?,
        val score: Int,
    ) : Comparable<ScoredEntry<T>> {
        override fun compareTo(other: ScoredEntry<T>): Int {
            val scoreCmp = other.score.compareTo(this.score)
            if (scoreCmp != 0) return scoreCmp
            return this.primaryTitle.compareTo(other.primaryTitle, ignoreCase = true)
        }
    }

    /**
     * Reusable row buffers for Damerau-Levenshtein calculations to avoid heap allocations
     * inside tight loops.
     */
    private class LevenshteinWorkspace(initialCapacity: Int = 128) {
        var prev2 = IntArray(initialCapacity)
        var prev = IntArray(initialCapacity)
        var cur = IntArray(initialCapacity)

        fun ensureCapacity(size: Int) {
            if (prev.size < size) {
                val newSize = max(size, prev.size * 2)
                prev2 = IntArray(newSize)
                prev = IntArray(newSize)
                cur = IntArray(newSize)
            }
        }
    }

    /**
     * Precomputed query tokens, tolerances, and workspace for Pass 2 fuzzy matching.
     */
    private class QueryContext(val nq: String) {
        val tokens: Array<String> = nq.splitToSequence(' ').filter { it.isNotEmpty() }.toList().toTypedArray()
        val tokenTolerances: IntArray = IntArray(tokens.size) { i ->
            val len = tokens[i].length
            when {
                len <= TOKEN_TOLERANCE_NONE_MAX_LENGTH -> 0
                len <= TOKEN_TOLERANCE_ONE_MAX_LENGTH -> 1
                else -> TOKEN_TOLERANCE_DEFAULT
            }
        }
        val flat: String = nq.replace(" ", "")
        val globalTolerance: Int = when {
            nq.length <= QUERY_TOLERANCE_NONE_MAX_LENGTH -> 0
            nq.length <= QUERY_TOLERANCE_ONE_MAX_LENGTH -> 1
            nq.length <= QUERY_TOLERANCE_TWO_MAX_LENGTH -> 2
            else -> QUERY_TOLERANCE_DEFAULT
        }
        val workspace = LevenshteinWorkspace()
    }

    /**
     * Searches [items] for elements matching [query], ordered by descending relevance score
     * and alphabetical title tie-breaking.
     *
     * Scoring weights:
     * - Exact primary title match: 120 pts
     * - Primary title prefix match: 105 pts
     * - Primary title substring match: 95 pts
     * - Alternative title substring match: 80 pts
     * - Tag / genre substring match: 60 pts
     * - Fuzzy fallback match (>= 60% similarity): up to 50 pts
     *
     * @param query The raw user search query.
     * @param items The collection of items to search through.
     * @param limit Maximum number of results to return (defaults to 20).
     * @return A deduplicated, sorted list of matching items.
     */
    fun search(
        query: String,
        items: Iterable<T>,
        limit: Int = defaultLimit,
    ): List<T> {
        if (limit <= 0) return emptyList()
        val nq = fastNormalize(query)
        if (nq.isEmpty()) return emptyList()

        val exactMatches = ArrayList<ScoredEntry<T>>(EXACT_MATCH_CAPACITY)
        val canRunFuzzy = nq.length >= MIN_LENGTH_FOR_FUZZY
        val candidatesForFuzzy = if (canRunFuzzy) ArrayList<Pair<T, SearchMetadata>>(FUZZY_CANDIDATE_CAPACITY) else null

        // --- PASS 1: Exact and substring matching ---
        for (item in items) {
            val meta = adapter.extract(item)
            val titleN = fastNormalize(meta.primaryTitle)

            val titleMatch = titleN.isNotEmpty() && titleN.contains(nq)
            var simMatch = false
            if (!titleMatch) {
                val alt = meta.alternativeTitles
                for (i in alt.indices) {
                    if (fastNormalize(alt[i]).contains(nq)) {
                        simMatch = true
                        break
                    }
                }
            }

            var tagMatch = false
            if (!titleMatch && !simMatch) {
                val tags = meta.tags
                for (i in tags.indices) {
                    if (fastNormalize(tags[i]).contains(nq)) {
                        tagMatch = true
                        break
                    }
                }
            }

            if (titleMatch || simMatch || tagMatch) {
                val score = when {
                    titleN == nq -> SCORE_EXACT_TITLE
                    titleN.startsWith(nq) -> SCORE_TITLE_PREFIX
                    titleMatch -> SCORE_TITLE_MATCH
                    simMatch -> SCORE_SIMILAR_TITLE
                    else -> SCORE_TAG_MATCH
                }
                exactMatches.add(ScoredEntry(item, meta.primaryTitle, meta.distinctId, score))
            } else if (exactMatches.isEmpty() && candidatesForFuzzy != null) {
                candidatesForFuzzy.add(item to meta)
            }
        }

        val finalPool: MutableList<ScoredEntry<T>> = if (exactMatches.isNotEmpty()) {
            exactMatches
        } else if (!candidatesForFuzzy.isNullOrEmpty()) {
            // --- PASS 2: Fuzzy fallback (executed only when Pass 1 returns 0 matches) ---
            val qCtx = QueryContext(nq)
            val fuzzyMatches = ArrayList<ScoredEntry<T>>(FUZZY_MATCH_CAPACITY)

            for (i in candidatesForFuzzy.indices) {
                val (item, meta) = candidatesForFuzzy[i]
                val fs = computeBestFuzzy(qCtx, meta.primaryTitle, meta.alternativeTitles)
                if (fs >= MIN_FUZZY_SIMILARITY) {
                    fuzzyMatches.add(
                        ScoredEntry(
                            item = item,
                            primaryTitle = meta.primaryTitle,
                            distinctId = meta.distinctId,
                            score = (fs * 50.0).toInt(),
                        ),
                    )
                }
            }
            fuzzyMatches
        } else {
            return emptyList()
        }

        if (finalPool.isEmpty()) return emptyList()

        finalPool.sort()

        val result = ArrayList<T>(min(limit, finalPool.size))
        val seenIds = HashSet<Any>(min(limit * 2, finalPool.size))

        for (i in finalPool.indices) {
            val entry = finalPool[i]
            val id = entry.distinctId
            if (id != null && !seenIds.add(id)) continue
            result.add(entry.item)
            if (result.size >= limit) break
        }

        return result
    }

    /**
     * Computes the highest fuzzy similarity score across the primary title and all alternative titles.
     */
    private fun computeBestFuzzy(
        qCtx: QueryContext,
        primaryTitle: String,
        altTitles: List<String>,
    ): Double {
        var best = 0.0
        val normPrimary = fastNormalize(primaryTitle)
        if (normPrimary.isNotEmpty()) {
            best = fuzzySim(qCtx, normPrimary)
            if (best >= 1.0) return best
        }

        for (i in altTitles.indices) {
            val normAlt = fastNormalize(altTitles[i])
            if (normAlt.isNotEmpty()) {
                val s = fuzzySim(qCtx, normAlt)
                if (s > best) {
                    best = s
                    if (best >= 1.0) return best
                }
            }
        }
        return best
    }

    /**
     * Evaluates the fuzzy similarity ratio (between `0.0` and `1.0`) between the query context
     * and a normalized [target] string using token-level and global edit distances.
     */
    private fun fuzzySim(qCtx: QueryContext, target: String): Double {
        val nq = qCtx.nq
        if (target.isEmpty()) return 0.0
        if (target.contains(nq)) return 1.0

        val qt = qCtx.tokens
        val tt = target.split(' ')
        val ws = qCtx.workspace

        var total = 0.0
        var ok = true

        for (qi in qt.indices) {
            val q = qt[qi]
            val tol = qCtx.tokenTolerances[qi]
            var bestSub = 0.0

            for (ti in tt.indices) {
                val t = tt[ti]
                if (t.isEmpty()) continue
                if (t.startsWith(q)) {
                    bestSub = 1.0
                    break
                }
                val headLen = min(t.length, q.length + 1)
                val d1 = damerauLevenshteinBounded(q, t, 0, t.length, tol, ws)
                val d2 = if (headLen < t.length) {
                    damerauLevenshteinBounded(q, t, 0, headLen, tol, ws)
                } else {
                    d1
                }
                val d = min(d1, d2)
                if (d <= tol) {
                    val score = 1.0 - (d.toDouble() / max(q.length, 1)) * FUZZY_DISTANCE_PENALTY
                    if (score > bestSub) bestSub = score
                }
            }

            if (bestSub == 0.0) {
                ok = false
                break
            }
            total += bestSub
        }

        var best = if (ok && qt.isNotEmpty()) total / qt.size else 0.0

        val tol2 = qCtx.globalTolerance
        val flatQuery = qCtx.flat
        val targetFlat = target.replace(" ", "")

        best = evalGlobalCandidate(nq, target, target.length, tol2, ws, best)
        best = evalGlobalCandidate(nq, target, min(target.length, nq.length + 2), tol2, ws, best)
        best = evalGlobalCandidate(flatQuery, targetFlat, targetFlat.length, tol2, ws, best)
        best = evalGlobalCandidate(flatQuery, targetFlat, min(targetFlat.length, flatQuery.length + 2), tol2, ws, best)

        return best
    }

    private fun evalGlobalCandidate(
        q: String,
        target: String,
        targetEnd: Int,
        tol: Int,
        ws: LevenshteinWorkspace,
        currentBest: Double,
    ): Double {
        val d = damerauLevenshteinBounded(q, target, 0, targetEnd, tol, ws)
        if (d <= tol) {
            val score = 1.0 - (d.toDouble() / max(q.length, 1)) * FUZZY_DISTANCE_PENALTY
            if (score > currentBest) return score
        }
        return currentBest
    }

    /**
     * Computes the optimal string alignment (restricted Damerau-Levenshtein) distance between [a]
     * and the substring of [b] from [bStart] to [bEnd], bounded by [maxDist].
     *
     * Uses early termination to abort in O(1) if length difference exceeds [maxDist], or mid-row
     * if all cells in the current dynamic programming row exceed [maxDist].
     *
     * @return The edit distance if `<= maxDist`, or `maxDist + 1` if the threshold is exceeded.
     */
    private fun damerauLevenshteinBounded(
        a: String,
        b: String,
        bStart: Int,
        bEnd: Int,
        maxDist: Int,
        ws: LevenshteinWorkspace,
    ): Int {
        val al = a.length
        val bl = bEnd - bStart
        if (abs(al - bl) > maxDist) return maxDist + 1
        if (al == 0) return bl
        if (bl == 0) return al

        if (maxDist == 0) {
            for (i in 0 until al) {
                if (a[i] != b[bStart + i]) return 1
            }
            return 0
        }

        ws.ensureCapacity(bl + 1)
        var prev2 = ws.prev2
        var prev = ws.prev
        var cur = ws.cur

        for (j in 0..bl) prev[j] = j

        for (i in 1..al) {
            cur[0] = i
            var minInRow = cur[0]
            val ca = a[i - 1]

            for (j in 1..bl) {
                val cb = b[bStart + j - 1]
                val cost = if (ca == cb) 0 else 1
                var v = min(
                    min(cur[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost,
                )
                if (i > 1 && j > 1 && ca == b[bStart + j - 2] && a[i - 2] == cb) {
                    v = min(v, prev2[j - 2] + 1)
                }
                cur[j] = v
                if (v < minInRow) minInRow = v
            }

            if (minInRow > maxDist) return maxDist + 1

            val tmp = prev2
            prev2 = prev
            prev = cur
            cur = tmp
        }

        ws.prev2 = prev2
        ws.prev = prev
        ws.cur = cur
        return prev[bl]
    }

    companion object {
        /**
         * Single-pass string normalizer that strips diacritics, converts characters to lowercase
         * alphanumeric (`[a-z0-9]`), collapses consecutive non-alphanumeric delimiters into a single
         * space, and trims leading/trailing whitespace.
         *
         * Includes an ASCII fast-path that bypasses [Normalizer.normalize] when no non-ASCII
         * code points are present.
         */
        fun fastNormalize(input: String?): String {
            if (input.isNullOrEmpty()) return ""

            var hasNonAscii = false
            for (i in input.indices) {
                if (input[i].code > ASCII_LIMIT) {
                    hasNonAscii = true
                    break
                }
            }

            val src = if (hasNonAscii) {
                Normalizer.normalize(input, Normalizer.Form.NFD)
            } else {
                input
            }

            val out = CharArray(src.length)
            var outLen = 0
            var lastWasSpace = true

            for (i in src.indices) {
                val c = src[i]
                val code = c.code

                if (code > ASCII_LIMIT && Character.getType(c).toByte() == Character.NON_SPACING_MARK) {
                    continue
                }

                val normChar = when (c) {
                    in 'a'..'z', in '0'..'9' -> c
                    in 'A'..'Z' -> (code + ASCII_LOWERCASE_OFFSET).toChar()
                    else -> ' '
                }

                if (normChar == ' ') {
                    if (!lastWasSpace) {
                        out[outLen++] = ' '
                        lastWasSpace = true
                    }
                } else {
                    out[outLen++] = normChar
                    lastWasSpace = false
                }
            }

            if (outLen > 0 && out[outLen - 1] == ' ') {
                outLen--
            }

            return if (outLen == 0) "" else String(out, 0, outLen)
        }
    }
}

/**
 * Performs a weighted substring and fuzzy search over this [Iterable] using the provided [adapter].
 *
 * @param query The raw search query.
 * @param limit Maximum number of matching items to return.
 * @param adapter The [SearchableAdapter] used to extract searchable fields from each element.
 * @return A list of matching elements sorted by relevance.
 */
fun <T> Iterable<T>.fuzzySearch(
    query: String,
    limit: Int = 20,
    adapter: SearchableAdapter<T>,
): List<T> = FuzzySearchEngine(limit, adapter).search(query, this)

/**
 * Performs a weighted substring and fuzzy search over this [Iterable] using a selector lambda.
 *
 * @param query The raw search query.
 * @param limit Maximum number of matching items to return.
 * @param transform Lambda mapping each element [T] to its [SearchMetadata].
 * @return A list of matching elements sorted by relevance.
 */
inline fun <T> Iterable<T>.fuzzySearch(
    query: String,
    limit: Int = 20,
    crossinline transform: (T) -> SearchMetadata,
): List<T> = FuzzySearchEngine<T> { transform(it) }.search(query, this, limit)

/**
 * Performs a weighted substring and fuzzy search over this [Sequence] using a selector lambda.
 *
 * @param query The raw search query.
 * @param limit Maximum number of matching items to return.
 * @param transform Lambda mapping each element [T] to its [SearchMetadata].
 * @return A list of matching elements sorted by relevance.
 */
inline fun <T> Sequence<T>.fuzzySearch(
    query: String,
    limit: Int = 20,
    crossinline transform: (T) -> SearchMetadata,
): List<T> = asIterable().fuzzySearch(query, limit, transform)

/**
 * Paginates a [List] using one-based page indexing in O(1) offset lookup time.
 *
 * If [page] is less than 1 or [pageSize] is less than or equal to 0, an empty list is returned.
 * If the list contains fewer elements than the requested offset, or if the
 * final page is partially filled, this gracefully returns the remaining elements
 * or an empty list without throwing an [IndexOutOfBoundsException] or overflowing.
 *
 * @param T The type of elements contained in the list.
 * @param page The one-based index of the page to retrieve (1 for the first page).
 * @param pageSize The maximum number of elements to include in the returned page.
 * @return A new [List] containing the elements for the requested page window.
 */
fun <T> List<T>.paginate(page: Int, pageSize: Int): List<T> {
    if (page < 1 || pageSize <= 0 || isEmpty()) return emptyList()

    val startOffset = (page - 1).toLong() * pageSize
    if (startOffset >= size) return emptyList()

    val startIndex = startOffset.toInt()
    val endIndex = minOf(size.toLong(), startOffset + pageSize).toInt()

    return subList(startIndex, endIndex).toList()
}

/**
 * Paginates an [Iterable] using one-based page indexing with zero intermediate collection allocations.
 *
 * If [page] is less than 1 or [pageSize] is less than or equal to 0, an empty list is returned.
 * If the iterable contains fewer elements than the requested offset, or if the
 * final page is partially filled, this gracefully returns the remaining elements
 * or an empty list without throwing an [IndexOutOfBoundsException] or overflowing.
 *
 * @param T The type of elements contained in the iterable.
 * @param page The one-based index of the page to retrieve (1 for the first page).
 * @param pageSize The maximum number of elements to include in the returned page.
 * @return A [List] containing the elements for the requested page window.
 */
fun <T> Iterable<T>.paginate(page: Int, pageSize: Int): List<T> {
    if (page < 1 || pageSize <= 0) return emptyList()
    if (this is List<T>) return this.paginate(page, pageSize)

    val startOffset = (page - 1).toLong() * pageSize
    if (startOffset >= Int.MAX_VALUE && this is Collection<T>) return emptyList()

    val iterator = iterator()
    var skipped = 0L
    while (skipped < startOffset && iterator.hasNext()) {
        iterator.next()
        skipped++
    }

    if (!iterator.hasNext()) return emptyList()

    val result = ArrayList<T>(minOf(pageSize, 1024))
    var count = 0
    while (count < pageSize && iterator.hasNext()) {
        result.add(iterator.next())
        count++
    }

    return result
}
