package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
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
import eu.kanade.tachiyomi.util.parallelMapBlocking
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
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        )
        .add("Referer", "$baseUrl/")

    override val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = response.request.header("X-Page") ?: "1"
        return parseCatalogue(response.asJsoup(), page)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/catalogue/?page=$page", headers.newBuilder().add("X-Page", page.toString()).build())

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("#containerAjoutsAnimes > div").mapNotNull {
            val a = it.select("a")
            val animeUrl = a.attr("abs:href").toHttpUrl()

            // Expected path: /catalogue/anime-name/saisonX/lang/
            // Hub path should be: /catalogue/anime-name/
            val pathSegments = animeUrl.pathSegments
            if (pathSegments.size < 2) {
                return@mapNotNull null
            }

            val hubUrl = animeUrl.newBuilder().apply {
                // Keep only "catalogue" and "anime-name"
                (pathSegments.size - 1 downTo 2).forEach { i ->
                    removePathSegment(i)
                }
            }.build()

            SAnime.create().apply {
                // Title is in .card-title (h2), not h1
                val rawTitle = a.select(".card-title").text().trim()
                if (rawTitle.isBlank()) {
                }
                title = rawTitle
                    .substringBefore(" - Saison")
                    .substringBefore(" Saison")
                    .substringBefore(" - Film")
                    .substringBefore(" Film")
                    .substringBefore(" - OAV")
                    .substringBefore(" OAV")
                    .trim()
                thumbnail_url = a.select("img").attr("abs:src")
                url = hubUrl.encodedPath
            }
        }.distinctBy { it.url }

        return AnimesPage(animes, false)
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
        return GET(url.build(), headers.newBuilder().add("X-Page", page.toString()).build())
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val page = response.request.header("X-Page") ?: "1"
        return parseCatalogue(response.asJsoup(), page)
    }

    private fun parseCatalogue(document: org.jsoup.nodes.Document, page: String): AnimesPage {
        val animes = document.select("div.catalog-card").mapNotNull {
            val typeText = it.select(".info-row:has(.info-label:contains(Types)) .info-value").text()
            val types =
                typeText.split(",").map { t -> t.trim() }.filter { t -> t.isNotBlank() && !t.equals("Scans", true) }
            if (types.isEmpty()) return@mapNotNull null

            val a = it.selectFirst("a") ?: return@mapNotNull null
            SAnime.create().apply {
                title = a.select(".card-title").text().trim()
                thumbnail_url = a.select("img").attr("abs:src")
                url = a.attr("abs:href").toHttpUrl().encodedPath
            }
        }

        val lastPageText = document.select("#list_pagination a:last-child").text()
        val hasNextPage =
            lastPageText.isNotBlank() && lastPageText != page && (
                lastPageText.toIntOrNull()?.let { it > page.toInt() }
                    ?: lastPageText.contains(Regex("""(?i)Suivant|Next|»"""))
                )

        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeUrlPath = anime.url.substringBefore("#")
        val response = client.newCall(GET("$baseUrl$animeUrlPath")).execute()
        val doc = response.asJsoup()

        val isMoviePage = animeUrlPath.contains("/film", ignoreCase = true)
        val rawUrl = "$baseUrl$animeUrlPath".toHttpUrl()
        val pathSegments = rawUrl.pathSegments
        val isSubPage = pathSegments.size > 2

        // Extract season number from URL fragment if available
        val sNumFromUrl = anime.url.substringAfter("#s", "").toDoubleOrNull()

        // Hub is always /catalogue/anime-name/ (segments 0 and 1)
        val hubUrl = if (isSubPage) {
            rawUrl.newBuilder().apply {
                (pathSegments.size - 1 downTo 2).forEach { i -> removePathSegment(i) }
            }.build().toString().removeSuffix("/")
        } else {
            rawUrl.toString().removeSuffix("/")
        }

        val hubDoc = if (rawUrl.toString().removeSuffix("/") == hubUrl) {
            doc
        } else {
            try {
                client.newCall(GET(hubUrl)).execute().asJsoup()
            } catch (_: Exception) {
                doc
            }
        }

        val seriesTitle = hubDoc.getElementById("titreOeuvre")?.text() ?: ""
        val absoluteFullTitle = doc.getElementById("titreOeuvre")?.text() ?: anime.title

        // Extract title from URL fragment if available (set in fetchAnimeSeasons)
        val originalTitleFromUrl = anime.url.substringAfter("|", "").takeIf { it.isNotBlank() }
        val titleToSearch = originalTitleFromUrl ?: absoluteFullTitle

        val tmdbMetadata = when {
            sNumFromUrl != null -> fetchTmdbMetadata(seriesTitle, sNumFromUrl.toInt(), "tv")
            else -> fetchSmartTmdbMetadata(titleToSearch)
        }

        // REPO_RULES: Optimize long titles for grid view
        if (absoluteFullTitle.length > 40 && absoluteFullTitle.contains(" ") && seriesTitle.isNotEmpty()) {
            val suffix = absoluteFullTitle.substringAfter(seriesTitle).trim()
            val isStandardSeason = suffix.matches(Regex("""(?i)(?:-?\s*)?(?:Saison|Season|Partie|Part|\d+).*"""))
            if (suffix.isNotEmpty() && suffix != absoluteFullTitle && !isStandardSeason) {
                anime.title = suffix
            }
        }

        // REPO_RULES: "Saison X" -> "X" for consistency and space (Saison 1 is removed)
        anime.title = anime.title
            .replace(Regex("""(?i)\s*-\s*Saison\s*1(?!\d)"""), "")
            .replace(Regex("""(?i)\s*Saison\s*1(?!\d)"""), "")
            .replace(Regex("""(?i)\s*-\s*Saison\s*(\d+)"""), " $1")
            .replace(Regex("""(?i)\s*Saison\s*(\d+)"""), " $1")
            .replace(Regex("""(?i)Partie\s*(\d+)"""), "Part $1")
            .trim()

        anime.description = tmdbMetadata?.summary ?: hubDoc.select("h2:contains(synopsis) + p").text()

        tmdbMetadata?.releaseDate?.let { date ->
            anime.description = "Date de sortie : $date\n\n${anime.description ?: ""}"
        }

        // Always put raw full title at the very top if the display title was optimized
        if (anime.title != titleToSearch) {
            anime.description = "$titleToSearch\n\n${anime.description ?: ""}"
        }

        anime.author = tmdbMetadata?.author
        anime.artist = tmdbMetadata?.artist
        anime.status = tmdbMetadata?.status ?: if (isMoviePage) SAnime.COMPLETED else SAnime.UNKNOWN
        anime.genre = tmdbMetadata?.genre ?: hubDoc.select("h2:contains(genres) + a").text().replace(" - ", ", ")

        // Priority: Sub-page TMDB Poster > Hub TMDB Poster > Hub HTML Cover
        anime.thumbnail_url = tmdbMetadata?.posterUrl ?: hubDoc.getElementById("coverOeuvre")?.attr("abs:src")

        val scripts = doc.select("script").toString()
        val uncommented = commentRegex.replace(scripts, "")

        // Count distinct base stems to avoid duplicates (VOSTFR vs VF panels)
        val distinctSeasons = seasonRegex.findAll(uncommented)
            .map {
                it.groupValues[2].trim().removeSuffix("/")
                    .substringBeforeLast("/", it.groupValues[2].trim().removeSuffix("/"))
            }
            .distinct()
            .toList()

        val isHub = !anime.url.contains(Regex("/(saison|film|oav|special|hs|kai)", RegexOption.IGNORE_CASE))
        anime.fetch_type = if (isHub && distinctSeasons.size > 1) FetchType.Seasons else FetchType.Episodes

        // Set season number for proper app state
        if (!isHub) {
            val sNumRegex = Regex("""(?i)saison\s*(\d+)""")
            val movieRegex = Regex("""(?i)film|movie""")
            val oavRegex = Regex("""(?i)oav|special""")

            anime.season_number = when {
                movieRegex.containsMatchIn(anime.url) -> 0.0

                oavRegex.containsMatchIn(anime.url) -> 0.0

                sNumRegex.containsMatchIn(anime.url) -> {
                    val baseNum = sNumRegex.find(anime.url)!!.groupValues[1].toDoubleOrNull() ?: 1.0
                    when {
                        anime.title.contains("Kai", true) -> baseNum + 0.1
                        anime.title.contains("Director's Cut", true) -> baseNum + 0.2
                        else -> baseNum
                    }
                }

                else -> {
                    when {
                        anime.title.contains("Kai", true) -> 1.1
                        anime.title.contains("Director's Cut", true) -> 1.2
                        else -> 1.0
                    }
                }
            }
        }

        return anime
    }

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val res = client.newCall(GET("$baseUrl${anime.url}")).execute()
        return fetchAnimeSeasons(res).onEach { it.fetch_type = FetchType.Episodes }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrlPath = anime.url.substringBefore("#").removeSuffix("/")
        val movie = anime.url.substringAfter("#", "").takeIf { it.isNotBlank() && !it.startsWith("s") }?.toIntOrNull()

        var currentUrlPath = animeUrlPath
        val isHub = !animeUrlPath.contains(Regex("/(saison|film|oav|special|hs|kai)", RegexOption.IGNORE_CASE))

        // If it's a hub (no season/film/oav in URL) but we are in Episode mode,
        // it means there's only one season or we want to show the first one.
        if (isHub) {
            val response = client.newCall(GET("$baseUrl$animeUrlPath/")).execute()
            val doc = response.asJsoup()
            val scripts = doc.select("script").toString()
            val uncommented = commentRegex.replace(scripts, "")
            val seasons = seasonRegex.findAll(uncommented).toList()
                .distinctBy {
                    it.groupValues[2].trim().removeSuffix("/")
                        .substringBeforeLast("/", it.groupValues[2].trim().removeSuffix("/"))
                }

            if (seasons.isNotEmpty()) {
                currentUrlPath = "$animeUrlPath/${seasons.first().groupValues[2].trim().removeSuffix("/")}"
            }
        }

        // Anime-Sama paths usually end with /vostfr, /vf, etc.
        // We need the "Season Root" to aggregate all languages.
        val langSuffixes = listOf("vostfr", "vf", "vf1", "vf2", "va", "vcn", "vj", "vkr", "vqc")
        var seasonRootPath = currentUrlPath
        for (suffix in langSuffixes) {
            if (seasonRootPath.endsWith("/$suffix", ignoreCase = true)) {
                seasonRootPath = seasonRootPath.substringBeforeLast("/")
                break
            }
        }

        val players = VOICES_VALUES.map {
            val p = fetchPlayers("$baseUrl$seasonRootPath/$it")
            if (p.isNotEmpty()) {
            }
            p
        }
        val episodes = playersToEpisodes(players, anime, "$seasonRootPath/")
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
                fetchTmdbMetadata(cleanTitle, 1, "movie") ?: fetchTmdbMetadata(cleanTitle, 1, "tv")
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

    private val qualityCleanRegex = Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)(?:/s)?")
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
    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!.uppercase()
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!

        return this.sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains("($voices)", true) }
                .thenByDescending { it.hosterName.contains(player, true) },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!.uppercase()
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!

        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains("($voices)", true) }
                .thenByDescending { it.videoTitle.contains(player, true) }
                .thenByDescending { it.videoTitle.contains(quality) }
                .thenByDescending {
                    pQualityRegex.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    private fun fetchAnimeSeasons(animeUrl: String): List<SAnime> {
        val res = client.newCall(GET(animeUrl)).execute()
        return fetchAnimeSeasons(res)
    }

    private val commentRegex by lazy { Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL) }
    private val seasonRegex by lazy { Regex("""(?i)panneauAnime\s*\(\s*"(.*)"\s*,\s*"(.*)"\s*\)""") }

    private fun fetchAnimeSeasons(response: Response): List<SAnime> {
        val animeDoc = response.asJsoup()
        val rawUrl = animeDoc.baseUri().toHttpUrl()

        // Hub is always /catalogue/anime-name/ (segments 0 and 1)
        val pathSegments = rawUrl.pathSegments
        val hubUrl = if (pathSegments.size >= 2) {
            rawUrl.newBuilder().apply {
                (pathSegments.size - 1 downTo 2).forEach { i -> removePathSegment(i) }
            }.build().toString().removeSuffix("/")
        } else {
            rawUrl.toString().removeSuffix("/")
        }

        val animeUrl = hubUrl.toHttpUrl()

        // Clean animeName to avoid "Anime Saison 1 Saison 1"
        val animeName = (animeDoc.getElementById("titreOeuvre")?.text() ?: "")
            .substringBefore(" - Saison")
            .substringBefore(" Saison")
            .substringBefore(" - Film")
            .substringBefore(" Film")
            .substringBefore(" - OAV")
            .substringBefore(" OAV")
            .trim()

        val scripts = animeDoc.select("script").toString()
        val uncommented = commentRegex.replace(scripts, "")

        // REPO_RULES: Group by base stem to merge VOSTFR and VF panels
        val allMatches = seasonRegex.findAll(uncommented).toList()
            .distinctBy {
                it.groupValues[2].trim().removeSuffix("/")
                    .substringBeforeLast("/", it.groupValues[2].trim().removeSuffix("/"))
            }

        val animes = allMatches.flatMapIndexed { animeIndex, seasonMatch ->
            val (seasonName, seasonStem) = seasonMatch.destructured
            val seasonNumMatch = Regex("""\d+""").find(seasonName)
            val sNum = when {
                seasonStem.contains("film", true) || seasonName.contains("film", true) -> 0.0

                // Use 0.0 for movies and OAVs to group them at the start
                seasonStem.contains("oav", true) || seasonName.contains("oav", true) || seasonName.contains(
                    "special",
                    true,
                ) -> 0.0

                else -> {
                    val baseNum = seasonNumMatch?.value?.toDoubleOrNull() ?: 1.0
                    when {
                        seasonName.contains("Kai", true) -> baseNum + 0.1
                        seasonName.contains("Director's Cut", true) -> baseNum + 0.2
                        else -> baseNum
                    }
                }
            }
            val defaultStatus = if (seasonStem.contains("film", true)) SAnime.COMPLETED else SAnime.UNKNOWN

            listOf(Quadruple("$animeName $seasonName", "$animeUrl/$seasonStem", sNum, defaultStatus))
        }

        return animes.map { (fullTitle, url, sNum, defStatus) ->
            val tmdbMetadata = kotlinx.coroutines.runBlocking { fetchSmartTmdbMetadata(fullTitle) }

            val displayTitle = if (fullTitle.length > 40 && fullTitle.contains(" ")) {
                val suffix = fullTitle.substringAfter(animeName).trim()
                val isStandardSeason = suffix.matches(Regex("""(?i)(?:-?\s*)?(?:Saison|Season|Partie|Part|\d+).*"""))
                if (suffix.isNotEmpty() && suffix != fullTitle && !isStandardSeason) {
                    suffix
                } else {
                    fullTitle
                }
            } else {
                fullTitle
            }

            // REPO_RULES: "Saison X" -> "X" for consistency and space (Saison 1 is removed)
            val finalTitle = displayTitle
                .replace(Regex("""(?i)\s*-\s*Saison\s*1(?!\d)"""), "")
                .replace(Regex("""(?i)\s*Saison\s*1(?!\d)"""), "")
                .replace(Regex("""(?i)\s*-\s*Saison\s*(\d+)"""), " $1")
                .replace(Regex("""(?i)\s*Saison\s*(\d+)"""), " $1")
                .replace(Regex("""(?i)Partie\s*(\d+)"""), "Part $1")
                .trim()

            SAnime.create().apply {
                this.title = finalTitle
                thumbnail_url = tmdbMetadata?.posterUrl ?: animeDoc.getElementById("coverOeuvre")?.attr("src")
                description = tmdbMetadata?.summary ?: animeDoc.select("h2:contains(synopsis) + p").text()

                tmdbMetadata?.releaseDate?.let { date ->
                    description = "Date de sortie : $date\n\n${description ?: ""}"
                }

                // Always put raw full title at the very top
                description = "$fullTitle\n\n${description ?: ""}"

                genre = tmdbMetadata?.genre ?: animeDoc.select("h2:contains(genres) + a").text().replace(" - ", ", ")
                author = tmdbMetadata?.author
                artist = tmdbMetadata?.artist
                status = tmdbMetadata?.status ?: defStatus
                this.url = "${url.toHttpUrl().encodedPath}#s$sNum|${fullTitle.replace("|", "")}"
                season_number = sNum
                initialized = true
            }
        }.toList()
    }

    private data class Quadruple<out A, out B, out C, out D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )

    private suspend fun playersToEpisodes(
        list: List<List<List<String>>>,
        anime: SAnime,
        animeUrlPath: String,
    ): List<SEpisode> {
        val maxEpisodes = list.fold(0) { acc, it -> maxOf(acc, it.size) }

        // Extract metadata from URL fragment
        val sNumFromUrl = anime.url.substringAfter("#s", "").substringBefore("|").toDoubleOrNull()
        val originalTitleFromUrl = anime.url.substringAfter("|", "").takeIf { it.isNotBlank() }
        val effectiveTitle = originalTitleFromUrl ?: anime.title

        // REPO_RULES: Standard terminology for AniZen grouping
        val isOav = effectiveTitle.contains("OAV", true) || effectiveTitle.contains("OVA", true) || effectiveTitle.contains("Special", true)
        val isMovie = effectiveTitle.contains("FILM", true) || effectiveTitle.contains("MOVIE", true) || animeUrlPath.contains("film", true) || animeUrlPath.contains("movie", true)

        val sNumRegex = Regex("""(?i)(?:Saison|Season)\s*(\d+)""")
        val sNumMatch = sNumRegex.find(effectiveTitle)
        val sNum = when {
            isOav -> 0
            sNumFromUrl != null -> sNumFromUrl.toInt()
            sNumMatch != null -> sNumMatch.groupValues[1].toInt()
            else -> 1
        }

        // Base title for TMDB search and display
        val baseTitle = effectiveTitle
            .replace(sNumRegex, "")
            .replace(Regex("(?i)OAV|OVA|Special|FILM|MOVIE"), "")
            .replace(Regex("\\s+-\\s*$"), "")
            .trim()

        // Calculate offsets by scanning the main anime page
        var siteOffset = 0
        var oavOffset = 0
        if (offsetCache.containsKey(animeUrlPath)) {
            val cached = offsetCache[animeUrlPath]!!
            siteOffset = cached.first
            oavOffset = cached.second
        } else {
            try {
                val rootPath = animeUrlPath.replace(Regex("/(saison|film|oav|special|hs|kai).*"), "")
                val mainUrl = "$baseUrl$rootPath/"
                val mainDoc = client.newCall(GET(mainUrl)).execute().use { it.body.string() }
                val uncommented = commentRegex.replace(mainDoc, "")

                // Merge redundant panels in offset calculation too
                val allSeasons = seasonRegex.findAll(uncommented).toList()
                    .distinctBy {
                        it.groupValues[2].trim().removeSuffix("/")
                            .substringBeforeLast("/", it.groupValues[2].trim().removeSuffix("/"))
                    }

                val currentStem = animeUrlPath.removePrefix(rootPath).removePrefix("/").removeSuffix("/")
                val currentBaseStem = currentStem.substringBeforeLast("/", currentStem)

                val seenTmdbSeasons = mutableSetOf<Int>()
                for (match in allSeasons) {
                    val (seasonName, stem) = match.destructured
                    val cleanStem = stem.trim().removeSuffix("/")
                    val stemBase = cleanStem.substringBeforeLast("/", cleanStem)

                    if (stemBase == currentBaseStem) break

                    val count = countEpisodesInSeason("$rootPath/$cleanStem")
                    if (count > 0) {
                        val seasonNumMatch = Regex("""\d+""").find(seasonName)
                        val isPrevOav = seasonName.contains("OAV", true) || stem.contains(
                            "oav",
                            true,
                        ) || seasonName.contains("Film", true) || stem.contains(
                            "film",
                            true,
                        ) || seasonName.contains("Special", true)
                        if (!isPrevOav && seasonNumMatch != null) {
                            val sN = seasonNumMatch.value.toInt()
                            if (seenTmdbSeasons.contains(sN)) continue
                            seenTmdbSeasons.add(sN)
                            val tmdbMeta = fetchTmdbMetadata(baseTitle, sN, "tv")
                            val tmdbCount = tmdbMeta?.episodeSummaries?.size ?: 0
                            if (tmdbCount > 0) {
                                siteOffset += minOf(count, tmdbCount)
                                if (count > tmdbCount) {
                                    oavOffset += (count - tmdbCount)
                                }
                            } else {
                                siteOffset += count
                            }
                        } else {
                            oavOffset += count
                        }
                    }
                }
                offsetCache[animeUrlPath] = siteOffset to oavOffset
            } catch (_: Exception) {
            }
        }

        // Fetch primary metadata
        val tmdbMetadata = fetchSmartTmdbMetadata(effectiveTitle)
        var tmdbEpCount = tmdbMetadata?.episodeSummaries?.size ?: 0

        val tmdbS1Metadata = if (sNum > 1 && !isOav && !isMovie && siteOffset > 0) {
            fetchTmdbMetadata(baseTitle, 1)
        } else {
            null
        }

        if (sNum > 1 && !isOav && !isMovie && siteOffset > 0 && tmdbEpCount == 0) {
            val s1Count = tmdbS1Metadata?.episodeSummaries?.size ?: 0
            if (s1Count > siteOffset) {
                tmdbEpCount = s1Count - siteOffset
            }
        }

        // REPO_RULES prefixes
        val sPrefix = when {
            isMovie -> "[Movie] "
            isOav -> "[OAV] "
            sNum > 1 || (sNum == 1 && maxEpisodes > tmdbEpCount) -> "[S$sNum] "
            else -> ""
        }

        val isSpecialVersion = effectiveTitle.contains("Director's Cut", true) || effectiveTitle.contains("Kai", true)

        val movieNames = if (isMovie) {
            val urlsToTry = mutableListOf("$baseUrl$animeUrlPath")
            VOICES_VALUES.forEach { urlsToTry.add("$baseUrl$animeUrlPath$it/") }

            var names = emptyList<String>()
            for (url in urlsToTry) {
                try {
                    val response = client.newCall(GET(url, headers)).execute()
                    if (!response.isSuccessful) continue
                    val doc = response.body.string()
                    val regex = Regex("""newSPF\s*\(\s*['"](.*?)['"]\s*\)""")
                    val matches = regex.findAll(doc).map { it.groupValues[1] }.toList()
                    if (matches.isNotEmpty()) {
                        names = matches
                        break
                    }
                } catch (_: Exception) {
                }
            }
            names
        } else {
            emptyList()
        }

        // Handle overflow metadata (S0)
        val s0Metadata = if (sNum > 0 && maxEpisodes > tmdbEpCount) {
            fetchTmdbMetadata(baseTitle, 0, "tv")?.let { filterSmartMetadata(it, isSpecialSeason = true) }
        } else {
            null
        }

        return List(maxEpisodes) { episodeNumber ->
            val epNum = episodeNumber + 1
            val players = list.map { it.getOrElse(episodeNumber) { emptyList() } }

            val movieName = movieNames.getOrNull(episodeNumber)
            var epMeta = when {
                isMovie -> if (maxEpisodes == 1) tmdbMetadata?.episodeSummaries?.get(1) else null
                isOav -> tmdbMetadata?.episodeSummaries?.get(epNum + oavOffset)
                else -> tmdbMetadata?.episodeSummaries?.get(epNum)
            }

            var epSummary = epMeta?.third
            var epPreview = epMeta?.second

            if (isMovie && movieName != null && maxEpisodes > 1) {
                val movieMeta = kotlinx.coroutines.runBlocking { fetchTmdbMetadata(movieName, type = "movie") }
                epSummary = movieMeta?.summary
                epPreview = movieMeta?.posterUrl
            }

            var finalPrefix = sPrefix

            // 2. Try Absolute Mapping (Reverse Overflow)
            if (!isOav && !isMovie && sNum > 1 && siteOffset > 0) {
                val absoluteMeta = tmdbS1Metadata?.episodeSummaries?.get(epNum + siteOffset)
                if (absoluteMeta != null) {
                    epMeta = absoluteMeta
                    epSummary = epMeta.third
                    epPreview = epMeta.second
                }
            }

            // 3. Overflow to OAVs mapping
            if (epMeta == null && !isOav && !isMovie && sNum > 0 && epNum > tmdbEpCount && s0Metadata != null) {
                val s0EpNum = (epNum - tmdbEpCount) + oavOffset
                val s0Meta = s0Metadata.episodeSummaries[s0EpNum]
                if (s0Meta != null) {
                    epMeta = s0Meta
                    epSummary = epMeta.third
                    epPreview = epMeta.second
                    finalPrefix = "[OAV] "
                }
            }

            SEpisode.create().apply {
                val tmdbName = epMeta?.first
                // REPO_RULES: avoid "Episode 1 - Episode 1"
                val baseName = when {
                    isMovie -> {
                        when {
                            movieName != null -> if (maxEpisodes == 1) movieName else "Film $epNum - $movieName"

                            maxEpisodes == 1 -> tmdbName ?: effectiveTitle.replace("[Movie]", "").replace("Films", "")
                                .trim()

                            else -> "Film $epNum"
                        }
                    }

                    tmdbName == null -> "Episode $epNum"

                    tmdbName.contains(Regex("""(?i)Episode\s*$epNum""")) -> tmdbName

                    else -> "Episode $epNum - $tmdbName"
                }
                name = "$finalPrefix$baseName"
                url = json.encodeToString(players)
                episode_number = epNum.toFloat()
                scanlator = players.mapIndexedNotNull { i, it -> if (it.isNotEmpty()) VOICES_VALUES[i] else null }
                    .joinToString().uppercase()
                preview_url = if (isSpecialVersion) null else epPreview ?: tmdbMetadata?.posterUrl
                summary = if (isSpecialVersion) null else epSummary
            }
        }
    }

    private fun countEpisodesInSeason(seasonPath: String): Int {
        val cleanSeasonPath = seasonPath.substringBefore("#")
        val langSuffixes = listOf("/vostfr", "/vf", "/vf1", "/vf2", "/va")
        var basePath = cleanSeasonPath.trim().removeSuffix("/")
        for (suffix in langSuffixes) {
            if (basePath.endsWith(suffix)) {
                basePath = basePath.removeSuffix(suffix)
                break
            }
        }

        val jsPaths = mutableListOf(cleanSeasonPath.trim().removeSuffix("/"))
        for (suffix in langSuffixes) {
            val p = "$basePath$suffix"
            if (p != cleanSeasonPath.trim().removeSuffix("/")) jsPaths.add(p)
        }

        for (p in jsPaths) {
            try {
                val docUrl = "$baseUrl$p/episodes.js"
                val js = client.newCall(GET(docUrl)).execute().use {
                    if (!it.isSuccessful) null else it.body.string()
                } ?: continue

                if (js.trim().startsWith("<")) continue

                // Simple regex to find the size of the first array (eps1)
                val match = Regex("""eps1\s*=\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(js)
                val content = match?.groupValues?.get(1)?.trim() ?: continue
                if (content.isEmpty() || content == "[]") continue
                val count = content.split(",").filter { it.isNotBlank() }.size
                return count
            } catch (_: Exception) {
            }
        }
        return 0
    }

    private fun fetchPlayers(url: String): List<List<String>> {
        val cleanUrl = url.substringBefore("#")
        val docUrl = "$cleanUrl/episodes.js"
        val doc = client.newCall(GET(docUrl)).execute().use {
            if (!it.isSuccessful) return emptyList()
            it.body.string()
        }

        // Safety check: if response is HTML, it's not a JS file
        if (doc.trim().startsWith("<")) {
            return emptyList()
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

        private val offsetCache = mutableMapOf<String, Pair<Int, Int>>()

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
