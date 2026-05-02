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
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.Response
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
    // Map<EpNumber, Triple<Title, Thumb, Summary>>
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
     * Fetches metadata from TMDB using a smart two-step search.
     */
    suspend fun fetchTmdbMetadata(title: String, season: Int = 1): TmdbMetadata? {
        if (title.isBlank()) return null

        // Step 1: Try searching with the full title (most accurate)
        val firstAttempt = performTmdbSearch(title, season)
        if (firstAttempt != null) return firstAttempt

        // Step 2: If failed, try with sanitized title (removes arcs/subtitles)
        val cleanTitle = sanitizeTitle(title)
        if (cleanTitle != title) {
            return performTmdbSearch(cleanTitle, season)
        }

        return null
    }

    private suspend fun performTmdbSearch(query: String, season: Int): TmdbMetadata? {
        val searchUrl = "$tmdbBaseUrl/search/multi?api_key=$tmdbApiKey&query=${URLEncoder.encode(query, "UTF-8")}&language=fr-FR"

        return try {
            val response = client.newCall(GET(searchUrl)).execute().use { it.body.string() }
            val results = JSONObject(response).getJSONArray("results")
            if (results.length() == 0) return null

            // Find best match (TV or Movie)
            var bestMatch: JSONObject? = null
            for (i in 0 until results.length()) {
                val res = results.getJSONObject(i)
                val type = res.optString("media_type")
                if (type == "tv" || type == "movie") {
                    bestMatch = res
                    break
                }
            }

            if (bestMatch == null) return null

            val id = bestMatch.getInt("id")
            val mediaType = bestMatch.optString("media_type")
            val poster = bestMatch.optString("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
            val mainSummary = bestMatch.optString("overview").takeIf { it.isNotBlank() }

            if (mediaType == "movie") {
                val backdrop = bestMatch.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
                val movieTitle = bestMatch.optString("title").ifBlank { bestMatch.optString("name") }
                TmdbMetadata(posterUrl = poster, episodeThumbUrl = backdrop, summary = mainSummary, episodeSummaries = mapOf(1 to Triple(movieTitle, backdrop, mainSummary)))
            } else {
                // TV Series - Fetch season data in French first
                val frSeasonBody = client.newCall(GET("$tmdbBaseUrl/tv/$id/season/$season?api_key=$tmdbApiKey&language=fr-FR")).execute().use { it.body.string() }
                val frSeasonJson = JSONObject(frSeasonBody)
                val frEpisodes = frSeasonJson.optJSONArray("episodes") ?: return TmdbMetadata(posterUrl = poster, summary = mainSummary)

                // Check if we need English fallback for titles or summaries
                var needsEnglishFallback = false
                val genericRegexCheck = Regex("(?i)^(Episode|Épisode)\\s*\\d+$")
                for (i in 0 until frEpisodes.length()) {
                    val ep = frEpisodes.getJSONObject(i)
                    val name = ep.optString("name").trim()
                    val overview = ep.optString("overview").trim()
                    if (name.isBlank() || name.matches(genericRegexCheck) || overview.isBlank()) {
                        needsEnglishFallback = true
                        break
                    }
                }

                val enEpisodes = if (needsEnglishFallback) {
                    val enSeasonBody = client.newCall(GET("$tmdbBaseUrl/tv/$id/season/$season?api_key=$tmdbApiKey&language=en-US")).execute().use { it.body.string() }
                    JSONObject(enSeasonBody).optJSONArray("episodes")
                } else {
                    null
                }

                val epMap = mutableMapOf<Int, Triple<String?, String?, String?>>()
                val genericRegex = Regex("(?i)^(Episode|Épisode)\\s*\\d+$")

                for (i in 0 until frEpisodes.length()) {
                    val frEp = frEpisodes.getJSONObject(i)
                    val epNumber = frEp.getInt("episode_number")
                    val frName = frEp.optString("name").trim()
                    val frSummary = frEp.optString("overview").trim()
                    val thumb = frEp.optString("still_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }

                    var finalName = frName.takeIf { it.isNotBlank() && !it.matches(genericRegex) }
                    var finalSummary = frSummary.takeIf { it.isNotBlank() }

                    if ((finalName == null || finalSummary == null) && enEpisodes != null) {
                        for (j in 0 until enEpisodes.length()) {
                            val enEp = enEpisodes.getJSONObject(j)
                            if (enEp.getInt("episode_number") == epNumber) {
                                if (finalName == null) {
                                    val enName = enEp.optString("name").trim()
                                    finalName = enName.takeIf { it.isNotBlank() && !it.matches(Regex("(?i)^Episode\\s*\\d+$")) }
                                }
                                if (finalSummary == null) {
                                    finalSummary = enEp.optString("overview").trim().takeIf { it.isNotBlank() }
                                }
                                break
                            }
                        }
                    }

                    epMap[epNumber] = Triple(finalName, thumb, finalSummary)
                }

                val seasonSummary = frSeasonJson.optString("overview").takeIf { it.isNotBlank() } ?: mainSummary
                TmdbMetadata(posterUrl = poster, summary = seasonSummary, episodeSummaries = epMap)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    // ============================== V16 Mandatory Stubs ==============================
    // These must be implemented by the extension or will throw an error.

    override fun popularAnimeRequest(page: Int): okhttp3.Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: okhttp3.Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): okhttp3.Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: okhttp3.Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = throw UnsupportedOperationException()

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList,
    ): okhttp3.Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: okhttp3.Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = throw UnsupportedOperationException()

    override fun animeDetailsParse(response: okhttp3.Response): eu.kanade.tachiyomi.animesource.model.SAnime = throw UnsupportedOperationException()

    override fun episodeListParse(response: okhttp3.Response): List<eu.kanade.tachiyomi.animesource.model.SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: okhttp3.Response): List<eu.kanade.tachiyomi.animesource.model.SAnime> = throw UnsupportedOperationException()

    override fun hosterListParse(response: okhttp3.Response): List<eu.kanade.tachiyomi.animesource.model.Hoster> = throw UnsupportedOperationException()

    override fun videoListParse(response: okhttp3.Response, hoster: eu.kanade.tachiyomi.animesource.model.Hoster): List<eu.kanade.tachiyomi.animesource.model.Video> = throw UnsupportedOperationException()
}
