// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.movix

import android.util.Log
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.AnimeItem
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.Season
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.TmdbDiscoverResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.Top10Item
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.Top10Response
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.get
import eu.kanade.tachiyomi.util.parallelMap
import eu.kanade.tachiyomi.util.parseAs
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.MOVIXANIME_LOG
import fr.bluecxt.core.tmdb.TmdbMetadata
import fr.bluecxt.core.tmdb.fetchTmdbMetadata
import fr.bluecxt.core.tmdb.filterSmartMetadata
import keiyoushi.core.BuildConfig
import kotlinx.serialization.encodeToString
import okhttp3.Response
import java.net.URLEncoder
import java.util.concurrent.CancellationException

class MovixAnime : BaseMovix("Movix Anime") {

    private val animeCache = mutableMapOf<String, AnimeItem>()

    private fun getAnimeId(url: String): String = URLEncoder.encode(url, "UTF-8")

    // ============================ Popular ============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val response = client.get("$apiUrl/api/top10/overview?type=anime", apiHeaders)
        val top10Item: List<Top10Item> = response.use { it.body.string().parseAs<Top10Response>().top10 }

        val animes = top10Item.parallelMap { item ->
            try {
                val encodedQuery = URLEncoder.encode(item.title, "UTF-8").replace("+", "%20")
                Log.d(MOVIXANIME_LOG, "Popular: Fetching URL for ${item.title} -> $encodedQuery")
                val searchRes = client.get("$apiUrl/anime/search/$encodedQuery?includeSeasons=false&includeEpisodes=false", apiHeaders)
                val results: List<AnimeItem> = searchRes.use { it.body.string().parseAs() }
                val exactMatch = results.firstOrNull { it.name.equals(item.title, true) } ?: results.firstOrNull()

                Log.d(MOVIXANIME_LOG, "Popular: Results for ${item.title} -> Found: ${exactMatch != null}")
                if (exactMatch != null) {
                    val id = URLEncoder.encode(exactMatch.url, "UTF-8")
                    animeCache[id] = exactMatch
                    SAnime.create().apply {
                        title = exactMatch.name
                        thumbnail_url =
                            item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: exactMatch.image
                        url = "/anime/$id"
                        initialized = false
                    }
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
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
        val tmdbUrl =
            "https://api.themoviedb.org/3/discover/tv?api_key=$tmdbApi&with_genres=&page=$page&language=fr-FR&vote_average_gte=0&sort_by=first_air_date.desc&with_watch_providers=283&watch_region=FR&with_release_type=2%7C3"
        val response = client.get(tmdbUrl)
        val data: TmdbDiscoverResponse = response.use { it.body.string().parseAs() }

        val animes = data.results.parallelMap { item ->
            try {
                val titleStr = item.name ?: item.title ?: return@parallelMap null
                val encodedQuery = URLEncoder.encode(titleStr, "UTF-8").replace("+", "%20")
                Log.d(MOVIXANIME_LOG, "Latest: Fetching URL for $titleStr -> $encodedQuery")

                val searchRes = client.get("$apiUrl/anime/search/$encodedQuery?includeSeasons=false&includeEpisodes=false", apiHeaders)
                val results: List<AnimeItem> = searchRes.use { it.body.string().parseAs() }
                val exactMatch = results.firstOrNull { it.name.equals(titleStr, true) } ?: results.firstOrNull()

                Log.d(MOVIXANIME_LOG, "Latest: Results for $titleStr -> Found: ${exactMatch != null}")

                if (exactMatch != null) {
                    val id = URLEncoder.encode(exactMatch.url, "UTF-8")
                    animeCache[id] = exactMatch
                    SAnime.create().apply {
                        title = exactMatch.name
                        thumbnail_url =
                            item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: exactMatch.image
                        url = "/anime/$id"
                        initialized = false
                    }
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(MOVIXANIME_LOG, "Latest Error: ${e.message}")
                null
            }
        }.filterNotNull()

        return AnimesPage(animes, data.results.isNotEmpty())
    }

    // ============================ Search ============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return AnimesPage(emptyList(), false)

        if (trimmedQuery.lowercase().startsWith(PREFIX_SEARCH)) {
            val id = trimmedQuery.substring(PREFIX_SEARCH.length).trim()
            val decodedUrl = java.net.URLDecoder.decode(id, "UTF-8")
            val name = decodedUrl.split("/").last { it.isNotBlank() }
            val target = if (decodedUrl.startsWith("http")) decodedUrl else null
            return fetchAndCache(name, target)
        }

        return fetchAndCache(trimmedQuery)
    }

    private suspend fun fetchAndCache(query: String, targetUrl: String? = null): AnimesPage {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val response = client.get("$apiUrl/anime/search/$encodedQuery?includeSeasons=true&includeEpisodes=true", apiHeaders)
        val results: List<AnimeItem> = response.use { it.body.string().parseAs() }

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

    // ============================ URL ============================

    override fun getAnimeUrl(anime: SAnime): String {
        val cleanUrl = anime.url.substringBefore("#").substringBefore("?")
        return "$baseUrl$cleanUrl"
    }

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
            val name = decodedUrl.split("/").last { it.isNotBlank() }.replace("-", " ")
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
            tmdbMetadata?.mainPosterUrl?.let { anime.thumbnail_url = it }
            tmdbMetadata?.author?.let { anime.author = it }
            tmdbMetadata?.artist?.let { anime.artist = it }
            tmdbMetadata?.genre?.let { anime.genre = it }
            tmdbMetadata?.status?.let { anime.status = it }

            if (item.seasons.size > 1 && !anime.url.contains("#s")) {
                anime.fetch_type = eu.kanade.tachiyomi.animesource.model.FetchType.Seasons
            } else {
                anime.fetch_type = eu.kanade.tachiyomi.animesource.model.FetchType.Episodes
            }
        }

        anime.initialized = true
        return anime
    }

    private suspend fun fetchSmartTmdbMetadata(title: String, isMovieHint: Boolean = false): TmdbMetadata? {
        if (title.isBlank()) return null

        Log.d(MOVIXANIME_LOG, "fetchSmartTmdbMetadata: Raw title = '$title'")

        return when {
            seasonRegex.containsMatchIn(title) -> {
                val match = seasonRegex.find(title)!!
                val cleanTitle = match.groupValues[1].trim()
                val season = match.groupValues[2].toIntOrNull() ?: 1
                Log.d(
                    MOVIXANIME_LOG,
                    "fetchSmartTmdbMetadata: Detected SEASON. CleanTitle = '$cleanTitle', Season = $season",
                )
                fetchTmdbMetadata(cleanTitle, season, "tv")
            }

            oavRegex.containsMatchIn(title) -> {
                val match = oavRegex.find(title)!!
                val cleanTitle = match.groupValues[1].trim()
                val isTrueSpecial = !title.contains("Kai", true) && !title.contains("Director's Cut", true)
                val seasonToFetch = if (isTrueSpecial) 0 else 1
                Log.d(
                    MOVIXANIME_LOG,
                    "fetchSmartTmdbMetadata: Detected OAV/SPECIAL. CleanTitle = '$cleanTitle', SeasonToFetch = $seasonToFetch",
                )
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
                Log.d(
                    MOVIXANIME_LOG,
                    "fetchSmartTmdbMetadata: Meta details: mainPosterUrl=${it.mainPosterUrl}, seasonPosterUrl = ${it.seasonPosterUrl}, epSummaries keys=${it.episodeSummaries.keys}",
                )
            }
        }
    }

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val id = anime.url.substringAfter("/anime/").substringBefore("#").substringBefore("?")
        val cleanId = id.removePrefix(PREFIX_SEARCH)
        val decodedName = java.net.URLDecoder.decode(cleanId, "UTF-8").split("/").last { it.isNotBlank() }
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
                thumbnail_url = tmdbMeta?.seasonPosterUrl ?: item.image
                description = tmdbMeta?.summary
                tmdbMeta?.releaseDate?.let { date ->
                    description = "Date de sortie : $date\n\n${description ?: ""}"
                }
                genre = tmdbMeta?.genre
                author = tmdbMeta?.author
                artist = tmdbMeta?.artist
                status = if (index < siteSeasons.size - 1) SAnime.COMPLETED else (tmdbMeta?.status ?: SAnime.UNKNOWN)

                coreOptimizeDisplayTitle(sTitle, item.name)
                fetch_type = eu.kanade.tachiyomi.animesource.model.FetchType.Episodes
                season_number = HUB_SEASON_NUMBER
                initialized = true
            }
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.substringAfter("/anime/").substringBefore("#").substringBefore("?")
        val cleanId = id.removePrefix(PREFIX_SEARCH)
        val decodedName = java.net.URLDecoder.decode(cleanId, "UTF-8").split("/").last { it.isNotBlank() }
        val item =
            animeCache[id] ?: animeCache.values.firstOrNull { it.name.equals(decodedName, true) } ?: return emptyList()

        val sIdxFromUrl = anime.url.substringAfter("#s", "").substringBefore("|").toIntOrNull()

        val seasonsToProcess = if (sIdxFromUrl != null) {
            listOf(item.seasons[sIdxFromUrl] to (sIdxFromUrl + 1))
        } else {
            item.seasons.mapIndexed { index, season -> season to (index + 1) }
        }

        val allEpisodes = mutableListOf<SEpisode>()
        for ((season, seasonNumber) in seasonsToProcess) {
            val episodes = processSeasonEpisodes(item, season, seasonNumber, id)
            allEpisodes.addAll(episodes)
        }

        return allEpisodes.asReversed()
    }

    private suspend fun processSeasonEpisodes(
        item: AnimeItem,
        season: Season,
        seasonNumber: Int,
        id: String,
    ): List<SEpisode> {
        val fullTitle = if (season.name.contains(item.name, true)) season.name else "${item.name} ${season.name}"
        Log.d(
            MOVIXANIME_LOG,
            "getEpisodeList: Processing season with fullTitle = '$fullTitle', seasonNumber = $seasonNumber",
        )

        val tmdbSNum = season.name.replace("Saison ", "").toDoubleOrNull()?.toInt() ?: seasonNumber
        val isMovieHint =
            season.episodes.size == 1 && !fullTitle.contains("OAV", true) && !fullTitle.contains("Special", true)
        val isOav = fullTitle.contains("OAV", true) || fullTitle.contains("Special", true)
        val isMovie = fullTitle.contains("Film", true) || fullTitle.contains("Movie", true) || isMovieHint

        val tmdbMetadata = fetchSmartTmdbMetadata(fullTitle, isMovieHint)
        val (siteOffset, oavOffset) = calculateSeasonOffsets(item, season)

        val activeTmdbMeta = resolveActiveTmdbMeta(item.name, tmdbSNum, siteOffset, isOav, isMovie, tmdbMetadata)
        val tmdbEpCount = activeTmdbMeta?.episodeSummaries?.size ?: 0

        val s0Metadata = if (tmdbSNum > 0 && season.episodes.size > tmdbEpCount) {
            fetchTmdbMetadata(item.name, 0, "tv")?.let { filterSmartMetadata(it, isSpecialSeason = true) }
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
            if (isMovie && season.episodes.size == 1) {
                it.name = "[Movie] $fullTitle".replace(" [Movie]", "")
                it.preview_url = tmdbMetadata?.mainPosterUrl ?: it.preview_url
                it.summary = tmdbMetadata?.summary ?: it.summary
            } else if (isMovie && it.preview_url == null) {
                it.preview_url = tmdbMetadata?.seasonPosterUrl
                it.summary = tmdbMetadata?.summary
            }

            val epNum = it.episode_number.toInt()
            val absEpNum = epNum + siteOffset
            val isS0 = activeTmdbMeta != null && tmdbEpCount > 0 && absEpNum > tmdbEpCount

            val finalTmdbS = if (isS0) 0 else tmdbSNum
            val finalTmdbE = if (isS0) (absEpNum - tmdbEpCount) + oavOffset else absEpNum

            val tmdbId = if (isS0) s0Metadata?.tmdbId ?: activeTmdbMeta?.tmdbId else activeTmdbMeta?.tmdbId
            val tmdbType = if (isS0) s0Metadata?.type ?: activeTmdbMeta?.type else activeTmdbMeta?.type

            if (tmdbId != null) {
                val finalType = tmdbType ?: if (isMovie) "movie" else "tv"
                it.url += "&tmdbId=$tmdbId&tmdbType=$finalType&tmdbS=$finalTmdbS&tmdbE=$finalTmdbE"
            }
        }
        return mappedEpisodes
    }

    private suspend fun calculateSeasonOffsets(item: AnimeItem, season: Season): Pair<Int, Int> {
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

            val isPrevOav = prevName.contains("OAV", true) || prevName.contains("Film", true) || prevName.contains(
                "Special",
                true,
            )
            val seasonNumMatch = digitRegex.find(prevName)

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
        return Pair(siteOffset, oavOffset)
    }

    private suspend fun resolveActiveTmdbMeta(
        baseTitle: String,
        tmdbSNum: Int,
        siteOffset: Int,
        isOav: Boolean,
        isMovie: Boolean,
        fallbackMeta: TmdbMetadata?,
    ): TmdbMetadata? {
        val isOverflow = !isOav && !isMovie && tmdbSNum > 1 && siteOffset > 0
        if (!isOverflow) return fallbackMeta
        val s1Meta = fetchTmdbMetadata(baseTitle, 1)
        val s1Count = s1Meta?.episodeSummaries?.size ?: 0
        return if (s1Count > siteOffset) s1Meta else fallbackMeta
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val animeId = episode.url.substringBefore("?").substringAfter("/anime/")
        val queryParams = episode.url.substringAfter("?", "")
        val sIdx = queryParams.substringAfter("s=").substringBefore("&").toIntOrNull() ?: return emptyList()
        val eIdx = queryParams.substringAfter("e=").substringBefore("&").toIntOrNull() ?: return emptyList()

        val item = animeCache[animeId] ?: return emptyList()
        val epData = item.seasons.getOrNull(sIdx)?.episodes?.getOrNull(eIdx) ?: return emptyList()

        val endpoints = mutableListOf<Hoster>()

        // 1. Native Anime Hosters
        epData.streamingLinks.forEach { link ->
            endpoints.add(
                Hoster(
                    hosterName = link.language.uppercase(),
                    internalData = json.encodeToString(link.players) + "|" + link.language.uppercase(),
                ),
            )
        }

        // 2. Movix Hosters
        val tmdbId = queryParams.substringAfter("tmdbId=", "").substringBefore("&").takeIf { it.isNotBlank() }
        val tmdbType = queryParams.substringAfter("tmdbType=", "").substringBefore("&").takeIf { it.isNotBlank() }
        val tmdbSeason = queryParams.substringAfter("tmdbS=", "").substringBefore("&").takeIf { it.isNotBlank() }
        val tmdbEpisode = queryParams.substringAfter("tmdbE=", "").substringBefore("&").takeIf { it.isNotBlank() }

        if (tmdbId != null && tmdbType != null && tmdbSeason != null && tmdbEpisode != null) {
            endpoints.addAll(buildMovixHosters(tmdbType, tmdbId, tmdbSeason, tmdbEpisode))
        }

        return endpoints
    }
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        Log.d(MOVIXANIME_LOG, "getVideoList START for hoster: ${hoster.hosterName}")
        val data = hoster.internalData.split("|")

        // 1. Native API Link
        if (data[0].startsWith("[")) {
            val players = json.decodeFromString<List<String>>(data[0])
            Log.d(MOVIXANIME_LOG, "Raw players list: $players")
            val langLabel = data[1]

            return players.parallelMap { playerUrl ->
                try {
                    extractVideos(playerUrl, lang = langLabel, allowedServers = supportedServers)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(MOVIXANIME_LOG, "Exception extracting $playerUrl : ${e.message}")
                    emptyList()
                }
            }.flatten().distinctBy { it.videoUrl }.also { videos ->
                val logMessage = videos.joinToString { v -> "${v.videoTitle} -> ${v.videoUrl}" }
                Log.d(MOVIXANIME_LOG, "Final sorted videos list: [$logMessage]")
            }
        }

        // 2. Movix Endpoint Link
        val apiPath = data[0]
        val targetUrl = "$apiUrl/api/$apiPath"
        val type = data.getOrNull(1) ?: "movie"
        val episode = data.getOrNull(4)
        val brand = hoster.hosterName

        Log.d(MOVIXANIME_LOG, "[$brand] → $targetUrl")

        return try {
            val responseText = fetchApiResponse(targetUrl) ?: return emptyList()

            if (!isValidResponse(responseText)) {
                Log.d(MOVIXANIME_LOG, "[$brand] Réponse invalide")
                return emptyList()
            }

            val links = extractLinks(brand, responseText, type, episode)
            Log.d(MOVIXANIME_LOG, "[$brand] ${links.size} liens trouvés")

            links.parallelMap { link ->
                try {
                    extractVideos(link, lang = null, allowedServers = supportedServers)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(MOVIXANIME_LOG, "[$brand] Erreur extraction $link: ${e.message}")
                    emptyList()
                }
            }.flatten().distinctBy { it.videoUrl }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(MOVIXANIME_LOG, "[$brand] Erreur: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private val digitRegex = Regex("""\d+""")
        private val seasonRegex = Regex("""(?i)(.*?)\s+\b(?:Saison|Season)\b\s*(\d+)""")
        private val oavRegex = Regex("""(?i)(.*?)\s+\b(?:OAV|OVA|Special|Kai|Director's Cut)\b""")
        private val movieRegex = Regex("""(?i)(.*?)\s+\b(?:FILM|MOVIE)\b""")
    }
}
