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

/** Position du segment épisode dans l'URL. */
private const val EPISODE_SEGMENT_INDEX = 4

/** Indicateurs dérivés du nom de saison (numéro TMDB, film, OAV). */
private data class SeasonKind(
    val tmdbSeasonNumber: Int,
    val isMovieHint: Boolean,
    val isOav: Boolean,
    val isMovie: Boolean,
)

/** Contexte TMDB partagé par les épisodes d'une même saison. */
private data class SeasonTmdbContext(
    val fullTitle: String,
    val episodeCount: Int,
    val kind: SeasonKind,
    val tmdbMetadata: TmdbMetadata?,
    val activeTmdbMeta: TmdbMetadata?,
    val tmdbEpisodeCount: Int,
    val siteOffset: Int,
    val oavOffset: Int,
    val s0Metadata: TmdbMetadata?,
)

/** Coordonnées TMDB calculées pour un épisode. */
private data class EpisodeTmdbCoordinates(
    val tmdbId: Int,
    val tmdbType: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
)

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
                Log.w(MOVIXANIME_LOG, "Échec de la recherche populaire pour ${item.title}", e)
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
            item = fetchMissingAnimeItem(id)
        }

        if (item != null) {
            val titleFromUrl = anime.url.substringAfter("|", "").takeIf { it.isNotBlank() }
            val titleToSearch = titleFromUrl ?: anime.title

            val tmdbMetadata = if (anime.url.contains("#s")) {
                fetchSmartTmdbMetadata(titleToSearch)
            } else {
                fetchSmartTmdbMetadata(item.name)
            }

            applyTmdbMetadata(anime, tmdbMetadata)
            anime.fetch_type = resolveFetchType(anime, item)
        }

        anime.initialized = true
        return anime
    }

    /** Force un fetchAndCache quand l'item est absent du cache, puis renvoie l'item éventuel. */
    private suspend fun fetchMissingAnimeItem(id: String): AnimeItem? {
        val decodedUrl = java.net.URLDecoder.decode(id, "UTF-8")
        val name = decodedUrl.split("/").last { it.isNotBlank() }.replace("-", " ")
        Log.d(MOVIXANIME_LOG, "Cache miss! Forcing fetchAndCache for name: $name, decodedUrl: $decodedUrl")
        fetchAndCache(name, decodedUrl)
        val item = animeCache[id]
        Log.d(MOVIXANIME_LOG, "Item found after forced fetch? ${item != null}")
        return item
    }

    /** Applique les champs TMDB à [anime] (description, poster, auteur, studio, genre, statut). */
    private fun applyTmdbMetadata(anime: SAnime, tmdbMetadata: TmdbMetadata?) {
        anime.description = tmdbMetadata?.summary ?: ""
        tmdbMetadata?.releaseDate?.let { date ->
            anime.description = "Date de sortie : $date\n\n${anime.description ?: ""}"
        }
        tmdbMetadata?.mainPosterUrl?.let { anime.thumbnail_url = it }
        tmdbMetadata?.author?.let { anime.author = it }
        tmdbMetadata?.artist?.let { anime.artist = it }
        tmdbMetadata?.genre?.let { anime.genre = it }
        tmdbMetadata?.status?.let { anime.status = it }
    }

    /** Saison => FetchType.Seasons, sinon FetchType.Episodes. */
    private fun resolveFetchType(anime: SAnime, item: AnimeItem): eu.kanade.tachiyomi.animesource.model.FetchType = if (item.seasons.size > 1 && !anime.url.contains("#s")) {
        eu.kanade.tachiyomi.animesource.model.FetchType.Seasons
    } else {
        eu.kanade.tachiyomi.animesource.model.FetchType.Episodes
    }

    private suspend fun fetchSmartTmdbMetadata(title: String, isMovieHint: Boolean = false): TmdbMetadata? {
        if (title.isBlank()) return null

        Log.d(MOVIXANIME_LOG, "fetchSmartTmdbMetadata: Raw title = '$title'")

        return when {
            seasonRegex.containsMatchIn(title) -> fetchSeasonTmdbMetadata(title)

            oavRegex.containsMatchIn(title) -> fetchOavTmdbMetadata(title)

            isMovieHint || movieRegex.containsMatchIn(title) -> fetchMovieTmdbMetadata(title)

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

    /** Titre contenant « Saison/Season N » : fetch de la saison détectée. */
    private suspend fun fetchSeasonTmdbMetadata(title: String): TmdbMetadata? {
        val match = seasonRegex.find(title)!!
        val cleanTitle = match.groupValues[1].trim()
        val season = match.groupValues[2].toIntOrNull() ?: 1
        Log.d(
            MOVIXANIME_LOG,
            "fetchSmartTmdbMetadata: Detected SEASON. CleanTitle = '$cleanTitle', Season = $season",
        )
        return fetchTmdbMetadata(cleanTitle, season, "tv")
    }

    /** Titre contenant OAV/OVA/Special/Kai/Director's Cut : saison 0 pour un vrai special. */
    private suspend fun fetchOavTmdbMetadata(title: String): TmdbMetadata? {
        val match = oavRegex.find(title)!!
        val cleanTitle = match.groupValues[1].trim()
        val isTrueSpecial = !title.contains("Kai", true) && !title.contains("Director's Cut", true)
        val seasonToFetch = if (isTrueSpecial) 0 else 1
        Log.d(
            MOVIXANIME_LOG,
            "fetchSmartTmdbMetadata: Detected OAV/SPECIAL. CleanTitle = '$cleanTitle', SeasonToFetch = $seasonToFetch",
        )
        val meta = fetchTmdbMetadata(cleanTitle, seasonToFetch, "tv")
        return meta?.let { filterSmartMetadata(it, isSpecialSeason = isTrueSpecial) }
    }

    /** Titre de film (ou indice film) : fetch movie puis repli sur tv. */
    private suspend fun fetchMovieTmdbMetadata(title: String): TmdbMetadata? {
        val match = movieRegex.find(title)
        val cleanTitle = match?.groupValues?.get(1)?.trim() ?: title
        Log.d(MOVIXANIME_LOG, "fetchSmartTmdbMetadata: Detected MOVIE. CleanTitle = '$cleanTitle'")
        val movieMeta = fetchTmdbMetadata(cleanTitle, 1, "movie")
        Log.d(MOVIXANIME_LOG, "fetchSmartTmdbMetadata: Movie fetch result = ${movieMeta != null}")
        return movieMeta ?: fetchTmdbMetadata(cleanTitle, 1, "tv")
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
        val fullTitle = seasonFullTitle(item, season)
        Log.d(
            MOVIXANIME_LOG,
            "getEpisodeList: Processing season with fullTitle = '$fullTitle', seasonNumber = $seasonNumber",
        )

        val kind = computeSeasonKind(fullTitle, season, seasonNumber)

        val tmdbMetadata = fetchSmartTmdbMetadata(fullTitle, kind.isMovieHint)
        val (siteOffset, oavOffset) = calculateSeasonOffsets(item, season)

        val activeTmdbMeta =
            resolveActiveTmdbMeta(item.name, kind.tmdbSeasonNumber, siteOffset, kind.isOav, kind.isMovie, tmdbMetadata)
        val tmdbEpCount = activeTmdbMeta?.episodeSummaries?.size ?: 0

        val s0Metadata = fetchSpecialSeasonMetadata(item.name, kind.tmdbSeasonNumber, season.episodes.size, tmdbEpCount)

        val rawEpisodes = buildRawEpisodes(season, seasonNumber, id)

        val mappedEpisodes = coreMapEpisodes(
            rawEpisodes = rawEpisodes,
            tmdbMetadata = activeTmdbMeta,
            tmdbS0Metadata = s0Metadata,
            offsets = Pair(siteOffset, oavOffset),
            sNum = kind.tmdbSeasonNumber,
            isMovie = kind.isMovie,
            isOav = kind.isOav,
        )

        val context = SeasonTmdbContext(
            fullTitle = fullTitle,
            episodeCount = season.episodes.size,
            kind = kind,
            tmdbMetadata = tmdbMetadata,
            activeTmdbMeta = activeTmdbMeta,
            tmdbEpisodeCount = tmdbEpCount,
            siteOffset = siteOffset,
            oavOffset = oavOffset,
            s0Metadata = s0Metadata,
        )

        mappedEpisodes.forEach { episode ->
            episode.scanlator = "Season $seasonNumber"
            applyEpisodeTmdbData(episode, context)
        }
        return mappedEpisodes
    }

    /** « Nom de la série » + nom de saison, sans duplication si le nom contient déjà la série. */
    private fun seasonFullTitle(item: AnimeItem, season: Season): String = if (season.name.contains(item.name, true)) season.name else "${item.name} ${season.name}"

    /** Dérive numéro de saison TMDB et drapeaux film/OAV depuis le titre complet. */
    private fun computeSeasonKind(fullTitle: String, season: Season, seasonNumber: Int): SeasonKind {
        val tmdbSeasonNumber = season.name.replace("Saison ", "").toDoubleOrNull()?.toInt() ?: seasonNumber
        val isMovieHint =
            season.episodes.size == 1 && !fullTitle.contains("OAV", true) && !fullTitle.contains("Special", true)
        val isOav = fullTitle.contains("OAV", true) || fullTitle.contains("Special", true)
        val isMovie = fullTitle.contains("Film", true) || fullTitle.contains("Movie", true) || isMovieHint
        return SeasonKind(tmdbSeasonNumber, isMovieHint, isOav, isMovie)
    }

    /** Métadonnées de la saison 0 (specials) quand le site a plus d'épisodes que TMDB. */
    private suspend fun fetchSpecialSeasonMetadata(
        baseTitle: String,
        tmdbSNum: Int,
        episodeCount: Int,
        tmdbEpCount: Int,
    ): TmdbMetadata? = if (tmdbSNum > 0 && episodeCount > tmdbEpCount) {
        fetchTmdbMetadata(baseTitle, 0, "tv")?.let { filterSmartMetadata(it, isSpecialSeason = true) }
    } else {
        null
    }

    /** Épisodes bruts du site, avant mapping TMDB. */
    private fun buildRawEpisodes(season: Season, seasonNumber: Int, id: String): List<SEpisode> = season.episodes.map { ep ->
        SEpisode.create().apply {
            name = ep.name
            episode_number = ep.index.toFloat()
            url = "/anime/$id?s=${seasonNumber - 1}&e=${ep.index - 1}"
        }
    }

    /** Surcharges image/résumé réservées aux saisons détectées comme film. */
    private fun applyMovieEpisodeOverrides(episode: SEpisode, context: SeasonTmdbContext) {
        if (context.kind.isMovie && context.episodeCount == 1) {
            episode.name = "[Movie] ${context.fullTitle}".replace(" [Movie]", "")
            episode.preview_url = context.tmdbMetadata?.mainPosterUrl ?: episode.preview_url
            episode.summary = context.tmdbMetadata?.summary ?: episode.summary
        } else if (context.kind.isMovie && episode.preview_url == null) {
            episode.preview_url = context.tmdbMetadata?.seasonPosterUrl
            episode.summary = context.tmdbMetadata?.summary
        }
    }

    /** L'épisode dépasse-t-il la saison TMDB courante (bascule en saison 0) ? */
    private fun isSpecialEpisode(absEpNum: Int, context: SeasonTmdbContext): Boolean = context.activeTmdbMeta != null && context.tmdbEpisodeCount > 0 && absEpNum > context.tmdbEpisodeCount

    private fun resolveEpisodeTmdbId(isSpecial: Boolean, context: SeasonTmdbContext): Int? = if (isSpecial) {
        context.s0Metadata?.tmdbId ?: context.activeTmdbMeta?.tmdbId
    } else {
        context.activeTmdbMeta?.tmdbId
    }

    private fun resolveEpisodeTmdbType(isSpecial: Boolean, context: SeasonTmdbContext): String? = if (isSpecial) {
        context.s0Metadata?.type ?: context.activeTmdbMeta?.type
    } else {
        context.activeTmdbMeta?.type
    }

    private fun resolveFinalTmdbType(tmdbType: String?, isMovie: Boolean): String = tmdbType ?: if (isMovie) "movie" else "tv"

    /** Coordonnées TMDB de l'épisode, ou null si aucun tmdbId exploitable. */
    private fun resolveEpisodeTmdbCoordinates(episode: SEpisode, context: SeasonTmdbContext): EpisodeTmdbCoordinates? {
        val absEpNum = episode.episode_number.toInt() + context.siteOffset
        val isSpecial = isSpecialEpisode(absEpNum, context)

        val seasonNumber = if (isSpecial) 0 else context.kind.tmdbSeasonNumber
        val episodeNumber = if (isSpecial) (absEpNum - context.tmdbEpisodeCount) + context.oavOffset else absEpNum
        val tmdbId = resolveEpisodeTmdbId(isSpecial, context) ?: return null
        val tmdbType = resolveFinalTmdbType(resolveEpisodeTmdbType(isSpecial, context), context.kind.isMovie)
        return EpisodeTmdbCoordinates(tmdbId, tmdbType, seasonNumber, episodeNumber)
    }

    /** Applique surcharges film puis suffixe TMDB à l'épisode. */
    private fun applyEpisodeTmdbData(episode: SEpisode, context: SeasonTmdbContext) {
        applyMovieEpisodeOverrides(episode, context)
        val coordinates = resolveEpisodeTmdbCoordinates(episode, context) ?: return
        episode.url += "&tmdbId=${coordinates.tmdbId}&tmdbType=${coordinates.tmdbType}" +
            "&tmdbS=${coordinates.seasonNumber}&tmdbE=${coordinates.episodeNumber}"
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
                val (siteDelta, oavDelta) = seasonOffsetContribution(count, tmdbCount)
                siteOffset += siteDelta
                oavOffset += oavDelta
            } else {
                oavOffset += count
            }
        }
        return Pair(siteOffset, oavOffset)
    }

    /** Contribution d'une saison précédente : (épisodes alignés sur TMDB, surplus basculé en OAV). */
    private fun seasonOffsetContribution(count: Int, tmdbCount: Int): Pair<Int, Int> = if (tmdbCount > 0) {
        minOf(count, tmdbCount) to if (count > tmdbCount) count - tmdbCount else 0
    } else {
        count to 0
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

        if (tmdbId != null && tmdbType != null) {
            if (tmdbSeason != null && tmdbEpisode != null) {
                endpoints.addAll(buildMovixHosters(tmdbType, tmdbId, tmdbSeason, tmdbEpisode))
            }
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
        val episode = data.getOrNull(EPISODE_SEGMENT_INDEX)
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
