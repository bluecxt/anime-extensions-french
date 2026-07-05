package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.util.Log
import androidx.preference.PreferenceScreen
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.network.GET
import fr.bluecxt.core.network.await
import fr.bluecxt.core.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelMap
import fr.bluecxt.core.ANIMESAMA_LOG
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.Source
import fr.bluecxt.core.TmdbMetadata
import fr.bluecxt.core.fetchTmdbMetadata
import fr.bluecxt.core.filterSmartMetadata
import fr.bluecxt.core.safeRelativePath
import fr.bluecxt.core.withDefaultHeaders
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class AnimeSama :
    Source(),
    CommonPreferences {

    override val name = "Anime-Sama"

    override val defaultBaseUrl = "https://anime-sama.to"
    override val supportedServers = listOf("Sibnet", "Sendvid", "Vidmoly", "Embed4me", "Minochinos")
    override val supportedVoices = arrayOf("VOSTFR", "VF", "VA")

    override val lang = "fr"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)
        .add("Referer", "$baseUrl/")

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
                val rawTitle = a.select(".card-title").text().trim()
                title = rawTitle
                    .substringBefore(" - Saison")
                    .substringBefore(" Saison")
                    .substringBefore(" - Season")
                    .substringBefore(" Season")
                    .substringBefore(" - Film")
                    .substringBefore(" Film")
                    .substringBefore(" - Movie")
                    .substringBefore(" Movie")
                    .substringBefore(" - OAV")
                    .substringBefore(" OAV")
                    .substringBefore(" - Partie")
                    .substringBefore(" Partie")
                    .substringBefore(" - Part")
                    .substringBefore(" Part")
                    .substringBefore(" - Kai")
                    .substringBefore(" Kai")
                    .substringBefore(" - Director's Cut")
                    .substringBefore(" Director's Cut")
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
            if (it.select(".info-row:has(.info-label:contains(Types)) .info-value").text().trim().equals("Scans", true)) return@mapNotNull null

            val cardLink = it.selectFirst("a") ?: return@mapNotNull null
            SAnime.create().apply {
                title = cardLink.select(".card-title").text().trim()
                thumbnail_url = cardLink.select("img").attr("abs:src")
                url = cardLink.safeRelativePath()
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
        val response = client.newCall(GET("$baseUrl$animeUrlPath")).awaitSuccess()
        val doc = response.asJsoup()

        val rawUrl = "$baseUrl$animeUrlPath".toHttpUrl()
        val pathSegments = rawUrl.pathSegments

        // Hub is always /catalogue/anime-name/ (segments 0 and 1)
        val hubUrl = if (pathSegments.size > 2) {
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
                client.newCall(GET(hubUrl)).awaitSuccess().asJsoup()
            } catch (_: Exception) {
                doc
            }
        }

        val seriesTitle = hubDoc.selectFirst("div.my-2 > h1")?.text() ?: ""
        val absoluteFullTitle = doc.selectFirst("div.my-2 > h1")?.text() ?: anime.title

        // Extract title from URL fragment if available (set in fetchAnimeSeasons)
        val originalTitleFromUrl = anime.url.substringAfter("|", "").takeIf { it.isNotBlank() }
        val titleToSearch = originalTitleFromUrl ?: absoluteFullTitle

        // Fallback: extract from URL slug (often more accurate than the displayed H1)
        val urlSlugTitle = pathSegments.getOrNull(1)?.replace("-", " ")?.trim()

        val tmdbMetadata = when {
            // Extract season number from URL fragment if available
            anime.url.contains("#s") -> {
                val sNumStr = anime.url.substringAfter("#s", "").substringBefore("|")
                val sNum = sNumStr.toDoubleOrNull()
                // -2.0 is a bypass code, we need real number for TMDB
                val meta = if (sNum != null && sNum > 0) fetchTmdbMetadata(seriesTitle, sNum.toInt(), "tv") else fetchSmartTmdbMetadata(titleToSearch)
                meta ?: urlSlugTitle?.let { fetchSmartTmdbMetadata(it) }
            }

            else -> fetchSmartTmdbMetadata(titleToSearch) ?: urlSlugTitle?.let { fetchSmartTmdbMetadata(it) }
        }

        // REPO_RULES: Optimize long titles for grid view
        var isSubTitleOnly = false
        val displayTitle = if (absoluteFullTitle.length > 40 && absoluteFullTitle.contains(" ") && seriesTitle.isNotEmpty()) {
            val suffix = absoluteFullTitle.substringAfter(seriesTitle).trim()
            val isStandardSeason = suffix.matches(Regex("""(?i)(?:-?\s*)?(?:Saison|Season|Partie|Part|\d+).*"""))
            if (suffix.isNotEmpty() && suffix != absoluteFullTitle && !isStandardSeason) {
                isSubTitleOnly = true
                suffix
            } else {
                absoluteFullTitle
            }
        } else {
            // If the title is already a subtitle (from fetchAnimeSeasons), keep it
            if (originalTitleFromUrl != null && !absoluteFullTitle.contains(originalTitleFromUrl)) {
                isSubTitleOnly = true
                anime.title
            } else {
                absoluteFullTitle
            }
        }

        // Ensure series title is present if not already there
        val titleWithSeries = if (seriesTitle.isNotEmpty() && !displayTitle.contains(seriesTitle, true)) {
            "$seriesTitle $displayTitle"
        } else {
            displayTitle
        }

        // REPO_RULES: "Saison X" -> "X" for consistency and space (Saison 1 is removed)
        val finalTitle = titleWithSeries
            .replace(Regex("""(?i)\s*-\s*(?:Saison|Season)\s*1(?!\d)"""), "")
            .replace(Regex("""(?i)\s*(?:Saison|Season)\s*1(?!\d)"""), "")
            .replace(Regex("""(?i)\s*-\s*(?:Saison|Season)\s*(\d+)"""), " $1")
            .replace(Regex("""(?i)\s*(?:Saison|Season)\s*(\d+)"""), " $1")
            .replace(Regex("""(?i)Partie\s*(\d+)"""), "Part $1")
            .trim()

        anime.title = if (isSubTitleOnly) displayTitle else finalTitle

        anime.description = tmdbMetadata?.summary ?: hubDoc.select("h2:contains(synopsis) + p").text()

        tmdbMetadata?.releaseDate?.let { date ->
            anime.description = "Date de sortie : $date\n\n${anime.description ?: ""}"
        }

        // Always put raw full title at the very top if the display title was optimized to a subtitle
        if (isSubTitleOnly) {
            anime.description = "$titleToSearch\n\n${anime.description ?: ""}"
        }

        anime.author = tmdbMetadata?.author
        anime.artist = tmdbMetadata?.artist
        anime.status = tmdbMetadata?.status ?: if (animeUrlPath.contains("/film", ignoreCase = true)) SAnime.COMPLETED else SAnime.UNKNOWN
        anime.genre = tmdbMetadata?.genre ?: hubDoc.select("h2:contains(genres) + a").text().replace(" - ", ", ")

        // Priority: Sub-page TMDB Poster > Hub TMDB Poster > Hub HTML Cover
        anime.thumbnail_url = tmdbMetadata?.posterUrl ?: hubDoc.getElementById("coverOeuvre")?.attr("abs:src")

        val scripts = doc.select("script").toString()
        val uncommented = commentRegex.replace(scripts, "")

        // Count distinct base stems to avoid duplicates (VOSTFR vs VF panels)
        val matches = seasonRegex.findAll(uncommented).toList()

        val distinctSeasons = matches
            .filter {
                val stem = it.groupValues[2].trim().removeSuffix("/")
                // Filter out pointers to the hub itself (must have at least one / to be a sub-season)
                // A sub-season stem should NOT just be a language code (vostfr, vf, etc.)
                val isLangOnly = stem.equals("vostfr", true) || stem.equals("vf", true) ||
                    stem.equals("vf1", true) || stem.equals("vf2", true) ||
                    stem.equals("va", true) || stem.equals("vcn", true) ||
                    stem.equals("vj", true) || stem.equals("vkr", true) ||
                    stem.equals("vqc", true)

                stem.contains("/") && !isLangOnly
            }
            .map {
                it.groupValues[2].trim().removeSuffix("/")
                    .substringBeforeLast("/", it.groupValues[2].trim().removeSuffix("/"))
            }
            .distinct()
            .toList()

        // Hub is /catalogue/anime-name/ or /catalogue/anime-name/vostfr/
        // Path segments for hub are ["catalogue", "anime-name"] or ["catalogue", "anime-name", "lang"]
        val isHub = pathSegments.size <= 3
        anime.fetch_type = if (isHub && distinctSeasons.size > 1) FetchType.Seasons else FetchType.Episodes

        // Set season number for proper app state
        if (!isHub) {
            // AniZen bypass: Force -2.0 to totally disable auto-labeling and use our provided title
            anime.season_number = HUB_SEASON_NUMBER
        }

        return anime
    }

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val res = client.newCall(GET("$baseUrl${anime.url}")).awaitSuccess()
        return fetchAnimeSeasons(res).onEach { it.fetch_type = FetchType.Episodes }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrlPath = anime.url.substringBefore("#").removeSuffix("/")
        val movie = anime.url.substringAfter("#", "").takeIf { it.isNotBlank() && !it.startsWith("s") }?.toIntOrNull()

        var currentUrlPath = animeUrlPath
        val rawUrl = "$baseUrl$animeUrlPath".toHttpUrl()
        val isHub = rawUrl.pathSegments.size <= 2
        Log.d(ANIMESAMA_LOG, "épisode url = $rawUrl")

        // If it's a hub (no season/film/oav in URL) but we are in Episode mode,
        // it means there's only one season, or we want to show the first one.
        if (isHub) {
            val response = client.newCall(GET("$baseUrl$animeUrlPath/")).await()
            if (!response.isSuccessful) return emptyList()
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

        val langValues = listOf("vostfr", "vf", "va", "vcn", "vj", "vkr", "vqc")
        val players = langValues.parallelMap {
            fetchPlayers("$baseUrl$seasonRootPath/$it")
        }
        Log.v(ANIMESAMA_LOG, "player list = $players")
        val episodes = playersToEpisodes(players, anime, "$seasonRootPath/", langValues)
        return if (movie == null) episodes.asReversed() else listOf(episodes[movie])
    }

    private suspend fun fetchSmartTmdbMetadata(title: String): TmdbMetadata? {
        if (title.isBlank()) return null

        val seasonRegex = Regex("""(?i)(.*?)\s+(?:Saison|Season)\s*(\d+)""")
        val oavRegex = Regex("""(?i)(.*?)\s+(?:OAV|OVA|Special|Director's Cut|\bKai(?:\s+saison\s+\d+)?)$""")
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
                // REPO_RULES: Kai and Director's cut should use Season 1 metadata
                val isTrueSpecial = !title.contains(Regex("""(?i)\bKai(?:\s+saison\s+\d+)?$""")) && !title.contains("Director's Cut", true)
                val seasonToFetch = if (isTrueSpecial) 0 else 1
                val meta = fetchTmdbMetadata(cleanTitle, seasonToFetch, "tv")
                meta?.let { filterSmartMetadata(it, isSpecialSeason = isTrueSpecial) }
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

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val playerUrls = json.decodeFromString<List<List<String>>>(episode.url)
        val hosters = mutableListOf<Hoster>()
        val langValues = listOf("VOSTFR", "VF", "VA", "VCN", "VJ", "VKR", "VQC")

        playerUrls.forEachIndexed { i, it ->
            if (it.isEmpty()) return@forEachIndexed
            val lang = langValues[i]
            // Internal data: JSON array of URLs for this language | lang tag
            hosters.add(Hoster(hosterName = lang, internalData = json.encodeToString(it) + "|" + lang))
        }
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|")
        val urls = json.decodeFromString<List<String>>(data[0])
        val lang = data[1]

        return urls.parallelMap { playerUrl ->
            Log.v(ANIMESAMA_LOG, "player url = $playerUrl")
            extractVideos(playerUrl, lang, supportedServers)
        }.flatten()
    }

    // ============================ Utils =============================
    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val voices = preferences.getString(CommonPreferences.PREF_VOICES_KEY, "VOSTFR")!!.uppercase()
        val player = preferences.getString(CommonPreferences.PREF_SERVER_KEY, "Sibnet")!!

        return this.sortedWith(
            compareByDescending<Hoster> { it.hosterName.equals(voices, true) }
                .thenByDescending { it.hosterName.contains(player, true) },
        )
    }

    private val commentRegex by lazy { Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL) }
    private val seasonRegex by lazy { Regex("""(?i)panneauAnime\s*\(\s*"(.*)"\s*,\s*"(.*)"\s*\)""") }

    private suspend fun fetchAnimeSeasons(response: Response): List<SAnime> {
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
        Log.d(ANIMESAMA_LOG, "animeUrl = $animeUrl")

        // Clean animeName to avoid "Anime Saison 1 Saison 1"
        val rawTitle = (animeDoc.selectFirst("div.my-2 > h1")?.text() ?: "").trim()
        Log.d(ANIMESAMA_LOG, "rawtitle = $rawTitle")
        val attributesRegex = Regex("""(?i)\s*(?:-\s*)?(?:Saison|Season|Film|Movie|OAV|OVA|Partie|Part)\b.*""")
        val intermediateTitle = rawTitle.replace(attributesRegex, "").trim()

        val editionRegex = Regex("""(?i)\s*(?:-\s*)?(?:\bKai|\bDirector's Cut)$""")
        val animeName = intermediateTitle.replace(editionRegex, "").trim()

        android.util.Log.d(ANIMESAMA_LOG, "fetchAnimeSeasons: Extracted animeName: '$animeName'")

        val scripts = animeDoc.select("script").toString()
        val uncommented = commentRegex.replace(scripts, "")

        // REPO_RULES: Group by base stem to merge VOSTFR and VF panels
        val allMatches = seasonRegex.findAll(uncommented).toList()
            .filter {
                val stem = it.groupValues[2].trim().removeSuffix("/")
                // Filter out pointers to the hub itself (must have at least one / to be a sub-season)
                // A sub-season stem should NOT just be a language code (vostfr, vf, etc.)
                val isLangOnly = stem.equals("vostfr", true) || stem.equals("vf", true) ||
                    stem.equals("vf1", true) || stem.equals("vf2", true) ||
                    stem.equals("va", true) || stem.equals("vcn", true) ||
                    stem.equals("vj", true) || stem.equals("vkr", true) ||
                    stem.equals("vqc", true)

                val isSubSeason = stem.contains("/") && !isLangOnly
                if (!isSubSeason) {
                    android.util.Log.d(ANIMESAMA_LOG, "fetchAnimeSeasons: Filtering out Hub self-ref: $stem")
                }
                isSubSeason
            }
            .distinctBy {
                val stem = it.groupValues[2].trim().removeSuffix("/")
                    .substringBeforeLast("/", it.groupValues[2].trim().removeSuffix("/"))
                android.util.Log.d(ANIMESAMA_LOG, "fetchAnimeSeasons: Match - Name: ${it.groupValues[1]}, Stem: ${it.groupValues[2]}, BaseStem: $stem")
                stem
            }

        val animes = allMatches.flatMapIndexed { animeIndex, seasonMatch ->
            val (seasonName, seasonStem) = seasonMatch.destructured
            val seasonNumMatch = Regex("""\d+""").find(seasonName)
            val sNum = when {
                seasonStem.contains("film", true) || seasonName.contains("film", true) -> 0.0

                // Use 0.0 for movies, OAVs and PARTS to group them as specials or avoid app labels
                seasonStem.contains("oav", true) || seasonName.contains("oav", true) ||
                    seasonName.contains("special", true) || seasonName.contains("Partie", true) ||
                    seasonName.contains("Part", true) -> 0.0

                else -> {
                    val baseNum = seasonNumMatch?.value?.toDoubleOrNull() ?: 1.0
                    when {
                        seasonName.contains(Regex("""(?i)\bKai(?:\s+saison\s+\d+)?$""")) || seasonName.contains("Director's Cut", true) -> -1.0
                        else -> baseNum
                    }
                }
            }
            val defaultStatus = if (seasonStem.contains("film", true)) SAnime.COMPLETED else SAnime.UNKNOWN

            // Ensure animeName is not already in seasonName to avoid "Re:Zero Re:Zero"
            val fullTitle = if (seasonName.contains(animeName, true)) {
                seasonName
            } else {
                "$animeName $seasonName"
            }

            listOf(Quadruple(fullTitle, "$animeUrl/$seasonStem", sNum, defaultStatus))
        }
        return animes.parallelMap { (fullTitle, url, _, defStatus) ->
            val tmdbMetadata = fetchSmartTmdbMetadata(fullTitle)

            var isSubTitleOnly = false
            val displayTitle = if (fullTitle.length > 40 && fullTitle.contains(" ")) {
                val suffix = fullTitle.substringAfter(animeName).trim()
                val isStandardSeason = suffix.matches(Regex("""(?i)(?:-?\s*)?(?:Saison|Season|Partie|Part|\d+).*"""))
                if (suffix.isNotEmpty() && suffix != fullTitle && !isStandardSeason) {
                    isSubTitleOnly = true
                    suffix
                } else {
                    fullTitle
                }
            } else {
                fullTitle
            }

            // REPO_RULES: "Saison X" -> "X" for consistency and space (Saison 1 is removed)
            val finalTitle = displayTitle
                .replace(Regex("""(?i)\s*-\s*(?:Saison|Season)\s*1(?!\d)"""), "")
                .replace(Regex("""(?i)\s*(?:Saison|Season)\s*1(?!\d)"""), "")
                .replace(Regex("""(?i)\s*-\s*(?:Saison|Season)\s*(\d+)"""), " $1")
                .replace(Regex("""(?i)\s*(?:saison|season)\s*(\d+)"""), " $1")
                .replace(Regex("""(?i)Partie\s*(\d+)"""), "Part $1")
                .trim()

            android.util.Log.d(ANIMESAMA_LOG, "fetchAnimeSeasons: Season - Final Title: '$finalTitle', URL: '$url'")

            SAnime.create().apply {
                this.title = finalTitle
                thumbnail_url = tmdbMetadata?.posterUrl ?: animeDoc.getElementById("coverOeuvre")?.attr("src")
                description = tmdbMetadata?.summary ?: animeDoc.select("h2:contains(synopsis) + p").text()

                tmdbMetadata?.releaseDate?.let { date ->
                    description = "Date de sortie : $date\n\n${description ?: ""}"
                }

                // Always put raw full title at the very top if the display title was optimized to a subtitle
                if (isSubTitleOnly) {
                    description = "$fullTitle\n\n${description ?: ""}"
                }

                genre = tmdbMetadata?.genre ?: animeDoc.select("h2:contains(genres) + a").text().replace(" - ", ", ")
                author = tmdbMetadata?.author
                artist = tmdbMetadata?.artist
                status = tmdbMetadata?.status ?: defStatus
                this.url = "${url.toHttpUrl().encodedPath}#s-2.0|${fullTitle.replace("|", "")}"
                season_number = -2.0
                initialized = true
            }
        }
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
        langValues: List<String>,
    ): List<SEpisode> {
        val maxEpisodes = list.fold(0) { acc, it -> maxOf(acc, it.size) }

        // Extract metadata from URL fragment
        val sNumFromUrl = anime.url.substringAfter("#s", "").substringBefore("|").toDoubleOrNull()
        val originalTitleFromUrl = anime.url.substringAfter("|", "").takeIf { it.isNotBlank() }
        val effectiveTitle = originalTitleFromUrl ?: anime.title

        // REPO_RULES: Standard terminology for AniZen grouping
        val isSpecial = effectiveTitle.contains("Special", true) ||
            effectiveTitle.contains(Regex("""(?i)\bKai(\s+saison\s+\d+)?$""")) ||
            effectiveTitle.contains("Director's Cut", true)
        val isOav = effectiveTitle.contains("OAV", true) || effectiveTitle.contains("OVA", true) || isSpecial
        val isMovie = effectiveTitle.contains("FILM", true) || effectiveTitle.contains("MOVIE", true) || animeUrlPath.contains("film", true) || animeUrlPath.contains("movie", true)

        val sNumRegex = Regex("""(?i)(?:Saison|Season)\s*(\d+)""")
        val sNumMatch = sNumRegex.find(effectiveTitle)
        val sNum = when {
            isOav -> 0
            sNumFromUrl != null && sNumFromUrl > 0 -> sNumFromUrl.toInt()
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
                val rootPath = animeUrlPath.replace(Regex("/(saison|film|oav|special|hs|kai|partie|part).*"), "")
                val mainUrl = "$baseUrl$rootPath/"
                val mainDoc = client.newCall(GET(mainUrl)).awaitSuccess().use { it.body.string() }
                Log.d(ANIMESAMA_LOG, "url for maindoc = $mainUrl")
                val uncommented = commentRegex.replace(mainDoc, "")

                // Merge redundant panels in offset calculation too
                val allSeasons = seasonRegex.findAll(uncommented).toList()
                    .distinctBy {
                        it.groupValues[2].trim().removeSuffix("/")
                            .substringBeforeLast("/", it.groupValues[2].trim().removeSuffix("/"))
                    }

                val currentStem = animeUrlPath.removePrefix(rootPath).removePrefix("/").removeSuffix("/")
                val currentBaseStem = currentStem.substringBeforeLast("/", currentStem)

                // Parallelize episode counting for all seasons before the current one
                val seasonsToScan = allSeasons.takeWhile {
                    val (_, stem) = it.destructured
                    val cleanStem = stem.trim().removeSuffix("/")
                    val stemBase = cleanStem.substringBeforeLast("/", cleanStem)
                    stemBase != currentBaseStem
                }

                val seasonData = seasonsToScan.parallelMap { match ->
                    val (seasonName, stem) = match.destructured
                    val cleanStem = stem.trim().removeSuffix("/")
                    val count = countEpisodesInSeason("$rootPath/$cleanStem")
                    Quadruple(seasonName, cleanStem, count, null)
                }

                val seenTmdbSeasons = mutableSetOf<Int>()
                for (data in seasonData) {
                    val (seasonName, stem, count) = data
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
        val urlSlugTitle = animeUrlPath.split("/").filter { it.isNotBlank() }.getOrNull(1)?.replace("-", " ")?.trim()
        val tmdbMetadata = fetchSmartTmdbMetadata(effectiveTitle) ?: urlSlugTitle?.let { fetchSmartTmdbMetadata(it) }

        var tmdbEpCount = tmdbMetadata?.episodeSummaries?.size ?: 0
        val isSpecialVersion = effectiveTitle.contains(Regex("""(?i)\bKai(?:\s+saison\s+\d+)?$""")) || effectiveTitle.contains("Director's Cut", true)
        android.util.Log.d(ANIMESAMA_LOG, "playersToEpisodes: title='$effectiveTitle', slug='$urlSlugTitle', tmdbFound=${tmdbMetadata != null}, tmdbEpCount=$tmdbEpCount, isKai=$isSpecialVersion")

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
            isOav && isSpecial -> "[Special] "
            isOav -> "[OAV] "
            sNum > 1 || (sNum == 1 && tmdbEpCount > 0 && maxEpisodes > tmdbEpCount) -> "[S$sNum] "
            else -> ""
        }

        val movieNames = if (isMovie) {
            val urlsToTry = mutableListOf("$baseUrl$animeUrlPath")
            langValues.forEach { urlsToTry.add("$baseUrl$animeUrlPath$it/") }

            var names = emptyList<String>()
            for (url in urlsToTry) {
                try {
                    val response = client.newCall(GET(url, headers)).awaitSuccess()
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

        return (0 until maxEpisodes).parallelMap { episodeNumber ->
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
                val movieMeta = fetchTmdbMetadata(movieName, type = "movie")
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

                    tmdbName == null || isSpecialVersion -> "Episode $epNum"

                    tmdbName.contains(Regex("""(?i)Episode\s*$epNum""")) -> tmdbName

                    else -> "Episode $epNum - $tmdbName"
                }
                name = "$finalPrefix$baseName"
                url = json.encodeToString(players)
                episode_number = epNum.toFloat()
                scanlator = players.mapIndexedNotNull { i, it -> if (it.isNotEmpty()) langValues[i] else null }
                    .joinToString().uppercase()
                preview_url = if (isSpecialVersion) null else epPreview ?: tmdbMetadata?.posterUrl
                summary = if (isSpecialVersion) null else epSummary
            }
        }
    }

    private suspend fun countEpisodesInSeason(seasonPath: String): Int {
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

        val counts = jsPaths.parallelMap { p ->
            try {
                val docUrl = "$baseUrl$p/episodes.js"
                val js = client.newCall(GET(docUrl)).awaitSuccess().use {
                    if (!it.isSuccessful) null else it.body.string()
                } ?: return@parallelMap 0

                if (js.trim().startsWith("<")) return@parallelMap 0

                // Simple regex to find the size of the first array (eps1)
                val match = Regex("""eps1\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(js)
                val content = match?.groupValues?.get(1)?.trim() ?: return@parallelMap 0
                if (content.isEmpty() || content == "[]") return@parallelMap 0
                content.split(",").filter { it.isNotBlank() }.size
            } catch (_: Exception) {
                0
            }
        }

        return counts.firstOrNull { it > 0 } ?: 0
    }

    private suspend fun fetchPlayers(url: String): List<List<String>> {
        val cleanUrl = url.substringBefore("#")
        val docUrl = "$cleanUrl/episodes.js"
        val doc = client.newCall(GET(docUrl)).await().use {
            Log.v(ANIMESAMA_LOG, "fetch player finished ($docUrl) error code = ${it.code}")
            if (!it.isSuccessful) return emptyList()
            it.body.string()
        }

        // Safety check: if response is HTML, it's not a JS file
        if (doc.trim().startsWith("<")) {
            return emptyList()
        }

        val urls = QuickJs.create().use { qjs ->
            qjs.evaluate(doc)
            val res = qjs.evaluate("JSON.stringify(Array.from({length: 40}, (e, i) => this['eps' + (i + 1)]).filter(e => e !== undefined && e !== null))")
            json.decodeFromString<List<List<String>>>(res as String)
        }

        if (urls.isEmpty() || urls[0].isEmpty()) return emptyList()
        val maxEpisodes = urls.maxOfOrNull { it.size } ?: 0
        return List(maxEpisodes) { i -> urls.mapNotNull { it.getOrNull(i) }.distinct() }
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private val offsetCache = mutableMapOf<String, Pair<Int, Int>>()
    }
}
