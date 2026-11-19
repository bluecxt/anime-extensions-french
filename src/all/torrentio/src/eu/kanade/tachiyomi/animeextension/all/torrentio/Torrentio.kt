package eu.kanade.tachiyomi.animeextension.all.torrentio

import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.Content
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.EpisodeList
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.EpisodeVideo
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.GetPopularTitlesResponse
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.GetUrlTitleDetailsResponse
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.StreamDataTorrent
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.TorrentioStream
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parallelMap
import fr.bluecxt.core.model.ExtractionException
import fr.bluecxt.core.source.Source
import fr.bluecxt.core.tmdb.TmdbMetadata
import fr.bluecxt.core.tmdb.fetchTmdbMetadataById
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

/** Maximum number of actors and API page size. */
private const val MAX_ACTORS = 4
private const val PAGE_SIZE = 40

class Torrentio : Source() {

    override val name = "Torrentio (Torrent / Debrid)"

    override val baseUrl = "https://torrentio.strem.fun"

    override val lang = "all"

    override val supportsLatest = false

    private fun setFetchTypeSafe(anime: SAnime, type: eu.kanade.tachiyomi.animesource.model.FetchType) {
        try {
            val methods = anime.javaClass.methods
            val setter = methods.find { it.name == "setFetch_type" }
            if (setter != null) {
                val appFetchTypeClass = setter.parameterTypes[0]
                val enumValue = appFetchTypeClass.enumConstants?.find { it.toString() == type.name }
                setter.invoke(anime, enumValue)
            }
        } catch (_: Exception) {}
    }

    private fun setSeasonNumberSafe(anime: SAnime, season: Double) {
        try {
            val methods = anime.javaClass.methods
            val setter = methods.find { it.name == "setSeason_number" }
            if (setter != null) {
                setter.invoke(anime, season)
            }
        } catch (_: Exception) {}
    }

    // ============================== JustWatch API Request ===================
    private fun makeGraphQLRequest(query: String, variables: String): Request {
        val requestBody = """
        {"query": "${query.replace("\n", "")}", "variables": $variables}
        """.trimIndent().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://apis.justwatch.com/graphql")
            .post(requestBody)
            .build()

        return request
    }

    // ============================== JustWatch Api Query ======================
    private fun justWatchQuery(): String = """
            query GetPopularTitles(
              ${"$"}country: Country!,
              ${"$"}first: Int!,
              ${"$"}language: Language!,
              ${"$"}offset: Int,
              ${"$"}searchQuery: String,
              ${"$"}packages: [String!]!,
              ${"$"}objectTypes: [ObjectType!]!,
              ${"$"}popularTitlesSortBy: PopularTitlesSorting!,
              ${"$"}releaseYear: IntFilter
            ) {
              popularTitles(
                country: ${"$"}country
                first: ${"$"}first
                offset: ${"$"}offset
                sortBy: ${"$"}popularTitlesSortBy
                filter: {
                  objectTypes: ${"$"}objectTypes,
                  searchQuery: ${"$"}searchQuery,
                  packages: ${"$"}packages,
                  genres: [],
                  excludeGenres: [],
                  releaseYear: ${"$"}releaseYear
                }
              ) {
                edges {
                  node {
                    id
                    objectType
                    content(country: ${"$"}country, language: ${"$"}language) {
                      fullPath
                      title
                      shortDescription
                      externalIds {
                        imdbId
                      }
                      posterUrl
                      genres {
                        translation(language: ${"$"}language)
                      }
                      credits {
                        name
                        role
                      }
                    }
                  }
                }
                pageInfo {
                  hasPreviousPage
                  hasNextPage
                }
              }
            }
    """.trimIndent()

    private fun parseSearchJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val popularTitlesResponse = json.decodeFromString<GetPopularTitlesResponse>(jsonData)

        val edges = popularTitlesResponse.data?.popularTitles?.edges.orEmpty()
        val hasNextPage = popularTitlesResponse.data?.popularTitles?.pageInfo?.hasNextPage ?: false

        val metaList = edges
            .mapNotNull { edge ->
                val node = edge.node ?: return@mapNotNull null
                val content = node.content ?: return@mapNotNull null

                SAnime.create().apply {
                    url = "${content.externalIds?.imdbId ?: ""},${if (node.objectType == "SHOW") "series" else node.objectType ?: ""},${content.fullPath ?: ""}"
                    title = content.title ?: ""
                    thumbnail_url = "https://images.justwatch.com${content.posterUrl?.replace("{profile}", "s276")?.replace("{format}", "webp")}"
                    description = content.shortDescription ?: ""
                    val genresList = content.genres?.mapNotNull { it.translation }.orEmpty()
                    genre = genresList.joinToString()

                    val directors = content.credits?.filter { it.role == "DIRECTOR" }?.mapNotNull { it.name }
                    author = directors?.joinToString()
                    val actors = content.credits?.filter { it.role == "ACTOR" }?.take(MAX_ACTORS)?.mapNotNull { it.name }
                    artist = actors?.joinToString()
                    initialized = true
                }
            }

        return AnimesPage(metaList, hasNextPage)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val country = preferences.getString(PREF_REGION_KEY, PREF_REGION_DEFAULT)
        val language = preferences.getString(PREF_JW_LANG_KEY, PREF_JW_LANG_DEFAULT)
        val perPage = PAGE_SIZE
        val packages = ""
        val year = 0
        val objectTypes = ""
        val variables = """
            {
              "first": $perPage,
              "offset": ${(page - 1) * perPage},
              "platform": "WEB",
              "country": "$country",
              "language": "$language",
              "searchQuery": "",
              "packages": [$packages],
              "objectTypes": [$objectTypes],
              "popularTitlesSortBy": "TRENDING",
              "releaseYear": {
                "min": $year,
                "max": $year
              }
            }
        """.trimIndent()

        return makeGraphQLRequest(justWatchQuery(), variables)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        return parseSearchJson(jsonData)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
        val id = query.removePrefix(PREFIX_SEARCH)
        client.newCall(GET("$baseUrl/anime/$id"))
            .awaitSuccess()
            .use(::searchAnimeByIdParse)
    } else {
        super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response)
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val country = preferences.getString(PREF_REGION_KEY, PREF_REGION_DEFAULT)
        val language = preferences.getString(PREF_JW_LANG_KEY, PREF_JW_LANG_DEFAULT)
        val perPage = PAGE_SIZE
        val packages = ""
        val year = 0
        val objectTypes = ""
        val variables = """
            {
              "first": $perPage,
              "offset": ${(page - 1) * perPage},
              "platform": "WEB",
              "country": "$country",
              "language": "$language",
              "searchQuery": "${query.replace(Regex("[^A-Za-z0-9 ]"), "").trim()}",
              "packages": [$packages],
              "objectTypes": [$objectTypes],
              "popularTitlesSortBy": "TRENDING",
              "releaseYear": {
                "min": $year,
                "max": $year
              }
            }
        """.trimIndent()

        return makeGraphQLRequest(justWatchQuery(), variables)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    private var cinemetaCache: Pair<String, EpisodeList>? = null

    private suspend fun fetchCinemeta(imdbId: String, type: String): EpisodeList {
        val cache = cinemetaCache
        if (cache != null && cache.first == imdbId) return cache.second

        val responseString = client.newCall(GET("https://cinemeta-live.strem.io/meta/$type/$imdbId.json")).awaitSuccess().body.string()
        val episodeList = json.decodeFromString<EpisodeList>(responseString)
        cinemetaCache = imdbId to episodeList
        return episodeList
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parts = anime.url.substringBefore("#").split(",")
        val imdbId = parts[0]
        val type = parts[1].lowercase()

        val content = fetchTitleDetailsContent(justWatchDetailsQuery(), buildDetailsVariables(parts.last()))
        applyTitleDetails(anime, content)

        applyAnimeFetchType(anime, imdbId, type)

        return anime
    }

    private fun applyTitleDetails(anime: SAnime, content: Content?) {
        anime.title = content?.title ?: ""
        anime.thumbnail_url = "https://images.justwatch.com${content?.posterUrl?.replace("{profile}", "s718")?.replace("{format}", "webp")}"
        anime.description = content?.shortDescription ?: ""
        val genresList = content?.genres?.mapNotNull { it.translation }.orEmpty()
        anime.genre = genresList.joinToString()
    }

    private fun justWatchDetailsQuery(): String = """
            query GetUrlTitleDetails(${"$"}fullPath: String!, ${"$"}country: Country!, ${"$"}language: Language!) {
              urlV2(fullPath: ${"$"}fullPath) {
                node {
                  ...TitleDetails
                }
              }
            }

            fragment TitleDetails on Node {
              ... on MovieOrShowOrSeason {
                id
                objectType
                content(country: ${"$"}country, language: ${"$"}language) {
                  title
                  shortDescription
                  externalIds {
                    imdbId
                  }
                  posterUrl
                  genres {
                    translation(language: ${"$"}language)
                  }
                }
              }
            }
    """.trimIndent()

    private fun buildDetailsVariables(fullPath: String): String {
        val country = preferences.getString(PREF_REGION_KEY, PREF_REGION_DEFAULT)
        val language = preferences.getString(PREF_JW_LANG_KEY, PREF_JW_LANG_DEFAULT)
        return """
            {
              "fullPath": "$fullPath",
              "country": "$country",
              "language": "$language"
            }
        """.trimIndent()
    }

    private suspend fun fetchTitleDetailsContent(query: String, variables: String): Content? = runCatching {
        json.decodeFromString<GetUrlTitleDetailsResponse>(client.newCall(makeGraphQLRequest(query, variables)).awaitSuccess().body.string())
    }.getOrNull()?.data?.urlV2?.node?.content

    private suspend fun applyAnimeFetchType(anime: SAnime, imdbId: String, type: String) {
        if (type == "series" || type == "show") {
            applySeriesFetchType(anime, imdbId)
        } else {
            setFetchTypeSafe(anime, eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
        }
    }

    private suspend fun applySeriesFetchType(anime: SAnime, imdbId: String) {
        val cinemeta = fetchCinemeta(imdbId, "series")
        val seasons = cinemeta.meta?.videos?.mapNotNull { it.season }?.distinct() ?: emptyList()

        val isSeasonUrl = anime.url.contains("#season=")
        if (seasons.size > 1 && !isSeasonUrl) {
            setFetchTypeSafe(anime, eu.kanade.tachiyomi.animesource.model.FetchType.Seasons)
        } else {
            setFetchTypeSafe(anime, eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
            if (isSeasonUrl) {
                applySeasonSuffix(anime)
            }
        }
    }

    private fun applySeasonSuffix(anime: SAnime) {
        val sNum = anime.url.substringAfter("#season=").toIntOrNull() ?: 1
        val sSuffix = if (sNum == 0) "Special" else "Season $sNum"
        if (!anime.title.contains("Season", true)) {
            anime.title = "${anime.title} - $sSuffix"
        }
        setSeasonNumberSafe(anime, sNum.toDouble())
    }

    // ============================== Seasons ==============================

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val parts = anime.url.substringBefore("#").split(",")
        val imdbId = parts[0]
        val cinemeta = fetchCinemeta(imdbId, "series")

        val seasons = cinemeta.meta?.videos?.mapNotNull { it.season }?.distinct()?.sorted() ?: return emptyList()

        return seasons.map { s ->
            SAnime.create().apply {
                url = "${anime.url.substringBefore("#")}#season=$s"
                val sSuffix = if (s == 0) "Special" else "Season $s"
                title = "${anime.title} - $sSuffix"
                thumbnail_url = anime.thumbnail_url
                initialized = false
                setFetchTypeSafe(this, eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
                setSeasonNumberSafe(this, s.toDouble())
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        val parts = anime.url.substringBefore("#").split(",")
        val type = parts[1].lowercase()
        val imdbId = parts[0]
        return GET("https://cinemeta-live.strem.io/meta/$type/$imdbId.json")
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val parts = anime.url.substringBefore("#").split(",")
        val imdbId = parts[0]
        val type = parts[1].lowercase()

        val episodeList = fetchEpisodeList(anime, imdbId, type)

        val targetSeason = if (anime.url.contains("#season=")) {
            anime.url.substringAfter("#season=").toIntOrNull()
        } else {
            null
        }

        // Fetch TMDB Metadata for thumbs and summaries
        val tmdbLang = preferences.getString(PREF_TMDB_LANG_KEY, PREF_TMDB_LANG_DEFAULT)!!
        val tmdbSeasonsMeta = fetchTmdbSeasonsMeta(imdbId, targetSeason, episodeList, tmdbLang)

        return when (episodeList.meta?.type) {
            "series" -> buildSeriesEpisodes(episodeList, targetSeason, tmdbSeasonsMeta)
            "movie" -> buildMovieEpisodes(episodeList, tmdbSeasonsMeta)
            else -> emptyList()
        }
    }

    private suspend fun fetchEpisodeList(anime: SAnime, imdbId: String, type: String): EpisodeList = if (type == "movie") {
        val responseString = client.newCall(episodeListRequest(anime)).awaitSuccess().body.string()
        json.decodeFromString<EpisodeList>(responseString)
    } else {
        fetchCinemeta(imdbId, "series")
    }

    private suspend fun fetchTmdbSeasonsMeta(
        imdbId: String,
        targetSeason: Int?,
        episodeList: EpisodeList,
        tmdbLang: String,
    ): Map<Int, TmdbMetadata?>? = if (imdbId.isNotBlank()) {
        val tmdbIdData = resolveTmdbId(imdbId)
        tmdbIdData?.let { (id, mType) ->
            fetchTmdbSeasonMetadata(id, mType, targetSeason, episodeList, tmdbLang)
        }
    } else {
        null
    }

    private suspend fun resolveTmdbId(imdbId: String): Pair<Int, String>? {
        val tmdbIdUrl = "https://api.themoviedb.org/3/find/$imdbId?api_key=24621da8ae19dce721e59eff2ab479bb&external_source=imdb_id"
        return runCatching {
            val idRes = client.newCall(GET(tmdbIdUrl)).awaitSuccess().use { it.body.string() }
            val idJson = org.json.JSONObject(idRes)
            val movieResults = idJson.optJSONArray("movie_results")
            val tvResults = idJson.optJSONArray("tv_results")
            if (movieResults != null && movieResults.length() > 0) {
                movieResults.getJSONObject(0).getInt("id") to "movie"
            } else if (tvResults != null && tvResults.length() > 0) {
                tvResults.getJSONObject(0).getInt("id") to "tv"
            } else {
                null
            }
        }.getOrNull()
    }

    private suspend fun fetchTmdbSeasonMetadata(
        id: Int,
        mType: String,
        targetSeason: Int?,
        episodeList: EpisodeList,
        tmdbLang: String,
    ): Map<Int, TmdbMetadata?> {
        if (mType == "movie") {
            return mapOf(1 to fetchTmdbMetadataById(id, mType, 1, tmdbLang))
        }

        val seasonsToFetch = if (targetSeason != null) {
            listOf(targetSeason)
        } else {
            episodeList.meta?.videos?.mapNotNull { it.season }?.distinct() ?: listOf(1)
        }

        return seasonsToFetch.parallelMap { s ->
            s to fetchTmdbMetadataById(id, mType, s, tmdbLang)
        }.toMap()
    }

    private fun buildSeriesEpisodes(
        episodeList: EpisodeList,
        targetSeason: Int?,
        tmdbSeasonsMeta: Map<Int, TmdbMetadata?>?,
    ): List<SEpisode> {
        val allVideos = relevantSeriesVideos(episodeList)
        val seasons = allVideos.mapNotNull { it.season }.distinct()
        val hasMultipleSeasons = seasons.size > 1 || seasons.contains(0)

        val filteredVideos = if (targetSeason != null) {
            allVideos.filter { it.season == targetSeason }
        } else {
            allVideos
        }

        return filteredVideos.map { video ->
            buildSeriesEpisode(video, hasMultipleSeasons, tmdbSeasonsMeta)
        }.asReversed()
    }

    private fun relevantSeriesVideos(episodeList: EpisodeList): List<EpisodeVideo> = episodeList.meta?.videos
        ?.let { vList ->
            if (preferences.getBoolean(UPCOMING_EP_KEY, UPCOMING_EP_DEFAULT)) {
                vList
            } else {
                vList.filter { video -> (video.released?.let { parseDate(it) } ?: 0L) <= System.currentTimeMillis() }
            }
        }
        ?.sortedWith(compareBy({ video -> video.season ?: 1 }, { video -> video.number ?: 1 }))
        .orEmpty()

    private fun buildSeriesEpisode(
        video: EpisodeVideo,
        hasMultipleSeasons: Boolean,
        tmdbSeasonsMeta: Map<Int, TmdbMetadata?>?,
    ): SEpisode {
        val sNum = video.season ?: 1
        val eNum = video.number ?: 1
        return SEpisode.create().apply {
            episode_number = eNum.toFloat()
            url = "/stream/series/${video.id}.json"
            date_upload = video.released?.let { parseDate(it) } ?: 0L

            val sPrefix = seasonPrefix(sNum, hasMultipleSeasons)
            name = "$sPrefix${episodeTitle(video, eNum)}"
            scanlator = (video.released?.let { parseDate(it) } ?: 0L)
                .takeIf { it > System.currentTimeMillis() }
                ?.let { "Upcoming" }
                ?: ""

            applyTmdbEpisodeMetadata(this, video, sNum, eNum, sPrefix, tmdbSeasonsMeta)
        }
    }

    private fun seasonPrefix(sNum: Int, hasMultipleSeasons: Boolean): String = when {
        sNum == 0 -> "[Special] "
        hasMultipleSeasons -> "[S$sNum] "
        else -> ""
    }

    private fun episodeTitle(video: EpisodeVideo, eNum: Int): String = if (
        video.title.isNullOrBlank() || video.title.matches(Regex("(?i)Episode\\s*\\d+"))
    ) {
        "Episode $eNum"
    } else {
        if (video.title.contains("Episode", true)) video.title else "Episode $eNum - ${video.title}"
    }

    private fun applyTmdbEpisodeMetadata(
        episode: SEpisode,
        video: EpisodeVideo,
        sNum: Int,
        eNum: Int,
        sPrefix: String,
        tmdbSeasonsMeta: Map<Int, TmdbMetadata?>?,
    ) {
        val meta = tmdbSeasonsMeta?.get(sNum) ?: return
        val epMeta = meta.episodeSummaries[eNum] ?: return
        if (video.title.isNullOrBlank() || video.title.matches(Regex("(?i)Episode\\s*\\d+"))) {
            epMeta.first?.let { episode.name = "$sPrefix$it" }
        }
        episode.preview_url = epMeta.second
        episode.summary = epMeta.third
    }

    private fun buildMovieEpisodes(
        episodeList: EpisodeList,
        tmdbSeasonsMeta: Map<Int, TmdbMetadata?>?,
    ): List<SEpisode> = listOf(
        SEpisode.create().apply {
            episode_number = 1.0F
            url = "/stream/movie/${episodeList.meta?.id}.json"
            name = "[Movie] Film"
            tmdbSeasonsMeta?.get(1)?.let { meta ->
                summary = meta.summary ?: ""
                preview_url = meta.episodeSummaries[1]?.second
            }
        },
    ).asReversed()

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    private fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }
        .getOrNull() ?: 0L

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val mainURL = buildStreamUrl(episode)

        val responseString = client.newCall(GET(mainURL)).awaitSuccess().body.string()
        val streamList = json.decodeFromString<StreamDataTorrent>(responseString)
        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")

        val animeTrackers = buildTrackerList()

        return streamList.streams?.map { stream ->
            buildHoster(stream, debridProvider, animeTrackers)
        }.orEmpty()
    }

    private fun buildStreamUrl(episode: SEpisode): String = buildString {
        append("$baseUrl/")

        val appendQueryParam: (String, Set<String>?) -> Unit = { key, values ->
            values?.takeIf { it.isNotEmpty() }?.let {
                append("$key=${it.filter(String::isNotBlank).joinToString(",")}|")
            }
        }

        appendQueryParam("providers", preferences.getStringSet(PREF_PROVIDER_KEY, PREF_PROVIDERS_DEFAULT))
        appendQueryParam("language", preferences.getStringSet(PREF_LANG_KEY, PREF_LANG_DEFAULT))
        appendQueryParam("qualityfilter", preferences.getStringSet(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT))

        val sortKey = preferences.getString(PREF_SORT_KEY, "quality")
        appendQueryParam("sort", sortKey?.let { setOf(it) })

        val token = preferences.getString(PREF_TOKEN_KEY, null)
        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")

        when {
            token.isNullOrBlank() && debridProvider != "none" -> {
                handler.post {
                    context.let {
                        Toast.makeText(
                            it,
                            "Kindly input the debrid token in the extension settings.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                throw UnsupportedOperationException()
            }

            !token.isNullOrBlank() && debridProvider != "none" -> append("$debridProvider=$token|")
        }
        append(episode.url)
    }.removeSuffix("|")

    private suspend fun buildTrackerList(): String = """http://nyaa.tracker.wf:7777/announce,
            http://anidex.moe:6969/announce,http://tracker.anirena.com:80/announce,
            udp://tracker.uw0.xyz:6969/announce,
            http://share.camoe.cn:8080/announce,
            http://t.nyaatracker.com:80/announce,
            udp://47.ip-51-68-199.eu:6969/announce,
            udp://9.rarbg.me:2940,
            udp://9.rarbg.to:2820,
            udp://exodus.desync.com:6969/announce,
            udp://explodie.org:6969/announce,
            udp://ipv4.tracker.harry.lu:80/announce,
            udp://open.stealth.si:80/announce,
            udp://opentor.org:2710/announce,
            udp://opentracker.i2p.rocks:6969/announce,
            udp://retracker.lanta-net.ru:2710/announce,
            udp://tracker.cyberia.is:6969/announce,
            udp://tracker.dler.org:6969/announce,
            udp://tracker.ds.is:6969/announce,
            udp://tracker.internetwarriors.net:1337,
            udp://tracker.openbittorrent.com:6969/announce,
            udp://tracker.opentrackr.org:1337/announce,
            udp://tracker.tiny-vps.com:6969/announce,
            udp://tracker.torrent.eu.org:451/announce,
            udp://valakas.rollo.dnsabr.com:2710/announce,
            udp://www.torrent.eu.org:451/announce,
             ${fetchTrackers().split("\n").joinToString(",")}
    """.trimIndent()

    private fun buildHoster(stream: TorrentioStream, debridProvider: String?, animeTrackers: String): Hoster {
        val urlOrHash =
            if (debridProvider == "none") {
                val trackerList = animeTrackers.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString("&tr=")
                "magnet:?xt=urn:btih:${stream.infoHash}&dn=${stream.infoHash}&tr=$trackerList&index=${stream.fileIdx}"
            } else {
                stream.url ?: ""
            }
        val title = ((stream.name?.replace("Torrentio\n", "") ?: "") + "\n" + stream.title)
        return Hoster(hosterName = title, internalData = urlOrHash)
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val urlOrHash = hoster.internalData
        return listOf(
            Video(
                videoUrl = urlOrHash,
                videoTitle = hoster.hosterName,
            ),
        ).sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val isDub = preferences.getBoolean(IS_DUB_KEY, IS_DUB_DEFAULT)
        val isEfficient = preferences.getBoolean(IS_EFFICIENT_KEY, IS_EFFICIENT_DEFAULT)

        return sortedWith(
            compareBy(
                { Regex("\\[(.+?) download]").containsMatchIn(it.videoTitle) },
                { isDub && !it.videoTitle.contains("dubbed", true) },
                { isEfficient && !arrayOf("hevc", "265", "av1").any { q -> it.videoTitle.contains(q, true) } },
            ),
        )
    }

    private suspend fun fetchTrackers(): String {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt")
            .build()

        client.newCall(request).awaitSuccess().use { response ->
            if (!response.isSuccessful) throw ExtractionException("Unexpected code $response")
            return response.body.string().trim()
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = addTorrentioPreferences(screen)

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        }
    }
}
