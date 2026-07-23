package fr.bluecxt.core.tmdb

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.Source
import fr.bluecxt.core.TMDB_LOG
import fr.bluecxt.core.tmdb.dto.TmdbAlternativeTitlesResponse
import fr.bluecxt.core.tmdb.dto.TmdbDetailResponse
import fr.bluecxt.core.tmdb.dto.TmdbEpisodeDto
import fr.bluecxt.core.tmdb.dto.TmdbSearchResponse
import fr.bluecxt.core.tmdb.dto.TmdbSearchResult
import fr.bluecxt.core.tmdb.dto.TmdbSeasonImagesResponse
import fr.bluecxt.core.tmdb.dto.TmdbSeasonResponse
import fr.bluecxt.core.tmdb.utils.calculateSimilarityScore
import fr.bluecxt.core.tmdb.utils.sanitizeTitle
import keiyoushi.core.BuildConfig
import kotlinx.serialization.json.Json
import java.net.URLEncoder

private val TMDB_API_KEY = BuildConfig.TMDB_API
private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
private val tmdbCache = mutableMapOf<String, TmdbMetadata?>()

private val tmdbJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private val ignoredRegex by lazy {
    val terms = try {
        val jsonStream = Source::class.java.getResourceAsStream("/tmdb_ignored.json")
        val jsonString = jsonStream?.bufferedReader()?.use { it.readText() } ?: "[]"
        tmdbJson.decodeFromString<List<String>>(jsonString)
    } catch (_: Exception) {
        emptyList()
    }
    Regex("(?i)(" + terms.joinToString("|").ifEmpty { "NOMATCH" } + ")")
}

/**
 * Fetches TMDB metadata specifically for a movie.
 * Returns a result only if there is a single exact match to ensure accuracy.
 */
suspend fun Source.fetchTmdbMovieMetadata(query: String, lang: String = "fr-FR"): TmdbMetadata? {
    val searchUrl = "$TMDB_BASE_URL/search/movie?api_key=$TMDB_API_KEY&query=${URLEncoder.encode(query, "UTF-8")}&language=$lang"
    return try {
        val response = client.newCall(GET(searchUrl)).awaitSuccess().use { it.body.string() }
        val searchDto = tmdbJson.decodeFromString<TmdbSearchResponse>(response)
        if (searchDto.results.size == 1) {
            val id = searchDto.results[0].id
            constructMetadata(id, "movie", 1, lang)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Fetches TMDB metadata using a smart two-step search.
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

    // Step 1: Search with full title
    val firstAttempt = performTmdbSearch(title, season, type, lang)
    if (firstAttempt != null) {
        Log.d(TMDB_LOG, "TMDB Search success for full title '$title'")
        tmdbCache[cacheKey] = firstAttempt
        return firstAttempt
    }

    Log.d(TMDB_LOG, "TMDB Search failed for '$title', checking backup with sanitized title...")

    // Step 2: If failed, search with sanitized title
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
 * Filters TMDB metadata to remove non-fiction content (PVs, recaps, interviews).
 */
fun Source.filterSmartMetadata(meta: TmdbMetadata, isSpecialSeason: Boolean = false): TmdbMetadata {
    val filteredSummaries = meta.episodeSummaries.values
        .filter { triple ->
            val (title, _, summary) = triple
            val isIgnoredTitle = title?.let { ignoredRegex.containsMatchIn(it) } ?: false
            val hasNoSummary = summary.isNullOrBlank()

            !(isIgnoredTitle || (isSpecialSeason && hasNoSummary))
        }
        .mapIndexed { index, triple -> (index + 1) to triple }
        .toMap()

    return meta.copy(episodeSummaries = filteredSummaries)
}

/**
 * Fetches TMDB metadata directly by ID.
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
        val searchDto = tmdbJson.decodeFromString<TmdbSearchResponse>(response)
        val results = searchDto.results

        if (results.isEmpty()) {
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

        var bestMatch: TmdbSearchResult? = null
        var highestScore = -1
        var maxVotes = -1
        var bestIsAnimation = false

        val targetType = when (type?.lowercase()) {
            "movie", "film" -> "movie"
            "tv", "series", "série" -> "tv"
            else -> null
        }

        for (res in results) {
            val mType = res.mediaType ?: (targetType ?: "tv")
            if (mType != "movie" && mType != "tv") continue
            if (targetType != null && mType != targetType) continue

            val resultTitle = res.name?.ifBlank { null } ?: res.title.orEmpty()
            val resultOriginalTitle = res.originalName?.ifBlank { null } ?: res.originalTitle.orEmpty()

            val score = maxOf(calculateSimilarityScore(query, resultTitle), calculateSimilarityScore(query, resultOriginalTitle))

            if (score < 50) {
                val bestId = res.id
                val altUrl = "$TMDB_BASE_URL/$mType/$bestId/alternative_titles?api_key=$TMDB_API_KEY"
                var altScore = 0
                try {
                    val altRes = client.newCall(GET(altUrl)).awaitSuccess().use { it.body.string() }
                    val altDto = tmdbJson.decodeFromString<TmdbAlternativeTitlesResponse>(altRes)
                    for (alt in altDto.results) {
                        altScore = maxOf(altScore, calculateSimilarityScore(query, alt.title))
                    }
                } catch (_: Exception) {}
                if (altScore < 50) continue
            }

            val votes = res.voteCount
            val isAnimation = res.genreIds.contains(16) // 16 = Animation genre ID

            // Selection score calculation: Similarity score + 25 bonus points if media is Animation
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

        val id = bestMatch.id
        val mediaType = bestMatch.mediaType ?: (targetType ?: "tv")

        return constructMetadata(id, mediaType, season, lang)
    } catch (_: Exception) {
        return if (lang != "en-US") performTmdbSearch(query, season, type, "en-US") else null
    }
}

private suspend fun Source.constructMetadata(id: Int, mediaType: String, season: Int, lang: String): TmdbMetadata? {
    return try {
        val detailUrl = "$TMDB_BASE_URL/$mediaType/$id?api_key=$TMDB_API_KEY&language=$lang&append_to_response=credits"
        val detailResponse = client.newCall(GET(detailUrl)).awaitSuccess().use { it.body.string() }
        val detailDto = tmdbJson.decodeFromString<TmdbDetailResponse>(detailResponse)

        val mainPoster = detailDto.posterPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
        val mainSummary = detailDto.overview?.takeIf { it.isNotBlank() }

        val artist = detailDto.productionCompanies.map { it.name }.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }

        val status = when (detailDto.status) {
            "Ended", "Canceled", "Released" -> SAnime.COMPLETED
            "Returning Series", "In Production", "Pilot" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

        val releaseDate = detailDto.firstAirDate?.ifBlank { null } ?: detailDto.releaseDate?.takeIf { it.isNotBlank() }

        val genre = detailDto.genres.map { it.name }.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }

        val authorList = mutableListOf<String>()
        detailDto.createdBy.forEach { creator ->
            if (creator.name.isNotBlank()) authorList.add(creator.name)
        }
        detailDto.credits?.crew?.forEach { member ->
            if (member.job in listOf("Director", "Series Director", "Comic Book", "Novel", "Original Story", "Author", "Series Composition", "Writer", "Original Creator", "Story", "Screenplay", "Original Concept")) {
                if (member.name.isNotBlank()) authorList.add(member.name)
            }
        }
        val author = authorList.distinct().joinToString(", ").takeIf { it.isNotBlank() }

        if (mediaType == "movie") {
            val backdrop = detailDto.backdropPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
            val movieTitle = detailDto.title?.ifBlank { null } ?: detailDto.name.orEmpty()
            TmdbMetadata(
                summary = mainSummary,
                releaseDate = releaseDate,
                mainPosterUrl = mainPoster,
                seasonPosterUrl = mainPoster,
                posterUrl = mainPoster,
                author = author,
                artist = artist,
                status = status,
                genre = genre,
                episodeSummaries = mapOf(1 to Triple(movieTitle, backdrop, mainSummary)),
            )
        } else {
            val originalLang = detailDto.originalLanguage ?: "ja"

            // Check if the requested season actually exists on TMDB to avoid 404 responses
            val hasSeason = detailDto.seasons.any { it.seasonNumber == season }

            var seasonSummary = mainSummary
            var seasonPoster = mainPoster

            // TV Series - Fetch season data in the requested language (e.g. fr-FR)
            val langEpisodes = if (hasSeason) {
                try {
                    val langSeasonBody = client.newCall(GET("$TMDB_BASE_URL/tv/$id/season/$season?api_key=$TMDB_API_KEY&language=$lang")).awaitSuccess().use { it.body.string() }
                    val seasonDto = tmdbJson.decodeFromString<TmdbSeasonResponse>(langSeasonBody)
                    seasonSummary = seasonDto.overview?.takeIf { it.isNotBlank() } ?: mainSummary
                    seasonDto.episodes
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            // Select season poster with strict language fallback priority (FR -> VO -> EN -> Textless -> mainPoster)
            if (hasSeason) {
                try {
                    val imagesUrl = "$TMDB_BASE_URL/tv/$id/season/$season/images?api_key=$TMDB_API_KEY"
                    val imagesBody = client.newCall(GET(imagesUrl)).awaitSuccess().use { it.body.string() }
                    val imagesDto = tmdbJson.decodeFromString<TmdbSeasonImagesResponse>(imagesBody)
                    val posters = imagesDto.posters

                    val bestPoster = posters.firstOrNull { it.iso6391 == "fr" }
                        ?: posters.firstOrNull { it.iso6391 == originalLang }
                        ?: posters.firstOrNull { it.iso6391 == "en" }
                        ?: posters.firstOrNull { it.iso6391 == null }

                    if (bestPoster?.filePath?.isNotBlank() == true) {
                        seasonPoster = "https://image.tmdb.org/t/p/w500${bestPoster.filePath}"
                    }
                } catch (_: Exception) {}
            }

            // Check if fallback to English is needed (if titles/summaries are missing or generic)
            var needsFallback = hasSeason && langEpisodes.isEmpty()
            val genericRegex = Regex("(?i)^(Episode|Épisode)\\s*\\d+$|^第\\d+[話回]$|^\\d+$")
            if (hasSeason && !needsFallback) {
                for (ep in langEpisodes) {
                    val name = ep.name.orEmpty()
                    if (name.isBlank() || name.matches(genericRegex) || ep.overview.isNullOrBlank()) {
                        needsFallback = true
                        break
                    }
                }
            }

            val fallbackEpisodes = if (needsFallback && lang != "en-US") {
                try {
                    val enSeasonBody = client.newCall(GET("$TMDB_BASE_URL/tv/$id/season/$season?api_key=$TMDB_API_KEY&language=en-US")).awaitSuccess().use { it.body.string() }
                    tmdbJson.decodeFromString<TmdbSeasonResponse>(enSeasonBody).episodes
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            // Check if original language fallback (e.g. ja-JP) is needed
            var needsOriginal = fallbackEpisodes != null && fallbackEpisodes.isNotEmpty()
            if (needsOriginal) {
                needsOriginal = false
                for (ep in fallbackEpisodes!!) {
                    val name = ep.name.orEmpty()
                    if (name.isBlank() || name.matches(genericRegex) || ep.overview.isNullOrBlank()) {
                        needsOriginal = true
                        break
                    }
                }
            }

            val origEpisodes = if (needsOriginal && originalLang != "en" && originalLang != "fr") {
                try {
                    val origSeasonBody = client.newCall(GET("$TMDB_BASE_URL/tv/$id/season/$season?api_key=$TMDB_API_KEY&language=$originalLang")).awaitSuccess().use { it.body.string() }
                    tmdbJson.decodeFromString<TmdbSeasonResponse>(origSeasonBody).episodes
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            val epMap = mutableMapOf<Int, Triple<String?, String?, String?>>()

            // Merges episode details prioritizing the requested language, then English, then original language
            fun fillMap(source: List<TmdbEpisodeDto>?) {
                if (source == null) return
                for (ep in source) {
                    val num = ep.episodeNumber
                    val name = ep.name?.trim()?.takeIf { it.isNotBlank() && !it.matches(genericRegex) }
                    val summary = ep.overview?.trim()?.takeIf { it.isNotBlank() }
                    val thumb = ep.stillPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }

                    val existing = epMap[num]
                    epMap[num] = Triple(existing?.first ?: name, existing?.second ?: thumb, existing?.third ?: summary)
                }
            }

            fillMap(langEpisodes)
            fillMap(fallbackEpisodes)
            fillMap(origEpisodes)

            // Robust season fallback for sites that group multiple seasons into a single list
            if (season > 0) {
                var offset = 0
                for (s in detailDto.seasons) {
                    if (s.seasonNumber in 1 until season) {
                        offset += s.episodeCount
                    }
                }
                if (offset > 0) {
                    try {
                        val s1Body = client.newCall(GET("$TMDB_BASE_URL/tv/$id/season/1?api_key=$TMDB_API_KEY&language=$lang")).awaitSuccess().use { it.body.string() }
                        val s1Dto = tmdbJson.decodeFromString<TmdbSeasonResponse>(s1Body)
                        for (ep in s1Dto.episodes) {
                            val absNum = ep.episodeNumber
                            if (absNum > offset) {
                                val relNum = absNum - offset
                                if (epMap[relNum]?.first == null) {
                                    val name = ep.name?.trim()?.takeIf { it.isNotBlank() && !it.matches(genericRegex) }
                                    val summary = ep.overview?.trim()?.takeIf { it.isNotBlank() }
                                    val thumb = ep.stillPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
                                    epMap[relNum] = Triple(name, thumb, summary)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            TmdbMetadata(
                summary = seasonSummary,
                releaseDate = releaseDate,
                mainPosterUrl = mainPoster,
                seasonPosterUrl = seasonPoster,
                posterUrl = seasonPoster ?: mainPoster,
                author = author,
                artist = artist,
                status = status,
                genre = genre,
                episodeSummaries = epMap,
            )
        }
    } catch (e: Exception) {
        Log.e(TMDB_LOG, "Error constructing metadata for id=$id, type=$mediaType, season=$season", e)
        null
    }
}
