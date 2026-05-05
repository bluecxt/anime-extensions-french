package eu.kanade.tachiyomi.animeextension.fr.animesama

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import fr.bluecxt.core.Source
import fr.bluecxt.core.TmdbMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class AnimeSama : Source() {

    override val name = "Anime-Sama"

    override val baseUrl: String
        get() = preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!

    override val lang = "fr"

    override val supportsLatest = true

    override val client = network.client

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val page = response.request.url.fragment?.toIntOrNull() ?: 1
        val chunks = doc.select("#containerPepites > div a").chunked(5)
        val seasons = chunks.getOrNull(page - 1)?.flatMap {
            val animeUrl = "$baseUrl${it.attr("href")}"
            fetchAnimeSeasons(animeUrl)
        }?.toList().orEmpty()
        return AnimesPage(seasons, page < chunks.size)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/#$page")

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = response.asJsoup()
        val seasons = animes.select("#containerAjoutsAnimes > div").flatMap {
            val animeUrl = it.getElementsByTag("a").attr("abs:href").toHttpUrl()
            val url = animeUrl.newBuilder()
                .removePathSegment(animeUrl.pathSize - 2)
                .removePathSegment(animeUrl.pathSize - 3)
                .build()
            fetchAnimeSeasons(url.toString())
        }.distinctBy { it.url }
        return AnimesPage(seasons, false)
    }
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    // =============================== Search ===============================
    override fun getFilterList() = AnimeSamaFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder()
        val params = AnimeSamaFilters.getSearchFilters(filters)
        params.types.forEach { url.addQueryParameter("type[]", it) }
        params.language.forEach { url.addQueryParameter("langue[]", it) }
        params.genres.forEach { url.addQueryParameter("genre[]", it) }
        url.addQueryParameter("search", query)
        url.addQueryParameter("page", "$page")
        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val anime = document.select("#list_catalog > div a").parallelFlatMapBlocking {
            fetchAnimeSeasons(it.attr("href"))
        }
        val page = response.request.url.queryParameterValues("page").firstOrNull()
        val hasNextPage = document.select("#list_pagination a:last-child").text() != page
        return AnimesPage(anime, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        // Fetch HD Metadata from TMDB (Description and secondary posters)
        val tmdbMetadata = fetchSmartTmdbMetadata(anime.title)
        tmdbMetadata?.summary?.let { anime.description = it }
        // Note: keeping site thumbnail as per user preference in similar cases,
        // unless TMDB is a perfect match (done in fr.bluecxt.core.Source logic if requested)
        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = "$baseUrl${anime.url.substringBeforeLast("/")}"
        val movie = anime.url.split("#").getOrElse(1) { "" }.toIntOrNull()
        val players = VOICES_VALUES.map { fetchPlayers("$animeUrl/$it") }
        val episodes = playersToEpisodes(players, anime.title, anime.url)
        return if (movie == null) episodes.reversed() else listOf(episodes[movie])
    }

    private suspend fun fetchSmartTmdbMetadata(title: String): TmdbMetadata? {
        if (title.isBlank()) return null

        val seasonRegex = Regex("""(?i)(.*?)\s+(?:Saison|Season)\s*(\d+)""")
        val oavRegex = Regex("""(?i)(.*?)\s+(?:OAV|OVA|Special)""")
        val movieRegex = Regex("""(?i)(.*?)\s+(?:FILM|MOVIE)""")

        return when {
            seasonRegex.containsMatchIn(title) -> {
                val match = seasonRegex.find(title)!!
                val cleanTitle = match.groupValues[1].trim()
                val season = match.groupValues[2].toIntOrNull() ?: 1
                fetchTmdbMetadata(cleanTitle, season, "tv")
            }

            oavRegex.containsMatchIn(title) -> {
                val match = oavRegex.find(title)!!
                val cleanTitle = match.groupValues[1].trim()
                val meta = fetchTmdbMetadata(cleanTitle, 0, "tv")
                meta?.let { filterSmartMetadata(it, isSpecialSeason = true) }
            }

            movieRegex.containsMatchIn(title) -> {
                val match = movieRegex.find(title)!!
                val cleanTitle = match.groupValues[1].trim()
                fetchTmdbMetadata(cleanTitle, 1, "movie")
            }

            else -> fetchTmdbMetadata(title)
        }
    }

    // ============================ Video Links =============================

    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val vidmolyExtractor by lazy { VidMolyASExtractor(client, headers) }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val playerUrls = json.decodeFromString<List<List<String>>>(episode.url)
        val hosters = mutableListOf<Hoster>()

        playerUrls.forEachIndexed { i, it ->
            val lang = VOICES_VALUES[i].uppercase()
            it.forEach { playerUrl ->
                val server = when {
                    playerUrl.contains("sibnet.ru") -> "Sibnet"
                    playerUrl.contains("vk.") -> "VK"
                    playerUrl.contains("sendvid.com") -> "Sendvid"
                    playerUrl.contains("vidmoly.") -> "VidMoly"
                    else -> "Serveur"
                }
                hosters.add(Hoster(hosterName = "($lang) $server", internalData = "$playerUrl|$lang"))
            }
        }
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|")
        val playerUrl = data[0]
        val lang = data[1]
        val server = hoster.hosterName.substringAfter(") ")
        val prefix = "($lang) $server - "

        val videos = when {
            playerUrl.contains("sibnet.ru") -> sibnetExtractor.videosFromUrl(playerUrl, prefix)
            playerUrl.contains("vk.") -> vkExtractor.videosFromUrl(playerUrl, prefix)
            playerUrl.contains("sendvid.com") -> sendvidExtractor.videosFromUrl(playerUrl, prefix)
            playerUrl.contains("vidmoly.") -> vidmolyExtractor.videosFromUrl(playerUrl, prefix)
            else -> emptyList()
        }

        return videos.map { video ->
            val updatedHeaders = (video.headers ?: Headers.Builder().build()).newBuilder()
                .set("User-Agent", headers["User-Agent"]!!)
                .build()
            Video(
                videoUrl = video.videoUrl,
                videoTitle = cleanQuality(video.videoTitle),
                headers = updatedHeaders,
                subtitleTracks = video.subtitleTracks,
                audioTracks = video.audioTracks,
            )
        }.sortVideos()
    }

    private val qualityCleanRegex = Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s")
    private val qualitySizeRegex = Regex("\\s*\\(\\d+x\\d+\\)")
    private val qualityDefaultRegex = Regex("(?i)(Sendvid|Sibnet|VK|VidMoly):default")
    private val pQualityRegex = Regex("""(\d+)p""")
    private val whitespaceRegex = Regex("\\s+")

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(qualityCleanRegex, "")
            .replace(qualitySizeRegex, "")
            .replace(qualityDefaultRegex, "")
            .replace(" - - ", " - ")
            .trim()
            .removeSuffix("-")
            .trim()

        val servers = listOf("VidMoly", "Sibnet", "Sendvid", "VK")
        for (server in servers) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server(?!:)", RegexOption.IGNORE_CASE), server)
            cleaned = cleaned.replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(whitespaceRegex, " ").replace(" - - ", " - ").trim()
    }

    // ============================ Utils =============================
    override fun List<Video>.sortVideos(): List<Video> {
        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!

        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains("($voices", true) }
                .thenByDescending { it.videoTitle.contains(quality) }
                .thenByDescending {
                    pQualityRegex.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                .thenByDescending { it.videoTitle.contains(player, true) },
        )
    }

    private fun fetchAnimeSeasons(animeUrl: String): List<SAnime> {
        val res = client.newCall(GET(animeUrl)).execute()
        return fetchAnimeSeasons(res)
    }

    private val commentRegex by lazy { Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL) }
    private val seasonRegex by lazy { Regex("^\\s*panneauAnime\\(\"(.*)\", \"(.*)\"\\)", RegexOption.MULTILINE) }

    private fun fetchAnimeSeasons(response: Response): List<SAnime> {
        val animeDoc = response.asJsoup()
        val animeUrl = response.request.url
        val animeName = animeDoc.getElementById("titreOeuvre")?.text() ?: ""

        val scripts = animeDoc.select("h2 + p + div > script, h2 + div > script").toString()
        val uncommented = commentRegex.replace(scripts, "")
        val animes = seasonRegex.findAll(uncommented).flatMapIndexed { animeIndex, seasonMatch ->
            val (seasonName, seasonStem) = seasonMatch.destructured
            if (seasonStem.contains("film", true)) {
                val moviesUrl = "$animeUrl/$seasonStem"
                val movies = fetchPlayers(moviesUrl).ifEmpty { return@flatMapIndexed emptyList() }
                val movieNameRegex = Regex("^\\s*newSPF\\(\"(.*)\"\\);", RegexOption.MULTILINE)
                val moviesDoc = client.newCall(GET(moviesUrl)).execute().body.string()
                val matches = movieNameRegex.findAll(moviesDoc).toList()
                List(movies.size) { i ->
                    val title = when {
                        animeIndex == 0 && movies.size == 1 -> animeName
                        matches.size > i -> "$animeName ${matches[i].destructured.component1()}"
                        movies.size == 1 -> "$animeName Film"
                        else -> "$animeName Film ${i + 1}"
                    }
                    Triple(title, "$moviesUrl#$i", SAnime.COMPLETED)
                }
            } else {
                listOf(Triple("$animeName $seasonName", "$animeUrl/$seasonStem", SAnime.UNKNOWN))
            }
        }

        return animes.map {
            SAnime.create().apply {
                title = it.first
                thumbnail_url = animeDoc.getElementById("coverOeuvre")?.attr("src")
                description = animeDoc.select("h2:contains(synopsis) + p").text()
                genre = animeDoc.select("h2:contains(genres) + a").text().replace(" - ", ", ")
                setUrlWithoutDomain(it.second)
                status = it.third
                initialized = true
            }
        }.toList()
    }

    private suspend fun playersToEpisodes(list: List<List<List<String>>>, animeTitle: String, animeUrlPath: String): List<SEpisode> {
        val maxEpisodes = list.fold(0) { acc, it -> maxOf(acc, it.size) }

        // Extract nomenclature info from title
        val sNumRegex = Regex("""(?i)(?:Saison|Season)\s*(\d+)""")
        val sNumMatch = sNumRegex.find(animeTitle)
        val isOav = animeTitle.contains("OAV", true) || animeTitle.contains("OVA", true) || animeTitle.contains("Special", true)
        val isMovie = animeTitle.contains("FILM", true) || animeTitle.contains("MOVIE", true)

        val sNum = if (isOav) 0 else sNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        // Fetch primary metadata
        val tmdbMetadata = fetchSmartTmdbMetadata(animeTitle)
        val tmdbEpCount = tmdbMetadata?.episodeSummaries?.size ?: 0

        val hasOavs = maxEpisodes > tmdbEpCount
        val sPrefix = when {
            isMovie -> "[Movie] "

            isOav -> "[OAV] "

            sNumMatch != null -> {
                if (sNum == 1 && !hasOavs) "" else "[S$sNum] "
            }

            else -> ""
        }

        val baseTitle = animeTitle.replace(sNumRegex, "").trim()

        // Calculate OAV offset from previous seasons
        var oavOffset = 0
        if (sNum > 1) {
            val baseDir = animeUrlPath.substringBeforeLast("/", "").substringBeforeLast("/", "")
            for (i in 1 until sNum) {
                val prevAsCount = countEpisodesInSeason("$baseDir/saison$i")
                if (prevAsCount > 0) {
                    val prevTmdbMeta = fetchTmdbMetadata(baseTitle, i, "tv")
                    val prevTmdbCount = prevTmdbMeta?.episodeSummaries?.size ?: 0
                    if (prevAsCount > prevTmdbCount) {
                        oavOffset += (prevAsCount - prevTmdbCount)
                    }
                }
            }
        }

        // Handle overflow metadata (S0) if we are in a regular season
        val s0Metadata = if (sNum > 0 && maxEpisodes > tmdbEpCount) {
            fetchTmdbMetadata(baseTitle, 0, "tv")?.let { filterSmartMetadata(it, isSpecialSeason = true) }
        } else {
            null
        }

        return List(maxEpisodes) { episodeNumber ->
            val epNum = episodeNumber + 1
            val players = list.map { it.getOrElse(episodeNumber) { emptyList() } }

            var epMeta = if (isMovie) {
                tmdbMetadata?.episodeSummaries?.get(1)
            } else {
                tmdbMetadata?.episodeSummaries?.get(epNum)
            }

            var finalPrefix = sPrefix

            // Overflow to OAVs mapping
            if (sNum > 0 && epNum > tmdbEpCount && s0Metadata != null) {
                val s0EpNum = (epNum - tmdbEpCount) + oavOffset
                val s0Meta = s0Metadata.episodeSummaries[s0EpNum]
                if (s0Meta != null) {
                    epMeta = s0Meta
                    finalPrefix = "[OAV] "
                }
            }

            SEpisode.create().apply {
                val tmdbName = epMeta?.first
                val baseName = if (tmdbName != null) "Episode $epNum - $tmdbName" else "Episode $epNum"
                name = "$finalPrefix$baseName"
                url = json.encodeToString(players)
                episode_number = epNum.toFloat()
                scanlator = players.mapIndexedNotNull { i, it -> if (it.isNotEmpty()) VOICES_VALUES[i] else null }.joinToString().uppercase()

                // Metadata from TMDB
                preview_url = epMeta?.second
                summary = epMeta?.third
            }
        }
    }

    private fun countEpisodesInSeason(seasonPath: String): Int {
        return try {
            val docUrl = "$baseUrl$seasonPath/vostfr/episodes.js"
            val js = client.newCall(GET(docUrl)).execute().use {
                if (!it.isSuccessful) return 0
                it.body.string()
            }
            // Simple regex to find the size of the first array (eps1)
            val match = Regex("""eps1\s*=\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(js)
            match?.groupValues?.get(1)?.split(",")?.size ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun fetchPlayers(url: String): List<List<String>> {
        val docUrl = "$url/episodes.js"
        val doc = client.newCall(GET(docUrl)).execute().use {
            if (!it.isSuccessful) return emptyList()
            it.body.string()
        }
        val urls = QuickJs.create().use { qjs ->
            qjs.evaluate(doc)
            val res = qjs.evaluate(
                $$"""
                JSON.stringify(
                    Array.from({length: 40}, (e,i) => this[`eps${i + 1}`])
                        .filter(e => e !== undefined && e !== null)
                )
            """,
            )
            json.decodeFromString<List<List<String>>>(res as String)
        }

        if (urls.isEmpty() || urls[0].isEmpty()) return emptyList()
        return List(urls[0].size) { i -> urls.mapNotNull { it.getOrNull(i) }.distinct() }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = PREF_URL_TITLE
            summary = PREF_URL_SUMMARY
            setDefaultValue(PREF_URL_DEFAULT)
            dialogTitle = PREF_URL_TITLE
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    preferences.edit().putString(PREF_URL_KEY, newValue as String).apply()
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = "Voices preference"
            entries = VOICES
            entryValues = VOICES_VALUES
            setDefaultValue(PREF_VOICES_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_PLAYER_KEY
            title = "Default player"
            entries = PLAYERS
            entryValues = PLAYERS_VALUES
            setDefaultValue(PREF_PLAYER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_URL_KEY = "base_url_pref"
        private const val PREF_URL_TITLE = "Base URL"
        private const val PREF_URL_DEFAULT = "https://anime-sama.to"
        private const val PREF_URL_SUMMARY = "To change the domain of the extension. See https://anime-sama.pw"

        private val voicesMap = mapOf(
            "Prefer VOSTFR" to "vostfr",
            "Prefer VF" to "vf",
            "Prefer VF1" to "vf1",
            "Prefer VF2" to "vf2",
            "Prefer VA" to "va",
            "Prefer VCN" to "vcn",
            "Prefer VJ" to "vj",
            "Prefer VKR" to "vkr",
            "Prefer VQC" to "vqc",
        )
        private val VOICES = voicesMap.keys.toTypedArray()
        private val VOICES_VALUES = voicesMap.values.toTypedArray()

        private val playersMap = mapOf(
            "Sendvid" to "sendvid",
            "Sibnet" to "sibnet",
            "VK" to "vk",
            "VidMoly" to "vidmoly",
        )
        private val PLAYERS = playersMap.keys.toTypedArray()
        private val PLAYERS_VALUES = playersMap.values.toTypedArray()

        private const val PREF_VOICES_KEY = "voices_preference"
        private const val PREF_VOICES_DEFAULT = "vostfr"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_PLAYER_KEY = "player_preference"
        private const val PREF_PLAYER_DEFAULT = "sibnet"
    }
}
