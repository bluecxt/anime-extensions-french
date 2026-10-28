// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.tmdb.utils

import fr.bluecxt.core.Source
import java.text.Normalizer

private val explicitSeasonRegex = Regex("""(?i)\b(?:Saison|Season)\s*(\d+)""")
private val sagaNumberRegex = Regex("""(?i)\bSaga\s*(\d+)""")

private val mediaTagsRegex = Regex("""(?i)\((?:TV|Films?|OAVs?|ONAs?|Specials?)\)|VF|VOSTFR""")
private val seasonPartRegex = Regex("""(?i)\s*(?:Saison|Season|Part(?:ie)?)\s*\d+.*""")
private val dashSuffixRegex = Regex("""\s+-\s+.*$""")
private val trailingDigitsRegex = Regex("""\s+\d+$""")
private val multiWhitespaceRegex = Regex("""\s+""")

/**
 * Extracts a season number from a season title string (e.g. "Saison 2" -> 2).
 */
fun extractSeasonNumber(text: String, allowSagaFallback: Boolean = true): Int? {
    explicitSeasonRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    if (allowSagaFallback) {
        sagaNumberRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    }
    return null
}

/**
 * Sanitizes a title for better TMDB search results.
 */
fun Source.sanitizeTitle(title: String): String = title
    .replace(mediaTagsRegex, "")
    .replace(seasonPartRegex, "") // Remove season/part info
    .replace(dashSuffixRegex, "") // Remove content after dash
    .replace(trailingDigitsRegex, "") // Remove trailing numbers
    .trim()
    .replace(multiWhitespaceRegex, " ")

/**
 * Calculates a score from 0 to 100 based on title similarity.
 */
fun calculateSimilarityScore(query: String, candidate: String): Int {
    fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
        .replace(Regex("\\p{M}"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    val s1 = normalize(query)
    val s2 = normalize(candidate)
    if (s1.isBlank() || s2.isBlank()) return 0
    if (s1 == s2) return 100

    val flat1 = s1.replace(" ", "")
    val flat2 = s2.replace(" ", "")
    if (flat1 == flat2) return 95

    // If one string contains the other, penalize the length difference
    if (s1.contains(s2) || s2.contains(s1)) {
        val longer = maxOf(s1.length, s2.length)
        val shorter = minOf(s1.length, s2.length)
        return maxOf(0, 85 - (longer - shorter) * 5)
    }

    // Word-based matching for translated titles
    val words1 = s1.split(" ").filter { it.length >= 2 }
    val words2 = s2.split(" ").filter { it.length >= 2 }
    if (words1.isEmpty() || words2.isEmpty()) return 0

    val matchingWords = words1.count { w1 -> words2.any { w2 -> w1 == w2 } }
    return (matchingWords.toDouble() / maxOf(words1.size, words2.size) * 70).toInt()
}

fun Source.isTitleSimilar(q1: String, q2: String): Boolean = calculateSimilarityScore(q1, q2) >= 50
