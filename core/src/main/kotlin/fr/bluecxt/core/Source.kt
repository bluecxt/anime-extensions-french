package fr.bluecxt.core

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.network.CloudflareInterceptor
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import kotlin.math.abs

/**
 * Base class for all French Anime Extensions using extensions-lib v16.
 * Separated from keiyoushi utils to allow custom French logic.
 */
abstract class Source :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    val preferences: SharedPreferences by getPreferencesLazy()

    open val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Default empty implementation, extensions will call addCommonPreferences
    }

    protected fun PreferenceScreen.addCommonPreferences(
        baseUrlDefault: String,
        supportedServers: List<String>,
        defaultServer: String? = null,
        supportedEntries: Array<String> = arrayOf("VOSTFR", "VF"),
    ) {
        addBaseUrlPreference(preferences, baseUrlDefault, key = CommonPreferences.PREF_URL_KEY)

        androidx.preference.ListPreference(context).apply {
            key = CommonPreferences.PREF_VOICES_KEY
            title = "Préférence des voix"
            entries = supportedEntries
            entryValues = supportedEntries
            setDefaultValue("VF")
            summary = "%s"
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(::addPreference)

        if (supportedServers.size > 1) {
            androidx.preference.ListPreference(context).apply {
                key = CommonPreferences.PREF_SERVER_KEY
                title = "Serveur préféré"
                entries = supportedServers.toTypedArray()
                entryValues = supportedServers.toTypedArray()
                setDefaultValue(defaultServer ?: supportedServers.firstOrNull() ?: "")
                summary = "%s"
                setOnPreferenceChangeListener { _, _ -> true }
            }.also(::addPreference)
        }
    }

    // ============================ Utils =============================

    fun ExtractedSource.buildFromSource(lang: String?, name: String): Video {
        val sourceQuality = this.quality
        val sourceUrl = this.url

        val finalVideo = Video(
            videoUrl = sourceUrl,
            videoTitle = buildString {
                if (!lang.isNullOrBlank()) append("($lang) ")
                append(name)
                if (!sourceQuality.isNullOrBlank()) append(" - $sourceQuality")
            },
            headers = this.headers,
            subtitleTracks = this.subtitleTracks,
            audioTracks = this.audioTracks,
        ).withDefaultHeaders(sourceUrl)

        Log.d(SERVER_LOG, "title = ${finalVideo.videoTitle} url = ${finalVideo.videoUrl}")

        return finalVideo
    }

    suspend fun extractVideos(playerUrl: String, lang: String, allowedServers: List<String>): List<Video> {
        val isFilemoonDisabled = preferences.getBoolean(CommonPreferences.PREF_DISABLE_FILEMOON_KEY, false)

        val filteredAllowedServers = if (isFilemoonDisabled) {
            allowedServers.filterNot { it.equals("Filemoon", ignoreCase = true) }
        } else {
            allowedServers
        }

        val servers = filteredAllowedServers.mapNotNull { getVideoServer(this, it) }
        val server = servers.find { s -> s.matches(playerUrl) } ?: return emptyList()

        val rawSources = runCatching {
            server.extractor(playerUrl)
        }.getOrDefault(emptyList())

        Log.d(SERVER_LOG, "name = ${server.name} data =  $rawSources")

        return rawSources.map { it.buildFromSource(lang, server.name) }
    }

    protected fun getServerName(url: String, allowedServers: List<String>): String? {
        val servers = allowedServers.mapNotNull { getVideoServer(this, it) }
        return servers.find { s -> s.matches(url) }?.name
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
        val voices = preferences.getString(CommonPreferences.PREF_VOICES_KEY, "VOSTFR")!!
        val player = preferences.getString(CommonPreferences.PREF_SERVER_KEY, "sibnet")!!
        val prefQualStr = preferences.getString(CommonPreferences.PREF_QUALITY_KEY, "Highest")!!
        val prefQualInt = prefQualStr.toIntOrNull()

        // On cherche le nombre entre le " - " et le "p"
        val qualityRegex = Regex("""\s-\s(\d+)p""")

        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(voices, true) }
                .thenByDescending { it.videoTitle.contains(player, true) }
                .thenByDescending { video ->
                    val actualQual = qualityRegex.find(video.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                    if (prefQualInt == null) {
                        actualQual
                    } else {
                        1000000 - abs(actualQual - prefQualInt)
                    }
                },
        )
    }

    /**
     * Standardized hoster sorting based on language tags and user preferences.
     */
    protected fun List<Hoster>.coreSortHosters(): List<Hoster> {
        val prefVoice = preferences.getString(CommonPreferences.PREF_VOICES_KEY, "VOSTFR")!!
        val prefServer = preferences.getString(CommonPreferences.PREF_SERVER_KEY, "sibnet")!!
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

            val formattedName = formatName(currentName, tmdbName, epNum)

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

    private fun formatName(currentName: String, tmdbName: String?, epNum: Int): String = if (currentName.contains(Regex("(?i)Episode\\s*\\d+")) || currentName.isBlank() || currentName.length < 3) {
        if (tmdbName != null) "Episode $epNum - $tmdbName" else "Episode $epNum"
    } else {
        if (currentName.contains("Episode", true)) currentName else "Episode $epNum - $currentName"
    }

    // ============================== Telemetry engine ==============================

    protected val context: Application by injectLazy()

    override val client: okhttp3.OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(CloudflareInterceptor(network.client))
            .addInterceptor { chain ->
                logUsage()
                chain.proceed(chain.request())
            }.build()
    }

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

            network.client.newCall(request).enqueue(object : okhttp3.Callback {
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

    // ============================== Mandatory Stubs ==============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override suspend fun getVideoList(episode: SEpisode): List<Video> = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()
    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()
    override fun List<Video>.sortVideos(): List<Video> = this

    companion object {
        const val PREF_VOICES_KEY = "preferred_voices"
        const val PREF_VOICES_DEFAULT = "VOSTFR"

        const val PREF_PLAYER_KEY = "preferred_server"
        const val PREF_PLAYER_DEFAULT = "sibnet"
    }
}
