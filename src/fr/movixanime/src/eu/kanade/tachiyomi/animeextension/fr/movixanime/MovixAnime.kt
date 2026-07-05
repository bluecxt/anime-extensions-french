package eu.kanade.tachiyomi.animeextension.fr.movixanime

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.movixanime.dto.AnimeItem
import eu.kanade.tachiyomi.animeextension.fr.movixanime.dto.TmdbDiscoverResponse
import eu.kanade.tachiyomi.animeextension.fr.movixanime.dto.Top10Response
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.network.GET
import fr.bluecxt.core.network.awaitSuccess
import eu.kanade.tachiyomi.util.parallelMap
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.MOVIXANIME_LOG
import fr.bluecxt.core.Source
import fr.bluecxt.core.fetchTmdbMetadata
import fr.bluecxt.core.filterSmartMetadata
import fr.bluecxt.core.withDefaultHeaders
import keiyoushi.core.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class MovixAnime :
    Source(),
    CommonPreferences {

    override val name = "Movix Anime"

    override val defaultBaseUrl = "https://movix.chat"

    override val supportedServers = listOf(
        "Sendvid",
        "Sibnet",
        "Vidmoly",
        "Filemoon",
        "Dood",
        "Streamtape",
        "Vidoza",
        "Voe",
        "Minochinos",
        "Embed4me",
    )

    override val defaultServer = "Vidmoly"

    override val supportedVoices = arrayOf("VOSTFR", "VF", "VA", "VCN", "VJ", "VKR", "VQC")

    override val defaultVoice = "VOSTFR"

    override val supportedQualities = arrayOf("1080", "720", "480", "360")

    override val baseUrlSummary: String
        get() = "Laissez vide pour trouver automatiquement le domaine actif via movix.online. Actuel: $baseUrl"

    private var dynamicBaseUrl: String? = null

    override val baseUrl: String
        get() {
            if (dynamicBaseUrl != null) return dynamicBaseUrl!!

            if (!currentBaseUrl.isNullOrEmpty() && currentBaseUrl != "https://movix.online") {
                dynamicBaseUrl = currentBaseUrl
                return dynamicBaseUrl!!
            }

            // If empty or explicitly set to the status page, fetch the real one
            return fetchAndSaveRealUrl()
        }

    private fun fetchAndSaveRealUrl(): String {
        try {
            val response = client.newCall(GET("https://movix.online/")).execute()
            val html = response.body.string()
            val activeDomainRegex = Regex("""La seule adresse active de Movix est <a href="(https://[^"]+)"""")
            val match = activeDomainRegex.find(html)

            if (match != null) {
                val newDomain = match.groupValues[1].removeSuffix("/")
                preferences.edit().putString(CommonPreferences.PREF_URL_KEY, newDomain).apply()
                dynamicBaseUrl = newDomain
                return newDomain
            }
        } catch (e: Exception) {
            // Ignore and fallback
        }
        return defaultBaseUrl
    }

    private val domain: String
        get() = baseUrl.toHttpUrl().host

    private val apiUrl: String
        get() = "https://api.$domain"

    override val lang = "fr"

    override val supportsLatest = true

    override val json: Json by injectLazy()

    // Headers pour les pages du site (avec Origin/Referer)
    override fun headersBuilder() = super.headersBuilder()

    // Headers pour l'API — sans Origin pour éviter le CORS check côté serveur
    private val apiHeaders
        get() = headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("User-Agent", DEFAULT_USER_AGENT)
            .removeAll("Origin")
            .build()

    private val animeCache = mutableMapOf<String, AnimeItem>()

    companion object {
        const val PREFIX_SEARCH = "id:"
    }

    private fun getAnimeId(url: String): String = URLEncoder.encode(url, "UTF-8")

    // ============================ Popular ============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val response = client.newCall(GET("$apiUrl/api/top10/overview?type=anime", apiHeaders)).awaitSuccess()
        val data = json.decodeFromString<Top10Response>(response.body.string())

        // We must fetch the real URL from Movix API to ensure getAnimeDetails works
        val animes = data.top10.parallelMap { item ->
            try {
                val encodedQuery = URLEncoder.encode(item.title, "UTF-8").replace("+", "%20")
                Log.d(MOVIXANIME_LOG, "Popular: Fetching URL for ${item.title} -> $encodedQuery")
                val searchRes = client.newCall(GET("$apiUrl/anime/search/$encodedQuery?includeSeasons=false&includeEpisodes=false", apiHeaders)).awaitSuccess()
                val results = json.decodeFromString<List<AnimeItem>>(searchRes.body.string())
                val exactMatch = results.firstOrNull { it.name.equals(item.title, true) } ?: results.firstOrNull()

                Log.d(MOVIXANIME_LOG, "Popular: Results for ${item.title} -> Found: ${exactMatch != null}")
                if (exactMatch != null) {
                    val id = URLEncoder.encode(exactMatch.url, "UTF-8")
                    animeCache[id] = exactMatch
                    SAnime.create().apply {
                        title = exactMatch.name
                        thumbnail_url = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: exactMatch.image
                        url = "/anime/$id"
                        initialized = false
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }.filterNotNull()

        return AnimesPage(animes, false)
    }

    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    // ============================ Latest ============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val tmdbApi = BuildConfig.TMDB_API
        val tmdbUrl = "https://api.themoviedb.org/3/discover/tv?api_key=$tmdbApi&with_genres=&page=$page&language=fr-FR&vote_average_gte=0&sort_by=first_air_date.desc&with_watch_providers=283&watch_region=FR&with_release_type=2%7C3"
        val response = client.newCall(GET(tmdbUrl)).awaitSuccess()
        val data = json.decodeFromString<TmdbDiscoverResponse>(response.body.string())

        val animes = data.results.parallelMap { item ->
            try {
                val titleStr = item.name ?: item.title ?: return@parallelMap null
                val encodedQuery = URLEncoder.encode(titleStr, "UTF-8").replace("+", "%20")
                Log.d(MOVIXANIME_LOG, "Latest: Fetching URL for $titleStr -> $encodedQuery")

                val searchRes = client.newCall(GET("$apiUrl/anime/search/$encodedQuery?includeSeasons=false&includeEpisodes=false", apiHeaders)).awaitSuccess()
                val results = json.decodeFromString<List<AnimeItem>>(searchRes.body.string())
                val exactMatch = results.firstOrNull { it.name.equals(titleStr, true) } ?: results.firstOrNull()

                Log.d(MOVIXANIME_LOG, "Latest: Results for $titleStr -> Found: ${exactMatch != null}")

                if (exactMatch != null) {
                    val id = URLEncoder.encode(exactMatch.url, "UTF-8")
                    animeCache[id] = exactMatch
                    SAnime.create().apply {
                        title = exactMatch.name
                        thumbnail_url = item.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" } ?: exactMatch.image
                        url = "/anime/$id"
                        initialized = false
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.d(MOVIXANIME_LOG, "Latest Error: ${e.message}")
                null
            }
        }.filterNotNull()

        return AnimesPage(animes, data.results.isNotEmpty())
    } override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    // ============================ Search ============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return AnimesPage(emptyList(), false)

        if (trimmedQuery.lowercase().startsWith(PREFIX_SEARCH)) {
            val id = trimmedQuery.substring(PREFIX_SEARCH.length).trim()
            val decodedUrl = java.net.URLDecoder.decode(id, "UTF-8")
            val name = decodedUrl.split("/").filter { it.isNotBlank() }.last()
            // DO NOT filter by targetUrl if the id is just the title (like from Popular/Latest)
            val target = if (decodedUrl.startsWith("http")) decodedUrl else null
            return fetchAndCache(name, target)
        }

        return fetchAndCache(trimmedQuery)
    }

    private suspend fun fetchAndCache(query: String, targetUrl: String? = null): AnimesPage {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val response = client.newCall(GET("$apiUrl/anime/search/$encodedQuery?includeSeasons=true&includeEpisodes=true", apiHeaders)).awaitSuccess()
        val results = json.decodeFromString<List<AnimeItem>>(response.body.string())

        val filtered = if (targetUrl != null) results.filter { it.url == targetUrl } else results

        val animes = filtered.map { item ->
            val id = getAnimeId(item.url)
            animeCache[id] = item
            SAnime.create().apply {
                title = item.name
                thumbnail_url = item.image
                url = "/anime/$id"
                initialized = true
            }
        }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw UnsupportedOperationException()

    // ============================ Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        Log.d(MOVIXANIME_LOG, "getAnimeDetails START for: ${anime.title}")
        Log.d(MOVIXANIME_LOG, "Incoming URL: ${anime.url}")

        val id = anime.url.substringAfter("/anime/").substringBefore("#").substringBefore("?")
        Log.d(MOVIXANIME_LOG, "Parsed ID (Cache Key): $id")

        var item = animeCache[id]
        Log.d(MOVIXANIME_LOG, "Item found in cache? ${item != null}")

        if (item == null) {
            val decodedUrl = java.net.URLDecoder.decode(id, "UTF-8")
            val name = decodedUrl.split("/").filter { it.isNotBlank() }.last().replace("-", " ")
            Log.d(MOVIXANIME_LOG, "Cache miss! Forcing fetchAndCache for name: $name, decodedUrl: $decodedUrl")
            fetchAndCache(name, decodedUrl)
            item = animeCache[id]
            Log.d(MOVIXANIME_LOG, "Item found after forced fetch? ${item != null}")
        }

        if (item != null) {
            val titleFromUrl = anime.url.substringAfter("|", "").takeIf { it.isNotBlank() }
            val titleToSearch = titleFromUrl ?: anime.title

            val tmdbMetadata = if (anime.url.contains("#s")) {
                fetchSmartTmdbMetadata(titleToSearch)
            } else {
                fetchSmartTmdbMetadata(item.name)
            }

            anime.description = tmdbMetadata?.summary ?: ""
            tmdbMetadata?.releaseDate?.let { date ->
                anime.description = "Date de sortie : $date\n\n${anime.description ?: ""}"
            }
            tmdbMetadata?.posterUrl?.let { anime.thumbnail_url = it }
            tmdbMetadata?.author?.let { anime.author = it }
            tmdbMetadata?.artist?.let { anime.artist = it }
            tmdbMetadata?.genre?.let { anime.genre = it }
            tmdbMetadata?.status?.let { anime.status = it }

            if (item.seasons.size > 1 && !anime.url.contains("#s")) {
                anime.coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Seasons)
            } else {
                anime.coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
            }
        }

        anime.initialized = true
        return anime
    }

    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()

    private suspend fun fetchSmartTmdbMetadata(title: String, isMovieHint: Boolean = false): fr.bluecxt.core.TmdbMetadata? {
        if (title.isBlank()) return null

        val seasonRegex = Regex("""(?i)(.*?)\s+\b(?:Saison|Season)\b\s*(\d+)""")
        val oavRegex = Regex("""(?i)(.*?)\s+\b(?:OAV|OVA|Special|Kai|Director's Cut)\b""")
        val movieRegex = Regex("""(?i)(.*?)\s+\b(?:FILM|MOVIE)\b""")

        Log.d(MOVIXANIME_LOG, "fetchSmartTmdbMetadata: Raw title = '$title'")

        return when {
            seasonRegex.containsMatchIn(title) -> {
                val match = seasonRegex.find(title)!!
                val cleanTitle = match.groupValues[1].trim()
                val season = match.groupValues[2].toIntOrNull() ?: 1
                Log.d(MOVIXANIME_LOG, "fetchSmartTmdbMetadata: Detected SEASON. CleanTitle = '$cleanTitle', Season = $season")
                fetchTmdbMetadata(cleanTitle, season, "tv")
            }

            oavRegex.containsMatchIn(title) -> {
                val match = oavRegex.find(title)!!
                val cleanTitle = match.groupValues[1].trim()
                val isTrueSpecial = !title.contains("Kai", true) && !title.contains("Director's Cut", true)
                val seasonToFetch = if (isTrueSpecial) 0 else 1
                Log.d(MOVIXANIME_LOG, "fetchSmartTmdbMetadata: Detected OAV/SPECIAL. CleanTitle = '$cleanTitle', SeasonToFetch = $seasonToFetch")
                val meta = fetchTmdbMetadata(cleanTitle, seasonToFetch, "tv")
                meta?.let { filterSmartMetadata(it, isSpecialSeason = isTrueSpecial) }
            }

            isMovieHint || movieRegex.containsMatchIn(title) -> {
                val match = movieRegex.find(title)
                val cleanTitle = match?.groupValues?.get(1)?.trim() ?: title
                Log.d(MOVIXANIME_LOG, "fetchSmartTmdbMetadata: Detected MOVIE. CleanTitle = '$cleanTitle'")
                val movieMeta = fetchTmdbMetadata(cleanTitle, 1, "movie")
                Log.d(MOVIXANIME_LOG, "fetchSmartTmdbMetadata: Movie fetch result = ${movieMeta != null}")
                movieMeta ?: fetchTmdbMetadata(cleanTitle, 1, "tv")
            }

            else -> {
                Log.d(MOVIXANIME_LOG, "fetchSmartTmdbMetadata: No regex match, fetching as is: '$title'")
                fetchTmdbMetadata(title)
            }
        }.also {
            Log.d(MOVIXANIME_LOG, "fetchSmartTmdbMetadata: Final returned meta is null? ${it == null}")
            if (it != null) {
                Log.d(MOVIXANIME_LOG, "fetchSmartTmdbMetadata: Meta details: poster=${it.posterUrl}, epSummaries keys=${it.episodeSummaries.keys}")
            }
        }
    }

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val id = anime.url.substringAfter("/anime/").substringBefore("#").substringBefore("?")
        val cleanId = id.removePrefix(PREFIX_SEARCH)
        val decodedName = java.net.URLDecoder.decode(cleanId, "UTF-8").split("/").filter { it.isNotBlank() }.last()
        val item = animeCache[id] ?: animeCache.values.firstOrNull { it.name.equals(decodedName, true) }
        if (item == null) {
            return emptyList()
        }

        val siteSeasons = item.seasons.mapIndexed { index, season ->
            val seasonNum = index + 1
            val fullTitle = if (season.name.contains(item.name, true)) season.name else "${item.name} ${season.name}"
            val sUrl = "/anime/$id#s$index|${fullTitle.replace("|", "")}"
            Triple(fullTitle, sUrl, seasonNum)
        }

        return siteSeasons.mapIndexed { index, (sTitle, sUrl, _) ->
            val tmdbMeta = fetchSmartTmdbMetadata(sTitle)

            SAnime.create().apply {
                title = sTitle
                url = sUrl
                thumbnail_url = tmdbMeta?.posterUrl ?: item.image
                description = tmdbMeta?.summary
                tmdbMeta?.releaseDate?.let { date ->
                    description = "Date de sortie : $date\n\n${description ?: ""}"
                }
                genre = tmdbMeta?.genre
                author = tmdbMeta?.author
                artist = tmdbMeta?.artist
                status = if (index < siteSeasons.size - 1) SAnime.COMPLETED else (tmdbMeta?.status ?: SAnime.UNKNOWN)

                coreOptimizeDisplayTitle(sTitle, item.name)
                coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
                coreSetSeasonNumber(-2.0)
                initialized = true
            }
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.substringAfter("/anime/").substringBefore("#").substringBefore("?")
        val cleanId = id.removePrefix(PREFIX_SEARCH)
        val decodedName = java.net.URLDecoder.decode(cleanId, "UTF-8").split("/").filter { it.isNotBlank() }.last()
        val item = animeCache[id] ?: animeCache.values.firstOrNull { it.name.equals(decodedName, true) } ?: return emptyList()

        val sIdxFromUrl = anime.url.substringAfter("#s", "").substringBefore("|").toIntOrNull()

        val seasonsToProcess = if (sIdxFromUrl != null) {
            listOf(item.seasons[sIdxFromUrl] to (sIdxFromUrl + 1))
        } else {
            item.seasons.mapIndexed { index, season -> season to (index + 1) }
        }

        val allEpisodes = mutableListOf<SEpisode>()
        for ((season, seasonNumber) in seasonsToProcess) {
            val fullTitle = if (season.name.contains(item.name, true)) season.name else "${item.name} ${season.name}"
            Log.d(MOVIXANIME_LOG, "getEpisodeList: Processing season with fullTitle = '$fullTitle', seasonNumber = $seasonNumber")

            val tmdbSNum = season.name.replace("Saison ", "").toDoubleOrNull()?.toInt() ?: seasonNumber
            val isMovieHint = season.episodes.size == 1 && !fullTitle.contains("OAV", true) && !fullTitle.contains("Special", true)
            val isOav = fullTitle.contains("OAV", true) || fullTitle.contains("Special", true)
            val isMovie = fullTitle.contains("Film", true) || fullTitle.contains("Movie", true) || isMovieHint

            val tmdbMetadata = fetchSmartTmdbMetadata(fullTitle, isMovieHint)

            // Offset Calculation (AnimeSama logic)
            var siteOffset = 0
            var oavOffset = 0
            val baseTitle = item.name
            val realCurrentIndex = item.seasons.indexOf(season)
            val seenTmdbSeasons = mutableSetOf<Int>()

            for (i in 0 until realCurrentIndex) {
                val prevSeason = item.seasons[i]
                val prevName = prevSeason.name
                val count = prevSeason.episodes.size
                if (count == 0) continue

                val isPrevOav = prevName.contains("OAV", true) || prevName.contains("Film", true) || prevName.contains("Special", true)
                val seasonNumMatch = Regex("""\d+""").find(prevName)

                if (!isPrevOav && seasonNumMatch != null) {
                    val sN = seasonNumMatch.value.toInt()
                    if (seenTmdbSeasons.contains(sN)) continue
                    seenTmdbSeasons.add(sN)
                    val prevTmdbMeta = fetchTmdbMetadata(baseTitle, sN, "tv")
                    val tmdbCount = prevTmdbMeta?.episodeSummaries?.size ?: 0
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

            // Reverse Overflow (Absolute Mapping) Check
            val activeTmdbMeta = if (!isOav && !isMovie && tmdbSNum > 1 && siteOffset > 0) {
                val s1Meta = fetchTmdbMetadata(baseTitle, 1)
                val s1Count = s1Meta?.episodeSummaries?.size ?: 0
                if (s1Count > siteOffset) s1Meta else tmdbMetadata
            } else {
                tmdbMetadata
            }

            var tmdbEpCount = activeTmdbMeta?.episodeSummaries?.size ?: 0

            // Overflow metadata (S0)
            val s0Metadata = if (tmdbSNum > 0 && season.episodes.size > tmdbEpCount) {
                fetchTmdbMetadata(baseTitle, 0, "tv")?.let { filterSmartMetadata(it, isSpecialSeason = true) }
            } else {
                null
            }

            val rawEpisodes = season.episodes.map { ep ->
                SEpisode.create().apply {
                    name = ep.name
                    episode_number = ep.index.toFloat()
                    url = "/anime/$id?s=${seasonNumber - 1}&e=${ep.index - 1}"
                }
            }

            Log.d(MOVIXANIME_LOG, "getEpisodeList: isMovie detected as $isMovie for title '$fullTitle'")

            val mappedEpisodes = coreMapEpisodes(
                rawEpisodes = rawEpisodes,
                tmdbMetadata = activeTmdbMeta,
                tmdbS0Metadata = s0Metadata,
                offsets = Pair(siteOffset, oavOffset),
                sNum = tmdbSNum,
                isMovie = isMovie,
                isOav = isOav,
            )

            mappedEpisodes.forEach {
                it.scanlator = "Season $seasonNumber"
                Log.d(MOVIXANIME_LOG, "getEpisodeList: Mapped Episode ${it.name}, initial preview_url = ${it.preview_url}")
                if (isMovie && season.episodes.size == 1) {
                    it.name = "[Movie] $fullTitle".replace(" [Movie]", "") // On nettoie le nom pour n'avoir que le titre du film
                    it.preview_url = tmdbMetadata?.posterUrl ?: it.preview_url
                    it.summary = tmdbMetadata?.summary ?: it.summary
                    Log.d(MOVIXANIME_LOG, "getEpisodeList: Single-episode movie detected. Renamed to ${it.name}, Overrode preview_url with posterUrl = ${it.preview_url}")
                } else if (isMovie && it.preview_url == null) {
                    it.preview_url = tmdbMetadata?.posterUrl
                    it.summary = tmdbMetadata?.summary
                    Log.d(MOVIXANIME_LOG, "getEpisodeList: Overrode preview_url with posterUrl = ${it.preview_url}")
                }
            }
            allEpisodes.addAll(mappedEpisodes)
        }

        return allEpisodes.asReversed()
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val animeId = episode.url.substringBefore("?").substringAfter("/anime/")
        val sIdx = episode.url.substringAfter("?s=").substringBefore("&").toInt()
        val eIdx = episode.url.substringAfter("&e=").toInt()

        val item = animeCache[animeId] ?: return emptyList()
        val epData = item.seasons[sIdx].episodes[eIdx]

        return epData.streaming_links.map { link ->
            Hoster(
                hosterName = link.language.uppercase(),
                internalData = json.encodeToString(link.players) + "|" + link.language.uppercase(),
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        Log.d(MOVIXANIME_LOG, "getVideoList START for hoster: ${hoster.hosterName}")
        val data = hoster.internalData.split("|")
        val players = json.decodeFromString<List<String>>(data[0])
        Log.d(MOVIXANIME_LOG, "Raw players list: $players")
        val langLabel = data[1]

        return players.parallelMap { playerUrl ->
            try {
                extractVideos(playerUrl, lang = langLabel, allowedServers = supportedServers)
            } catch (e: Exception) {
                Log.d(MOVIXANIME_LOG, "Exception extracting $playerUrl : ${e.message}")
                emptyList()
            }
        }.flatten().distinctBy { it.videoUrl }.also { videos ->
            val logMessage = videos.joinToString { v -> "${v.videoTitle} -> ${v.videoUrl}" }
            Log.d(MOVIXANIME_LOG, "Final sorted videos list: [$logMessage]")
        }
    }
}
