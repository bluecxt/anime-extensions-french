package fr.bluecxt.core

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.TMDB_LOG
import keiyoushi.core.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer

private val TMDB_API_KEY = BuildConfig.TMDB_API
private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
private val tmdbCache = mutableMapOf<String, TmdbMetadata?>()

private val ignoredRegex by lazy {
    val terms = try {
        val jsonStream = Source::class.java.getResourceAsStream("/tmdb_ignored.json")
        val jsonString = jsonStream?.bufferedReader()?.use { it.readText() } ?: "[]"
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(jsonString)
    } catch (_: Exception) {
        emptyList<String>()
    }
    Regex("(?i)(" + terms.joinToString("|").ifEmpty { "NOMATCH" } + ")")
}

/**
 * Modèle de données pour les métadonnées TMDB.
 */
data class TmdbMetadata(
    val summary: String?,
    val releaseDate: String?,
    val posterUrl: String?,
    val author: String?,
    val artist: String?,
    val status: Int,
    val genre: String? = null,
    val episodeSummaries: Map<Int, Triple<String?, String?, String?>>,
)

/**
 * Nettoie un titre pour de meilleurs résultats de recherche TMDB.
 */
fun Source.sanitizeTitle(title: String): String = title
    .replace(Regex("(?i)\\(TV\\)|\\(Films?s?\\)|\\(OAVs?\\)|\\(ONAs?\\)|\\(Specials?\\)|VF|VOSTFR"), "")
    .replace(Regex("(?i)\\s*(?:Saison|Season|Part(?:ie)?)\\s*\\d+.*"), "") // Supprime les infos de saison/partie
    .replace(Regex("\\s+-\\s+.*$"), "") // Supprime après le tiret
    .replace(Regex("\\s+\\d+$"), "") // Supprime les chiffres finaux
    .trim()
    .replace(Regex("\\s+"), " ")

/**
 * Calcule un score de 0 à 100 basé sur la similarité des titres.
 */
private fun calculateSimilarityScore(query: String, candidate: String): Int {
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

    // Si l'un contient l'autre, on pénalise la différence de longueur
    if (s1.contains(s2) || s2.contains(s1)) {
        val longer = maxOf(s1.length, s2.length)
        val shorter = minOf(s1.length, s2.length)
        return Math.max(0, 85 - (longer - shorter) * 5)
    }

    // Word-based matching pour les titres traduits
    val words1 = s1.split(" ").filter { it.length >= 2 }
    val words2 = s2.split(" ").filter { it.length >= 2 }
    if (words1.isEmpty() || words2.isEmpty()) return 0

    val matchingWords = words1.count { w1 -> words2.any { w2 -> w1 == w2 } }
    val wordScore = (matchingWords.toDouble() / maxOf(words1.size, words2.size) * 70).toInt()

    return wordScore
}

fun Source.isTitleSimilar(q1: String, q2: String): Boolean = calculateSimilarityScore(q1, q2) >= 50

/**
 * Fetches metadata from TMDB specifically for a movie.
 * Only returns a result if there's exactly one match to ensure accuracy.
 */
suspend fun Source.fetchTmdbMovieMetadata(query: String, lang: String = "fr-FR"): TmdbMetadata? {
    val searchUrl = "$TMDB_BASE_URL/search/movie?api_key=$TMDB_API_KEY&query=${URLEncoder.encode(query, "UTF-8")}&language=$lang"
    return try {
        val response = client.newCall(GET(searchUrl)).awaitSuccess().use { it.body.string() }
        val json = JSONObject(response)
        val results = json.getJSONArray("results")
        if (json.getInt("total_results") == 1) {
            val id = results.getJSONObject(0).getInt("id")
            constructMetadata(id, "movie", 1, lang)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Fetches metadata from TMDB using a smart two-step search.
 * If type is provided ('movie' or 'tv'), searches exclusively in that category.
 */
suspend fun Source.fetchTmdbMetadata(title: String, season: Int = 1, type: String? = null, lang: String = "fr-FR"): TmdbMetadata? {
    if (title.isBlank()) return null
    val cacheKey = "$title-$season-$type-$lang"
    if (tmdbCache.containsKey(cacheKey)) {
        val cached = tmdbCache[cacheKey]
        Log.d(TMDB_LOG, "Cache hit for '$title' (season $season, type $type): found=${cached != null}")
        return cached
    }

    Log.d(TMDB_LOG, "TMDB Search start for '$title' (season $season, type $type, lang $lang)")

    // Step 1: Try searching with the full title (most accurate)
    val firstAttempt = performTmdbSearch(title, season, type, lang)
    if (firstAttempt != null) {
        Log.d(TMDB_LOG, "TMDB Search success for full title '$title'")
        tmdbCache[cacheKey] = firstAttempt
        return firstAttempt
    }

    Log.d(TMDB_LOG, "TMDB Search failed for '$title', checking backup with sanitized title...")

    // Step 2: If failed, try with sanitized title
    val cleanTitle = sanitizeTitle(title)
    if (cleanTitle != title && cleanTitle.isNotBlank()) {
        Log.d(TMDB_LOG, "TMDB Backup Search start with sanitized title '$cleanTitle'")
        val secondAttempt = performTmdbSearch(cleanTitle, season, type, lang)
        if (secondAttempt != null) {
            Log.d(TMDB_LOG, "TMDB Backup Search success with sanitized title '$cleanTitle'")
            tmdbCache[cacheKey] = secondAttempt
            return secondAttempt
        } else {
            Log.d(TMDB_LOG, "TMDB Backup Search failed for sanitized title '$cleanTitle'")
        }
    } else {
        Log.d(TMDB_LOG, "TMDB Backup Search skipped (sanitized title is same or empty)")
    }

    Log.d(TMDB_LOG, "TMDB Search totally failed for '$title'")
    tmdbCache[cacheKey] = null
    return null
}

/**
 * Filters TMDB metadata to remove non-fiction content (PVs, recaps, interviews, etc.).
 * Re-aligns episode numbers after filtering.
 */
fun Source.filterSmartMetadata(meta: TmdbMetadata, isSpecialSeason: Boolean = false): TmdbMetadata {
    val filteredSummaries = meta.episodeSummaries.values
        .filter { triple ->
            val (title, _, summary) = triple
            val isIgnoredTitle = title?.let { ignoredRegex.containsMatchIn(it) } ?: false
            val hasNoSummary = summary.isNullOrBlank()

            // Rule: Ignore if title matches blacklist OR (if Season 0 and has no summary)
            !(isIgnoredTitle || (isSpecialSeason && hasNoSummary))
        }
        .mapIndexed { index, triple -> (index + 1) to triple }
        .toMap()

    return meta.copy(episodeSummaries = filteredSummaries)
}

/**
 * Fetches metadata from TMDB directly by ID.
 */
suspend fun Source.fetchTmdbMetadataById(id: Int, type: String, season: Int = 1, lang: String = "fr-FR"): TmdbMetadata? = try {
    val typeClean = if (type == "tv" || type == "series") "tv" else "movie"
    constructMetadata(id, typeClean, season, lang)
} catch (_: Exception) {
    null
}

private suspend fun Source.performTmdbSearch(query: String, season: Int, type: String? = null, lang: String = "fr-FR"): TmdbMetadata? {
    val searchPath = when (type?.lowercase()) {
        "movie", "film" -> "search/movie"
        "tv", "series", "série" -> "search/tv"
        else -> "search/multi"
    }
    val searchUrl = "$TMDB_BASE_URL/$searchPath?api_key=$TMDB_API_KEY&query=${URLEncoder.encode(query, "UTF-8")}&language=$lang"

    try {
        val response = client.newCall(GET(searchUrl)).awaitSuccess().use { it.body.string() }
        val results = JSONObject(response).getJSONArray("results")
        if (results.length() == 0) {
            if (lang != "en-US") {
                Log.d(TMDB_LOG, "No results for '$query' in $lang, trying English backup (en-US)...")
                val enAttempt = performTmdbSearch(query, season, type, "en-US")
                if (enAttempt != null) {
                    Log.d(TMDB_LOG, "English backup search success for '$query'")
                } else {
                    Log.d(TMDB_LOG, "English backup search failed for '$query'")
                }
                return enAttempt
            }
            return null
        }

        var bestMatch: JSONObject? = null
        var highestScore = -1
        var maxVotes = -1
        var bestIsAnimation = false

        val targetType = when (type?.lowercase()) {
            "movie", "film" -> "movie"
            "tv", "series", "série" -> "tv"
            else -> null
        }

        for (i in 0 until results.length()) {
            val res = results.getJSONObject(i)
            val mType = res.optString("media_type", targetType ?: "tv")
            if (mType != "movie" && mType != "tv") continue
            if (targetType != null && mType != targetType) continue

            val resultTitle = res.optString("name").ifBlank { res.optString("title") }
            val resultOriginalTitle = res.optString("original_name").ifBlank { res.optString("original_title") }

            val score = maxOf(calculateSimilarityScore(query, resultTitle), calculateSimilarityScore(query, resultOriginalTitle))

            if (score < 50) {
                // Try alternative titles only if main titles fail
                val bestId = res.getInt("id")
                val altUrl = "$TMDB_BASE_URL/$mType/$bestId/alternative_titles?api_key=$TMDB_API_KEY"
                var altScore = 0
                try {
                    val altRes = client.newCall(GET(altUrl)).awaitSuccess().use { it.body.string() }
                    val altArray = JSONObject(altRes).getJSONArray("results")
                    for (j in 0 until altArray.length()) {
                        val alt = altArray.getJSONObject(j).optString("title")
                        altScore = maxOf(altScore, calculateSimilarityScore(query, alt))
                    }
                } catch (_: Exception) {}
                if (altScore < 50) continue
            }

            val votes = res.optInt("vote_count", 0)
            val genreIds = res.optJSONArray("genre_ids")
            val isAnimation = genreIds?.let { ids ->
                (0 until ids.length()).any { ids.getInt(it) == 16 } // 16 = Animation
            } ?: false

            // Logique de sélection : Score (avec bonus animation) > Animation > Votes
            val finalScore = maxOf(score, 50) + (if (isAnimation) 25 else 0)
            if (finalScore > highestScore) {
                highestScore = finalScore
                maxVotes = votes
                bestIsAnimation = isAnimation
                bestMatch = res
            } else if (finalScore == highestScore) {
                if (isAnimation && !bestIsAnimation) {
                    bestIsAnimation = true
                    maxVotes = votes
                    bestMatch = res
                } else if (isAnimation == bestIsAnimation && votes > maxVotes) {
                    maxVotes = votes
                    bestMatch = res
                }
            }
        }

        if (bestMatch == null) {
            return if (lang != "en-US") performTmdbSearch(query, season, type, "en-US") else null
        }

        val id = bestMatch.getInt("id")
        val mediaType = bestMatch.optString("media_type", targetType ?: "tv")

        return constructMetadata(id, mediaType, season, lang)
    } catch (_: Exception) {
        return if (lang != "en-US") performTmdbSearch(query, season, type, "en-US") else null
    }
}

private suspend fun Source.constructMetadata(id: Int, mediaType: String, season: Int, lang: String): TmdbMetadata? {
    return try {
        // Fetch full details to get author/artist/studios/status
        val detailUrl = "$TMDB_BASE_URL/$mediaType/$id?api_key=$TMDB_API_KEY&language=$lang&append_to_response=credits"
        val detailResponse = client.newCall(GET(detailUrl)).awaitSuccess().use { it.body.string() }
        val detailJson = JSONObject(detailResponse)

        val mainPoster = detailJson.optString("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
        val mainSummary = detailJson.optString("overview").takeIf { it.isNotBlank() }

        // Extract Artist (Studios)
        val artist = detailJson.optJSONArray("production_companies")?.let { companies ->
            val list = mutableListOf<String>()
            for (i in 0 until companies.length()) {
                list.add(companies.getJSONObject(i).getString("name"))
            }
            list.joinToString(", ").takeIf { it.isNotBlank() }
        }

        // Extract Status
        val tmdbStatus = detailJson.optString("status")
        val status = when (tmdbStatus) {
            "Ended", "Canceled", "Released" -> SAnime.COMPLETED
            "Returning Series", "In Production", "Pilot" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

        // Extract Release Date
        val releaseDate = detailJson.optString("first_air_date").ifBlank { detailJson.optString("release_date") }.takeIf { it.isNotBlank() }

        // Extract Genres
        val genre = detailJson.optJSONArray("genres")?.let { genres ->
            val list = mutableListOf<String>()
            for (i in 0 until genres.length()) {
                list.add(genres.getJSONObject(i).getString("name"))
            }
            list.joinToString(", ").takeIf { it.isNotBlank() }
        }

        // Extract Author
        val authorList = mutableListOf<String>()
        detailJson.optJSONArray("created_by")?.let { creators ->
            for (i in 0 until creators.length()) {
                authorList.add(creators.getJSONObject(i).getString("name"))
            }
        }
        detailJson.optJSONObject("credits")?.optJSONArray("crew")?.let { crew ->
            for (i in 0 until crew.length()) {
                val member = crew.getJSONObject(i)
                if (member.optString("job") in listOf("Director", "Series Director", "Comic Book", "Novel", "Original Story", "Author", "Series Composition", "Writer", "Original Creator", "Story", "Screenplay", "Original Concept")) {
                    authorList.add(member.getString("name"))
                }
            }
        }
        val author = authorList.distinct().joinToString(", ").takeIf { it.isNotBlank() }

        if (mediaType == "movie") {
            val backdrop = detailJson.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
            val movieTitle = detailJson.optString("title").ifBlank { detailJson.optString("name") }
            TmdbMetadata(posterUrl = mainPoster, summary = mainSummary, author = author, artist = artist, status = status, releaseDate = releaseDate, genre = genre, episodeSummaries = mapOf(1 to Triple(movieTitle, backdrop, mainSummary)))
        } else {
            val originalLang = detailJson.optString("original_language", "ja")

            // TV Series - Fetch season data (Requested language first)
            val langSeasonBody = client.newCall(GET("$TMDB_BASE_URL/tv/$id/season/$season?api_key=$TMDB_API_KEY&language=$lang")).awaitSuccess().use { it.body.string() }
            val langSeasonJson = JSONObject(langSeasonBody)
            val langEpisodes = langSeasonJson.optJSONArray("episodes") ?: JSONArray()

            // Check for fallback (English if requested was not English, else Japanese/Original)
            var needsFallback = langEpisodes.length() == 0
            val genericRegex = Regex("(?i)^(Episode|Épisode)\\s*\\d+$|^第\\d+[話回]$|^\\d+$")
            for (i in 0 until langEpisodes.length()) {
                val ep = langEpisodes.getJSONObject(i)
                val name = ep.optString("name")
                if (name.isBlank() || name.matches(genericRegex) || ep.optString("overview").isBlank()) {
                    needsFallback = true
                    break
                }
            }

            val fallbackEpisodes = if (needsFallback && lang != "en-US") {
                try {
                    val enSeasonBody = client.newCall(GET("$TMDB_BASE_URL/tv/$id/season/$season?api_key=$TMDB_API_KEY&language=en-US")).awaitSuccess().use { it.body.string() }
                    JSONObject(enSeasonBody).optJSONArray("episodes")
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            // Check for Original language fallback (e.g. ja-JP)
            var needsOriginal = fallbackEpisodes != null && fallbackEpisodes.length() > 0
            if (needsOriginal) {
                needsOriginal = false
                for (i in 0 until fallbackEpisodes!!.length()) {
                    val ep = fallbackEpisodes.getJSONObject(i)
                    val name = ep.optString("name")
                    if (name.isBlank() || name.matches(genericRegex) || ep.optString("overview").isBlank()) {
                        needsOriginal = true
                        break
                    }
                }
            }

            val origEpisodes = if (needsOriginal && originalLang != "en" && originalLang != "fr") {
                try {
                    val origSeasonBody = client.newCall(GET("$TMDB_BASE_URL/tv/$id/season/$season?api_key=$TMDB_API_KEY&language=$originalLang")).awaitSuccess().use { it.body.string() }
                    JSONObject(origSeasonBody).optJSONArray("episodes")
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            val epMap = mutableMapOf<Int, Triple<String?, String?, String?>>()

            fun fillMap(source: JSONArray?) {
                if (source == null) return
                for (i in 0 until source.length()) {
                    val ep = source.getJSONObject(i)
                    val num = ep.getInt("episode_number")
                    val name = ep.optString("name").trim().takeIf { it.isNotBlank() && !it.matches(genericRegex) }
                    val summary = ep.optString("overview").trim().takeIf { it.isNotBlank() }
                    val thumb = ep.optString("still_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }

                    val existing = epMap[num]
                    // Only replace if existing name/summary is null
                    epMap[num] = Triple(existing?.first ?: name, existing?.second ?: thumb, existing?.third ?: summary)
                }
            }

            fillMap(langEpisodes)
            fillMap(fallbackEpisodes)
            fillMap(origEpisodes)

            // Robust Season Fallback (for sites that group seasons differently)
            if (season > 0) {
                val tmdbSeasons = detailJson.optJSONArray("seasons")
                var offset = 0
                if (tmdbSeasons != null) {
                    for (i in 0 until tmdbSeasons.length()) {
                        val s = tmdbSeasons.getJSONObject(i)
                        val sNum = s.optInt("season_number")
                        if (sNum in 1 until season) {
                            offset += s.optInt("episode_count")
                        }
                    }
                }
                if (offset > 0) {
                    try {
                        val s1Body = client.newCall(GET("$TMDB_BASE_URL/tv/$id/season/1?api_key=$TMDB_API_KEY&language=$lang")).awaitSuccess().use { it.body.string() }
                        val s1Episodes = JSONObject(s1Body).optJSONArray("episodes")
                        if (s1Episodes != null) {
                            for (i in 0 until s1Episodes.length()) {
                                val ep = s1Episodes.getJSONObject(i)
                                val absNum = ep.getInt("episode_number")
                                if (absNum > offset) {
                                    val relNum = absNum - offset
                                    if (epMap[relNum]?.first == null) {
                                        val name = ep.optString("name").trim().takeIf { it.isNotBlank() && !it.matches(genericRegex) }
                                        val summary = ep.optString("overview").trim().takeIf { it.isNotBlank() }
                                        val thumb = ep.optString("still_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
                                        epMap[relNum] = Triple(name, thumb, summary)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            val seasonSummary = langSeasonJson.optString("overview").takeIf { it.isNotBlank() } ?: mainSummary
            val seasonPoster = langSeasonJson.optString("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" } ?: mainPoster
            TmdbMetadata(posterUrl = seasonPoster, summary = seasonSummary, author = author, artist = artist, status = status, releaseDate = releaseDate, genre = genre, episodeSummaries = epMap)
        }
    } catch (_: Exception) {
        null
    }
}
