package fr.bluecxt.core

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
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
import java.text.Normalizer

const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.3"

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

    private fun logUsage() {
        try {
            val currentName = try {
                name
            } catch (_: Exception) {
                "Unknown"
            }

            // Daily check: only ping once per day per extension
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val lastPingDate = preferences.getString("last_usage_ping", "")

            if (lastPingDate == today) return

            // Save today's date
            preferences.edit().putString("last_usage_ping", today).apply()
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            ) ?: "unknown"

            // Récupérer la version de l'extension
            val version = try {
                val pkgName = this.javaClass.`package`?.name ?: context.packageName
                context.packageManager.getPackageInfo(pkgName, 0).versionName
            } catch (_: Exception) {
                "Unknown"
            }

            // Hash simple pour l'anonymat (SHA-256)
            val bytes = androidId.toByteArray()
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            val hashedId = digest.fold("") { str, it -> str + "%02x".format(it) }.take(16)

            val url = "https://script.google.com/macros/s/AKfycbwpj3uZXjm--bPlnIVNnMoPlZtWtkcxmmtMsJeoHVZ4Nl4S96rq9DrrHstxQeZ9m3-ONg/exec" +
                "?name=${java.net.URLEncoder.encode(currentName, "UTF-8")}" +
                "&uid=$hashedId" +
                "&version=${java.net.URLEncoder.encode(version, "UTF-8")}"

            Log.d("SourceTelemetry", "Envoi usage quotidien vers : $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    Log.e("SourceTelemetry", "Erreur réseau pour $currentName", e)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val body = response.body.string()
                    if (response.isSuccessful) {
                        Log.d("SourceTelemetry", "Usage enregistré pour $currentName : $body")
                    } else {
                        Log.e("SourceTelemetry", "Erreur serveur : ${response.code} - $body")
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e("SourceTelemetry", "Erreur critique dans logUsage", e)
        }
    }

    protected fun displayToast(message: String, length: Int = Toast.LENGTH_SHORT) {
        handler.post {
            Toast.makeText(context, message, length).show()
        }
    }

    // ============================ Utils =============================
    private val qualityCleanRegex = Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)(?:/s)?")
    private val qualitySizeRegex = Regex("\\s*\\(\\d+x\\d+\\)")
    private val qualityDefaultRegex = Regex("(?i)(Sendvid|Sibnet|VK|VidMoly|Voe|Vidoza|Streamtape|Doodstream):default")
    private val whitespaceRegex = Regex("\\s+")

    /**
     * Standardized video label cleaning.
     * Removes file sizes, resolutions, technical suffixes and redundant server names.
     */
    protected fun coreCleanQuality(quality: String): String {
        var cleaned = quality.replace(qualityCleanRegex, "")
            .replace(qualitySizeRegex, "")
            .replace(qualityDefaultRegex, "")
            .replace(" - - ", " - ")
            .trim()
            .removeSuffix("-")
            .trim()

        val servers = listOf("VidMoly", "Sibnet", "Sendvid", "VK", "Voe", "Vidoza", "Streamtape", "Doodstream", "Embed4me", "SeekStreaming")
        for (server in servers) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server(?!:)", RegexOption.IGNORE_CASE), server)
            cleaned = cleaned.replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(whitespaceRegex, " ").replace(" - - ", " - ").trim()
    }

    /**
     * Normalizes a URL by removing the domain if it matches the base URL
     * and ensuring it starts with a leading slash.
     */
    protected fun coreCleanUrl(url: String): String {
        if (url.isBlank()) return ""
        val fixed = if (url.startsWith("http")) {
            url.replace(Regex("^https?://[^/]+"), "")
        } else {
            url
        }
        return if (fixed.startsWith("/")) fixed else "/$fixed"
    }

    /**
     * Safely sets the fetch type using reflection for backward compatibility.
     */
    protected fun SAnime.coreSetFetchType(type: eu.kanade.tachiyomi.animesource.model.FetchType) {
        try {
            val methods = this.javaClass.methods
            val setter = methods.find { it.name == "setFetch_type" }
            if (setter != null) {
                val appFetchTypeClass = setter.parameterTypes[0]
                val enumValue = appFetchTypeClass.enumConstants?.find { it.toString() == type.name }
                setter.invoke(this, enumValue)
            }
        } catch (_: Exception) {}
    }

    /**
     * Safely sets the season number using reflection for backward compatibility.
     */
    protected fun SAnime.coreSetSeasonNumber(season: Double) {
        try {
            val methods = this.javaClass.methods
            val setter = methods.find { it.name == "setSeason_number" }
            if (setter != null) {
                setter.invoke(this, season)
            }
        } catch (_: Exception) {}
    }

    /**
     * Standardized video sorting based on user preferences.
     */
    protected fun List<Video>.coreSortVideos(): List<Video> {
        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!

        val pQualityRegex = Regex("""(\d+)p""")

        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains(voices, true) }
                .thenByDescending { it.quality.contains(player, true) }
                .thenByDescending {
                    pQualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    /**
     * Standardized hoster sorting based on language tags and user preferences.
     */
    protected fun List<Hoster>.coreSortHosters(): List<Hoster> {
        val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val prefServer = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!
        val langRegex = Regex("\\((.*?)\\)")

        return this.sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains("($prefVoice)", true) }
                .thenBy {
                    langRegex.find(it.hosterName)?.value ?: "(Unknown)"
                }
                .thenByDescending { it.hosterName.contains(prefServer, true) },
        )
    }

    // ============================ Season Engine =============================

    /**
     * Optimized title display logic according to REPO_RULES.
     * Shortens seasons to numbers and detects special sub-titles/spin-offs.
     * Prepends full title to description if shortened to a sub-title.
     */
    protected fun SAnime.coreOptimizeDisplayTitle(fullTitle: String, seriesTitle: String) {
        var isSubTitleOnly = false
        val effectiveSeriesTitle = if (seriesTitle.isNotEmpty() && fullTitle.contains(seriesTitle, ignoreCase = true)) {
            seriesTitle
        } else if (this.title.isNotEmpty() && fullTitle.contains(this.title, ignoreCase = true)) {
            this.title
        } else {
            ""
        }

        if (effectiveSeriesTitle.isNotEmpty()) {
            val suffix = fullTitle.split(Regex("(?i)${Regex.escape(effectiveSeriesTitle)}"), 2)
                .last().trim().removePrefix(":").removePrefix("-").trim()

            if (suffix.isNotEmpty()) {
                val isStandardSeason = suffix.matches(Regex("""(?i)(?:Saison|Season|Partie|Part|Film|Movie|OAV|OVA|Special|HS|Kai|\d+).*"""))
                if (!isStandardSeason) {
                    this.title = suffix
                    isSubTitleOnly = true
                }
            }
        }

        // REPO_RULES: "Saison X" -> "X" for consistency and space (Saison 1 is removed)
        this.title = this.title
            .replace(Regex("""(?i)\s*-\s*Saison\s*1(?!\d)"""), "")
            .replace(Regex("""(?i)\s*Saison\s*1(?!\d)"""), "")
            .replace(Regex("""(?i)\s*-\s*Saison\s*(\d+)"""), " $1")
            .replace(Regex("""(?i)\s*Saison\s*(\d+)"""), " $1")
            .replace(Regex("""(?i)Partie\s*(\d+)"""), "Part $1")
            .trim()

        // Always put raw full title at the very top if the display title was optimized to a subtitle
        // We check if it's NOT a standard numeric season to avoid "Fullmetal Alchemist 1" in description
        val isNumericSeason = this.title.matches(Regex("""(?i).*\s*\d+$|^Saison\s*\d+$|^\d+$"""))
        if (isSubTitleOnly || (this.title != fullTitle && !isNumericSeason)) {
            this.description = "$fullTitle\n\n${this.description ?: ""}"
        }
    }

    /**
     * Builds a standardized list of SAnime seasons for hierarchical mode.
     * Applies TMDB continuation fallbacks and REPO_RULES automatically.
     */
    protected suspend fun coreBuildSeasonList(
        baseTitle: String,
        siteSeasons: List<Triple<String, String, Int>>, // Title, URL, SiteSNum
        defStatus: Int = SAnime.UNKNOWN,
    ): List<SAnime> = siteSeasons.mapIndexed { index, (sTitle, sUrl, siteSNum) ->
        val tmdbMeta = fetchTmdbMetadata(baseTitle, siteSNum)
        val isContinuation = siteSNum > 1 && (tmdbMeta == null || tmdbMeta.episodeSummaries.size < 2)
        val finalMeta = if (isContinuation) fetchTmdbMetadata(baseTitle, siteSNum - 1) else tmdbMeta

        SAnime.create().apply {
            this.title = sTitle
            this.url = sUrl
            thumbnail_url = finalMeta?.posterUrl
            description = finalMeta?.summary
            genre = finalMeta?.genre
            author = finalMeta?.author
            artist = finalMeta?.artist
            status = if (index < siteSeasons.size - 1) SAnime.COMPLETED else (finalMeta?.status ?: defStatus)

            coreOptimizeDisplayTitle(sTitle, baseTitle)
            coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
            coreSetSeasonNumber(siteSNum.toDouble())
            initialized = true
        }
    }

    /**
     * Calculates the episode offset for TMDB alignment.
     * Handles seasons mapping and OAV overflow detection.
     */
    protected suspend fun coreCalculateEpisodeOffset(
        baseTitle: String,
        targetTmdbSNum: Int,
        previousSeasonsCounts: Map<Int, Int>, // SiteSNum -> EpisodeCount
    ): Pair<Int, Int> {
        var siteOffsetAccumulator = 0
        var oavOffsetAccumulator = 0
        var lastTmdbSNum = -1

        val sortedSiteSeasons = previousSeasonsCounts.keys.sorted()

        for (sNum in sortedSiteSeasons) {
            val siteCount = previousSeasonsCounts[sNum] ?: 0

            // Determine TMDB Season
            var tmdbS = sNum
            val meta = fetchTmdbMetadata(baseTitle, tmdbS)
            if (sNum > 1 && (meta == null || meta.episodeSummaries.size < 2)) {
                tmdbS = sNum - 1
            }

            if (tmdbS != lastTmdbSNum) {
                siteOffsetAccumulator = 0
                oavOffsetAccumulator = 0
                lastTmdbSNum = tmdbS
            }

            if (tmdbS == targetTmdbSNum) {
                // We reached the current group
                return siteOffsetAccumulator to oavOffsetAccumulator
            }

            // Accumulate counts for previous groups
            val currentTmdbMeta = fetchTmdbMetadata(baseTitle, tmdbS)
            val currentTmdbCount = currentTmdbMeta?.episodeSummaries?.size ?: 0

            if (currentTmdbCount > 0) {
                val remainingInTmdb = maxOf(0, currentTmdbCount - siteOffsetAccumulator)
                siteOffsetAccumulator += minOf(siteCount, remainingInTmdb)
                if (siteCount > remainingInTmdb) {
                    oavOffsetAccumulator += (siteCount - remainingInTmdb)
                }
            } else {
                siteOffsetAccumulator += siteCount
            }
        }

        return siteOffsetAccumulator to oavOffsetAccumulator
    }

    /**
     * Maps raw episodes to TMDB metadata with offsets and formatting.
     */
    protected fun coreMapEpisodes(
        rawEpisodes: List<eu.kanade.tachiyomi.animesource.model.SEpisode>,
        tmdbMetadata: TmdbMetadata?,
        tmdbS0Metadata: TmdbMetadata?,
        offsets: Pair<Int, Int>, // Pair(siteOffset, oavOffset)
        sNum: Int,
        isMovie: Boolean = false,
        isOav: Boolean = false,
    ): List<eu.kanade.tachiyomi.animesource.model.SEpisode> {
        val (siteOffset, oavOffset) = offsets
        val tmdbSeasonEpisodeCount = tmdbMetadata?.episodeSummaries?.size ?: 0

        return rawEpisodes.map { episode ->
            val epNum = episode.episode_number.toInt()
            val absEpNum = epNum + siteOffset

            var epMeta = tmdbMetadata?.episodeSummaries?.get(absEpNum)

            // If not found and we are beyond the season count, try Season 0 (Specials)
            if (epMeta == null && tmdbSeasonEpisodeCount > 0 && absEpNum > tmdbSeasonEpisodeCount) {
                val s0EpNum = (absEpNum - tmdbSeasonEpisodeCount) + oavOffset
                epMeta = tmdbS0Metadata?.episodeSummaries?.get(s0EpNum)
            }

            val tmdbName = epMeta?.first
            val currentName = episode.name.replace("Épisode", "Episode", true)

            val formattedName = if (currentName.contains(Regex("(?i)Episode\\s*\\d+")) || currentName.isBlank() || currentName.length < 3) {
                if (tmdbName != null) "Episode $epNum - $tmdbName" else "Episode $epNum"
            } else {
                if (currentName.contains("Episode", true)) currentName else "Episode $epNum - $currentName"
            }

            val sPrefix = when {
                isMovie -> "[Movie] "
                isOav -> "[Special] "
                sNum > 1 -> "[S$sNum] "
                else -> ""
            }

            episode.apply {
                name = "$sPrefix$formattedName"
                preview_url = epMeta?.second
                summary = epMeta?.third
            }
        }
    }

    // ============================== TMDB Engine ==============================
    private val tmdbApiKey = "24621da8ae19dce721e59eff2ab479bb"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    companion object {
        const val PREF_VOICES_KEY = "preferred_voices"
        const val PREF_VOICES_DEFAULT = "VOSTFR"

        const val PREF_PLAYER_KEY = "preferred_server"
        const val PREF_PLAYER_DEFAULT = "sibnet"

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
    }

    /**
     * Sanitizes a title for better TMDB search results.
     */
    protected open fun sanitizeTitle(title: String): String = title
        .replace(Regex("(?i)\\(TV\\)|\\(Films?s?\\)|\\(OAVs?\\)|\\(ONAs?\\)|\\(Specials?\\)|VF|VOSTFR"), "")
        .replace(Regex("(?i)\\s*(?:Saison|Season|Part(?:ie)?)\\s*\\d+.*"), "") // Remove season/part info
        .replace(Regex("\\s+-\\s+.*$"), "") // Only remove after " - " (space dash space) to avoid removing "-kun"
        .replace(Regex("\\s+\\d+$"), "") // Remove trailing digits
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun isTitleSimilar(q1: String, q2: String): Boolean {
        fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        val s1 = normalize(q1)
        val s2 = normalize(q2)

        val flat1 = s1.replace(" ", "")
        val flat2 = s2.replace(" ", "")

        if (flat1.isBlank() || flat2.isBlank()) return false

        // Direct containment (handles most cases)
        if (flat1.contains(flat2) || flat2.contains(flat1)) return true

        // Word-based matching for translated titles
        val words1 = s1.split(" ").filter { it.length >= 2 }
        val words2 = s2.split(" ").filter { it.length >= 2 }

        if (words1.isEmpty() || words2.isEmpty()) return false

        val matchingWords = words1.count { w1 -> words2.any { w2 -> w1 == w2 } }
        val score = matchingWords.toDouble() / maxOf(words1.size, words2.size)

        return score >= 0.5
    }

    /**
     * Fetches metadata from TMDB specifically for a movie.
     * Only returns a result if there's exactly one match to ensure accuracy.
     */
    suspend fun fetchTmdbMovieMetadata(query: String, lang: String = "fr-FR"): TmdbMetadata? {
        val searchUrl = "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=${URLEncoder.encode(query, "UTF-8")}&language=$lang"
        return try {
            val response = client.newCall(GET(searchUrl)).execute().use { it.body.string() }
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
    suspend fun fetchTmdbMetadata(title: String, season: Int = 1, type: String? = null, lang: String = "fr-FR"): TmdbMetadata? {
        if (title.isBlank()) return null
        val cacheKey = "$title-$season-$type-$lang"
        if (tmdbCache.containsKey(cacheKey)) return tmdbCache[cacheKey]

        // Step 1: Try searching with the full title (most accurate)
        val firstAttempt = performTmdbSearch(title, season, type, lang)
        if (firstAttempt != null) {
            tmdbCache[cacheKey] = firstAttempt
            return firstAttempt
        }

        // Step 2: If failed, try with sanitized title
        val cleanTitle = sanitizeTitle(title)
        if (cleanTitle != title && cleanTitle.isNotBlank()) {
            val secondAttempt = performTmdbSearch(cleanTitle, season, type, lang)
            if (secondAttempt != null) {
                tmdbCache[cacheKey] = secondAttempt
                return secondAttempt
            }
        }

        tmdbCache[cacheKey] = null
        return null
    }

    /**
     * Filters TMDB metadata to remove non-fiction content (PVs, recaps, interviews, etc.).
     * Re-aligns episode numbers after filtering.
     */
    fun filterSmartMetadata(meta: TmdbMetadata, isSpecialSeason: Boolean = false): TmdbMetadata {
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
    suspend fun fetchTmdbMetadataById(id: Int, type: String, season: Int = 1, lang: String = "fr-FR"): TmdbMetadata? = try {
        val typeClean = if (type == "tv" || type == "series") "tv" else "movie"
        constructMetadata(id, typeClean, season, lang)
    } catch (_: Exception) {
        null
    }

    private suspend fun performTmdbSearch(query: String, season: Int, type: String? = null, lang: String = "fr-FR"): TmdbMetadata? {
        val searchPath = when (type?.lowercase()) {
            "movie", "film" -> "search/movie"
            "tv", "series", "série" -> "search/tv"
            else -> "search/multi"
        }
        val searchUrl = "$tmdbBaseUrl/$searchPath?api_key=$tmdbApiKey&query=${URLEncoder.encode(query, "UTF-8")}&language=$lang"

        try {
            val response = client.newCall(GET(searchUrl)).execute().use { it.body.string() }
            val results = JSONObject(response).getJSONArray("results")
            if (results.length() == 0) {
                return if (lang != "en-US") performTmdbSearch(query, season, type, "en-US") else null
            }

            // Separate results into Animation and Other, but only if they are similar
            var bestAnimationMatch: JSONObject? = null
            var maxAnimationVotes = -1
            var bestOtherMatch: JSONObject? = null
            var maxOtherVotes = -1

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

                var isSimilar = isTitleSimilar(query, resultTitle) || (resultOriginalTitle.isNotBlank() && isTitleSimilar(query, resultOriginalTitle))

                if (!isSimilar) {
                    // Try fetching alternative titles for better Romanized matching
                    val bestId = res.getInt("id")
                    val altUrl = "$tmdbBaseUrl/$mType/$bestId/alternative_titles?api_key=$tmdbApiKey"
                    try {
                        val altRes = client.newCall(GET(altUrl)).execute().use { it.body.string() }
                        val altArray = JSONObject(altRes).getJSONArray("results")
                        for (j in 0 until altArray.length()) {
                            val alt = altArray.getJSONObject(j).optString("title")
                            if (isTitleSimilar(query, alt)) {
                                isSimilar = true
                                break
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                if (!isSimilar) continue

                val votes = res.optInt("vote_count", 0)
                val genreIds = res.optJSONArray("genre_ids")
                val isAnimation = genreIds?.let { ids ->
                    (0 until ids.length()).any { ids.getInt(it) == 16 } // 16 = Animation
                } ?: false

                if (isAnimation) {
                    if (votes > maxAnimationVotes) {
                        maxAnimationVotes = votes
                        bestAnimationMatch = res
                    }
                } else {
                    if (votes > maxOtherVotes) {
                        maxOtherVotes = votes
                        bestOtherMatch = res
                    }
                }
            }

            val bestMatch = bestAnimationMatch ?: bestOtherMatch
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

    private fun constructMetadata(id: Int, mediaType: String, season: Int, lang: String): TmdbMetadata? {
        return try {
            // Fetch full details to get author/artist/studios/status
            val detailUrl = "$tmdbBaseUrl/$mediaType/$id?api_key=$tmdbApiKey&language=$lang&append_to_response=credits"
            val detailResponse = client.newCall(GET(detailUrl)).execute().use { it.body.string() }
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
                TmdbMetadata(posterUrl = mainPoster, episodeThumbUrl = backdrop, summary = mainSummary, author = author, artist = artist, status = status, releaseDate = releaseDate, genre = genre, episodeSummaries = mapOf(1 to Triple(movieTitle, backdrop, mainSummary)))
            } else {
                val originalLang = detailJson.optString("original_language", "ja")

                // TV Series - Fetch season data (Requested language first)
                val langSeasonBody = client.newCall(GET("$tmdbBaseUrl/tv/$id/season/$season?api_key=$tmdbApiKey&language=$lang")).execute().use { it.body.string() }
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
                        val enSeasonBody = client.newCall(GET("$tmdbBaseUrl/tv/$id/season/$season?api_key=$tmdbApiKey&language=en-US")).execute().use { it.body.string() }
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
                        val origSeasonBody = client.newCall(GET("$tmdbBaseUrl/tv/$id/season/$season?api_key=$tmdbApiKey&language=$originalLang")).execute().use { it.body.string() }
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
                            val s1Body = client.newCall(GET("$tmdbBaseUrl/tv/$id/season/1?api_key=$tmdbApiKey&language=$lang")).execute().use { it.body.string() }
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
                TmdbMetadata(posterUrl = seasonPoster, episodeThumbUrl = seasonPoster, summary = seasonSummary, author = author, artist = artist, status = status, releaseDate = releaseDate, genre = genre, episodeSummaries = epMap)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    /**
     * Common logic to fetch anime details.
     * Injected with logUsage("DETAILS") to track popularity.
     */
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        logUsage()
        return anime
    }

    override fun headersBuilder(): okhttp3.Headers.Builder {
        logUsage()
        return super.headersBuilder()
    }

    // ============================== V16 Mandatory Stubs ==============================
    override fun popularAnimeRequest(page: Int): Request {
        logUsage()
        throw UnsupportedOperationException()
    }
    override fun popularAnimeParse(response: Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request {
        logUsage()
        throw UnsupportedOperationException()
    }
    override fun latestUpdatesParse(response: Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        logUsage()
        throw UnsupportedOperationException()
    }
    override fun searchAnimeParse(response: Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<eu.kanade.tachiyomi.animesource.model.SEpisode> = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<eu.kanade.tachiyomi.animesource.model.Hoster> = throw UnsupportedOperationException()
    override fun videoListParse(response: Response, hoster: eu.kanade.tachiyomi.animesource.model.Hoster): List<eu.kanade.tachiyomi.animesource.model.Video> = throw UnsupportedOperationException()
    override fun List<eu.kanade.tachiyomi.animesource.model.Video>.sortVideos(): List<eu.kanade.tachiyomi.animesource.model.Video> = this
}
