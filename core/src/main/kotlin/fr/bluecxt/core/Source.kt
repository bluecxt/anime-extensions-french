package fr.bluecxt.core

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

/**
 * Metadata result from TMDB
 */
data class TmdbMetadata(
    val posterUrl: String? = null,
    val episodeThumbUrl: String? = null,
    val summary: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: Int = 0, // SAnime.UNKNOWN
    val releaseDate: String? = null,
    val genre: String? = null,
    // Map<EpNumber, Triple<String? (Title), String? (Thumb), String? (Summary)>>
    val episodeSummaries: Map<Int, Triple<String?, String?, String?>> = emptyMap(),
)

/**
 * Base class for all French Anime Extensions using extensions-lib v16.
 * Separated from keiyoushi utils to allow custom French logic.
 */
abstract class Source :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    protected val context: Application by injectLazy()

    protected open val migration: SharedPreferences.() -> Unit = {}

    open val json: Json by injectLazy()

    val preferences: SharedPreferences by getPreferencesLazy { migration }

    protected val handler by lazy { Handler(Looper.getMainLooper()) }

    protected fun displayToast(message: String, length: Int = Toast.LENGTH_SHORT) {
        handler.post {
            Toast.makeText(context, message, length).show()
        }
    }

    // ============================== TMDB Engine ==============================
    private val tmdbApiKey = "24621da8ae19dce721e59eff2ab479bb"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    /**
     * Sanitizes a title for better TMDB search results.
     */
    private fun sanitizeTitle(title: String): String = title.replace(Regex("(?i)\\(TV\\)|\\(Films?s?\\)|\\(OAVs?\\)|\\(ONAs?\\)|\\(Specials?\\)|VF|VOSTFR"), "")
        .replace(Regex("-.*$"), "") // Remove everything after a dash (arcs, subtitles)
        .trim()
        .replace(Regex("\\s+"), " ")

    /**
     * Fetches metadata from TMDB specifically for a movie.
     * Only returns a result if there's exactly one match to ensure accuracy.
     */
    suspend fun fetchTmdbMovieMetadata(query: String): TmdbMetadata? {
        val searchUrl = "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=${URLEncoder.encode(query, "UTF-8")}&language=fr-FR"
        return try {
            val response = client.newCall(GET(searchUrl)).execute().use { it.body.string() }
            val json = JSONObject(response)
            val results = json.getJSONArray("results")
            if (json.getInt("total_results") == 1) {
                val id = results.getJSONObject(0).getInt("id")
                constructMetadata(id, "movie", 1)
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
    suspend fun fetchTmdbMetadata(title: String, season: Int = 1, type: String? = null): TmdbMetadata? {
        if (title.isBlank()) return null

        // Step 1: Try searching with the full title (most accurate)
        val firstAttempt = performTmdbSearch(title, season, type)
        if (firstAttempt != null) return firstAttempt

        // Step 2: If failed, try with sanitized title (removes arcs/subtitles)
        val cleanTitle = sanitizeTitle(title)
        if (cleanTitle != title) {
            return performTmdbSearch(cleanTitle, season, type)
        }

        return null
    }

    /**
     * Fetches metadata from TMDB directly by ID.
     */
    suspend fun fetchTmdbMetadataById(id: Int, type: String, season: Int = 1): TmdbMetadata? = try {
        val typeClean = if (type == "tv" || type == "series") "tv" else "movie"
        constructMetadata(id, typeClean, season)
    } catch (_: Exception) {
        null
    }

    private suspend fun performTmdbSearch(query: String, season: Int, type: String? = null): TmdbMetadata? {
        val searchPath = when (type?.lowercase()) {
            "movie", "film" -> "search/movie"
            "tv", "series", "série" -> "search/tv"
            else -> "search/multi"
        }
        val searchUrl = "$tmdbBaseUrl/$searchPath?api_key=$tmdbApiKey&query=${URLEncoder.encode(query, "UTF-8")}&language=fr-FR"

        return try {
            val response = client.newCall(GET(searchUrl)).execute().use { it.body.string() }
            val results = JSONObject(response).getJSONArray("results")
            if (results.length() == 0) return null

            // Find best match
            var bestMatch: JSONObject? = null
            val targetType = when (type?.lowercase()) {
                "movie", "film" -> "movie"
                "tv", "series", "série" -> "tv"
                else -> null
            }

            if (targetType != null) {
                bestMatch = results.getJSONObject(0)
            } else {
                // Multi-search priority logic (TV first)
                for (i in 0 until results.length()) {
                    val res = results.getJSONObject(i)
                    if (res.optString("media_type") == "tv") {
                        bestMatch = res
                        break
                    }
                }
                if (bestMatch == null) {
                    for (i in 0 until results.length()) {
                        val res = results.getJSONObject(i)
                        if (res.optString("media_type") == "movie") {
                            bestMatch = res
                            break
                        }
                    }
                }
            }

            if (bestMatch == null) return null

            val id = bestMatch.getInt("id")
            val mediaType = targetType ?: bestMatch.optString("media_type")

            constructMetadata(id, mediaType, season)
        } catch (_: Exception) {
            null
        }
    }

    private fun constructMetadata(id: Int, mediaType: String, season: Int): TmdbMetadata? {
        return try {
            // Fetch full details to get author/artist/studios/status
            val detailUrl = "$tmdbBaseUrl/$mediaType/$id?api_key=$tmdbApiKey&language=fr-FR&append_to_response=credits"
            val detailResponse = client.newCall(GET(detailUrl)).execute().use { it.body.string() }
            val detailJson = JSONObject(detailResponse)

            val poster = detailJson.optString("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
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
                    if (member.optString("job") in listOf("Director", "Series Director", "Comic Book", "Novel", "Original Story", "Author", "Series Composition", "Writer")) {
                        authorList.add(member.getString("name"))
                    }
                }
            }
            val author = authorList.distinct().joinToString(", ").takeIf { it.isNotBlank() }

            if (mediaType == "movie") {
                val backdrop = detailJson.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
                val movieTitle = detailJson.optString("title").ifBlank { detailJson.optString("name") }
                TmdbMetadata(posterUrl = poster, episodeThumbUrl = backdrop, summary = mainSummary, author = author, artist = artist, status = status, releaseDate = releaseDate, genre = genre, episodeSummaries = mapOf(1 to Triple(movieTitle, backdrop, mainSummary)))
            } else {
                // TV Series - Fetch season data (FR first)
                val frSeasonBody = client.newCall(GET("$tmdbBaseUrl/tv/$id/season/$season?api_key=$tmdbApiKey&language=fr-FR")).execute().use { it.body.string() }
                val frSeasonJson = JSONObject(frSeasonBody)
                val frEpisodes = frSeasonJson.optJSONArray("episodes") ?: JSONArray()

                // Check for English fallback
                var needsEnglish = frEpisodes.length() == 0
                val genericRegex = Regex("(?i)^(Episode|Épisode)\\s*\\d+$")
                for (i in 0 until frEpisodes.length()) {
                    val ep = frEpisodes.getJSONObject(i)
                    val name = ep.optString("name")
                    if (name.isBlank() || name.matches(genericRegex) || ep.optString("overview").isBlank()) {
                        needsEnglish = true
                        break
                    }
                }

                val enEpisodes = if (needsEnglish) {
                    try {
                        val enSeasonBody = client.newCall(GET("$tmdbBaseUrl/tv/$id/season/$season?api_key=$tmdbApiKey&language=en-US")).execute().use { it.body.string() }
                        JSONObject(enSeasonBody).optJSONArray("episodes")
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

                val epMap = mutableMapOf<Int, Triple<String?, String?, String?>>()

                fun fillMap(source: JSONArray?, isEnglish: Boolean) {
                    if (source == null) return
                    for (i in 0 until source.length()) {
                        val ep = source.getJSONObject(i)
                        val num = ep.getInt("episode_number")
                        val name = ep.optString("name").trim().takeIf { it.isNotBlank() && !it.matches(genericRegex) }
                        val summary = ep.optString("overview").trim().takeIf { it.isNotBlank() }
                        val thumb = ep.optString("still_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }

                        if (isEnglish) {
                            val existing = epMap[num]
                            epMap[num] = Triple(existing?.first ?: name, existing?.second ?: thumb, existing?.third ?: summary)
                        } else {
                            epMap[num] = Triple(name, thumb, summary)
                        }
                    }
                }

                fillMap(frEpisodes, false)
                fillMap(enEpisodes, true)

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
                            val s1Body = client.newCall(GET("$tmdbBaseUrl/tv/$id/season/1?api_key=$tmdbApiKey&language=fr-FR")).execute().use { it.body.string() }
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

                val seasonSummary = frSeasonJson.optString("overview").takeIf { it.isNotBlank() } ?: mainSummary
                TmdbMetadata(posterUrl = poster, summary = seasonSummary, author = author, artist = artist, status = status, releaseDate = releaseDate, genre = genre, episodeSummaries = epMap)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    // ============================== V16 Mandatory Stubs ==============================
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<eu.kanade.tachiyomi.animesource.model.SEpisode> = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<eu.kanade.tachiyomi.animesource.model.Hoster> = throw UnsupportedOperationException()
    override fun videoListParse(response: Response, hoster: eu.kanade.tachiyomi.animesource.model.Hoster): List<eu.kanade.tachiyomi.animesource.model.Video> = throw UnsupportedOperationException()
    override fun List<eu.kanade.tachiyomi.animesource.model.Video>.sortVideos(): List<eu.kanade.tachiyomi.animesource.model.Video> = this
}
