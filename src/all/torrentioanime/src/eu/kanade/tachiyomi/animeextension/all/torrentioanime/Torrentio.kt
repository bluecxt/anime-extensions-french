package eu.kanade.tachiyomi.animeextension.all.torrentioanime

import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AniZipEpisode
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AniZipMappings
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AniZipResponse
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AnilistMedia
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AnilistMeta
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AnilistMetaLatest
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AnilistStreamingEpisode
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.DetailsById
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.StreamDataTorrent
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.TorrentioStream
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.model.ExtractionException
import fr.bluecxt.core.source.Source
import keiyoushi.utils.bodyString
import keiyoushi.utils.tryParse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

/** Taille de page demandée à AniList. */
private const val DEFAULT_PAGE_SIZE = 30

class Torrentio : Source() {

    override val name = "Torrentio Anime (Torrent / Debrid)"

    override val baseUrl = "https://torrentio.strem.fun"

    override val lang = "all"

    override val supportsLatest = true

    // ============================== Anilist API Request ===================
    private fun makeGraphQLRequest(query: String, variables: String): Request {
        val jsonBody = """{"query": "${query.replace("\n", " ").replace("\"", "\\\"")}", "variables": $variables}"""
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        return POST("https://graphql.anilist.co", body = jsonBody)
    }

    private fun parseSearchJson(jsonLine: String?, isLatestQuery: Boolean = false): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val metaData: Any = parseSearchMetaData(jsonData, isLatestQuery)

        val mediaList = extractMediaList(metaData)

        val hasNextPage: Boolean = extractHasNextPage(metaData)

        val animeList = mediaList
            .filterNot { (it?.countryOfOrigin == "CN" || it?.isAdult == true) && isLatestQuery }
            .map { media -> searchMediaToSAnime(media) }

        return AnimesPage(animeList, hasNextPage)
    }

    private fun parseSearchMetaData(jsonData: String, isLatestQuery: Boolean): Any = if (!isLatestQuery) {
        json.decodeFromString<AnilistMeta>(jsonData)
    } else {
        json.decodeFromString<AnilistMetaLatest>(jsonData)
    }

    private fun extractMediaList(metaData: Any): List<AnilistMedia?> = when (metaData) {
        is AnilistMeta -> metaData.data?.page?.media.orEmpty()
        is AnilistMetaLatest -> metaData.data?.page?.airingSchedules.orEmpty().map { it.media }
        else -> emptyList()
    }

    private fun extractHasNextPage(metaData: Any): Boolean = when (metaData) {
        is AnilistMeta -> metaData.data?.page?.pageInfo?.hasNextPage ?: false
        is AnilistMetaLatest -> metaData.data?.page?.pageInfo?.hasNextPage ?: false
        else -> false
    }

    private fun searchMediaToSAnime(media: AnilistMedia?): SAnime = SAnime.create().apply {
        url = media?.id.toString()
        title = searchResultTitle(media)
        thumbnail_url = media?.coverImage?.extraLarge
        description = media?.description
            ?.replace(Regex("<br><br>"), "\n")
            ?.replace(Regex("<.*?>"), "")
            ?: "No Description"

        status = anilistStatusToSAnime(media?.status)

        // Extracting tags
        val tagsList = media?.tags?.mapNotNull { it.name }.orEmpty()
        // Extracting genres
        val genresList = media?.genres.orEmpty()
        genre = (tagsList + genresList).toSet().sorted().joinToString()

        // Extracting studios
        val studiosList = media?.studios?.nodes?.mapNotNull { it.name }.orEmpty()
        author = studiosList.sorted().joinToString()

        initialized = true
    }

    private fun searchResultTitle(media: AnilistMedia?): String = when (preferences.getString(PREF_TITLE_KEY, "romaji")) {
        "romaji" -> media?.title?.romaji.toString()
        "english" -> (media?.title?.english?.takeIf { it.isNotBlank() } ?: media?.title?.romaji).toString()
        "native" -> media?.title?.native.toString()
        else -> ""
    }

    private fun anilistStatusToSAnime(status: String?): Int = when (status) {
        "RELEASING" -> SAnime.ONGOING
        "FINISHED" -> SAnime.COMPLETED
        "HIATUS" -> SAnime.ON_HIATUS
        "NOT_YET_RELEASED" -> SAnime.LICENSED
        else -> SAnime.UNKNOWN
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val variables = """
            {
                "page": $page,
                "perPage": 30,
                "sort": "TRENDING_DESC",
                "status": ["FINISHED", "RELEASING"]
            }
        """.trimIndent()

        return makeGraphQLRequest(anilistQuery(), variables)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        return parseSearchJson(jsonData)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val variables = """
            {
                "page": $page,
                "perPage": 30,
                "sort": "TIME_DESC"
            }
        """.trimIndent()

        return makeGraphQLRequest(anilistLatestQuery(), variables)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        return parseSearchJson(jsonData, true)
    }

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
        val params = AniListFilters.getSearchParameters(filters)
        val variablesObject = buildJsonObject {
            put("page", page)
            put("perPage", DEFAULT_PAGE_SIZE)
            put("sort", params.sort)
            if (query.isNotBlank()) put("search", query)

            if (params.genres.isNotEmpty()) {
                putJsonArray("genres") {
                    params.genres.forEach { add(it) }
                }
            }

            if (params.format.isNotEmpty()) {
                putJsonArray("format") {
                    params.format.forEach { add(it) }
                }
            }

            if (params.season.isBlank() && params.year.isNotBlank()) {
                put("year", "${params.year}%")
            }

            if (params.season.isNotBlank() && params.year.isBlank()) {
                error("Year cannot be blank if season is set")
            }

            if (params.season.isNotBlank() && params.year.isNotBlank()) {
                put("season", params.season)
                put("seasonYear", params.year)
            }

            if (params.status.isNotBlank()) {
                putJsonArray("status") {
                    params.status.forEach { add(it.toString()) }
                }
            }
        }

        val variables = json.encodeToString(variablesObject)

        return makeGraphQLRequest(anilistQuery(), variables)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AniListFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val metaData = fetchDetailsMedia(anime) ?: return anime

        anime.title = detailsTitle(metaData) ?: anime.title

        anime.thumbnail_url = metaData.coverImage?.extraLarge ?: anime.thumbnail_url

        anime.description = buildDetailsDescription(metaData)

        anime.status = anilistStatusToSAnime(metaData.status)

        // Extracting tags, genres, and studios
        applyTagsGenresAndStudios(anime, metaData)

        return anime
    }

    private suspend fun fetchDetailsMedia(anime: SAnime): AnilistMedia? {
        val anilistId = anime.url
        val variables = """{"id": $anilistId}"""

        return runCatching {
            json.decodeFromString<DetailsById>(client.newCall(makeGraphQLRequest(getDetailsQuery(), variables)).awaitSuccess().bodyString())
        }.getOrNull()?.data?.media
    }

    private fun detailsTitle(metaData: AnilistMedia): String? {
        val title = metaData.title ?: return null
        return when (preferences.getString(PREF_TITLE_KEY, "romaji")) {
            "romaji" -> title.romaji
            "english" -> (title.english?.takeIf { it.isNotBlank() } ?: title.romaji).toString()
            "native" -> title.native
            else -> ""
        }
    }

    private fun buildDetailsDescription(metaData: AnilistMedia): String = buildString {
        metaData.description?.let {
            append(
                Jsoup.parseBodyFragment(
                    it.replace("<br>\n", "br2n")
                        .replace("<br>", "br2n")
                        .replace("\n", "br2n"),
                ).text().replace("br2n", "\n"),
            )
        }
        append("\n\n")
        if (!(metaData.season == null && metaData.seasonYear == null)) {
            append("Release: ${ metaData.season ?: ""} ${ metaData.seasonYear ?: ""}")
        }
        metaData.format?.let { append("\nType: $it") }
        metaData.episodes?.let { append("\nTotal Episode Count: $it") }
    }.trim()

    private fun applyTagsGenresAndStudios(anime: SAnime, metaData: AnilistMedia) {
        val tagsList = metaData.tags?.mapNotNull { it.name } ?: emptyList()
        val genresList = metaData.genres ?: emptyList()
        val studiosList = metaData.studios?.nodes?.mapNotNull { it.name } ?: emptyList()

        anime.genre = (tagsList + genresList).toSet().sorted().joinToString()
        anime.author = studiosList.sorted().joinToString()
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request = GET("https://api.ani.zip/mappings?anilist_id=${anime.url}")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(episodeListRequest(anime)).awaitSuccess()
        val responseString = response.body.string()
        val aniZipResponse = json.decodeFromString<AniZipResponse>(responseString)

        val anilistDetails = fetchDetailsMedia(anime)
        val streamingEpisodes = anilistDetails?.streamingEpisodes.orEmpty()

        return when (aniZipResponse.mappings?.type) {
            "TV", "ONA", "OVA" -> buildSeriesEpisodes(aniZipResponse, aniZipResponse.mappings, streamingEpisodes)
            "MOVIE" -> buildMovieEpisodes(aniZipResponse, aniZipResponse.mappings, anilistDetails)
            else -> emptyList()
        }
    }

    private fun buildSeriesEpisodes(
        aniZipResponse: AniZipResponse,
        mappings: AniZipMappings,
        streamingEpisodes: List<AnilistStreamingEpisode>,
    ): List<SEpisode> = aniZipResponse.episodes
        ?.let { episodes ->
            if (preferences.getBoolean(UPCOMING_EP_KEY, UPCOMING_EP_DEFAULT)) {
                episodes
            } else {
                episodes.filter { (_, episode) -> episode?.airDate.let(DATE_FORMATTER::tryParse) <= System.currentTimeMillis() }
            }
        }
        ?.mapNotNull { (_, episode) -> buildSeriesEpisode(episode, mappings, streamingEpisodes) }
        .orEmpty()
        .asReversed()

    private fun buildSeriesEpisode(
        episode: AniZipEpisode?,
        mappings: AniZipMappings,
        streamingEpisodes: List<AnilistStreamingEpisode>,
    ): SEpisode? {
        val episodeNumber = runCatching { episode?.episode?.toFloat() }.getOrNull() ?: return null

        val title = episode?.title?.get("en")

        return SEpisode.create().apply {
            episode_number = episodeNumber
            url = "/stream/series/kitsu:${mappings.kitsuId}:${String.format(Locale.ENGLISH, "%.0f", episodeNumber)}.json"
            date_upload = episode?.airDate.let(DATE_FORMATTER::tryParse)
            name = if (title == null) "Episode ${episode?.episode}" else "Episode ${episode.episode}: $title"
            scanlator = episode?.airDate.let(DATE_FORMATTER::tryParse).takeIf { it > System.currentTimeMillis() }?.let { "Upcoming" } ?: ""

            // Match thumbnail from Anilist
            preview_url = findStreamingThumbnail(streamingEpisodes, episode?.episode) ?: episode?.image
        }
    }

    private fun findStreamingThumbnail(
        streamingEpisodes: List<AnilistStreamingEpisode>,
        episodeNumber: String?,
    ): String? = streamingEpisodes.find { al ->
        al.title?.contains(Regex("(?i)Episode\\s*$episodeNumber\\b")) == true ||
            al.title?.startsWith("EP $episodeNumber -") == true ||
            al.title?.startsWith("$episodeNumber -") == true
    }?.thumbnail

    private fun buildMovieEpisodes(
        aniZipResponse: AniZipResponse,
        mappings: AniZipMappings,
        anilistDetails: AnilistMedia?,
    ): List<SEpisode> {
        val dateUpload = if (!aniZipResponse.episodes.isNullOrEmpty()) {
            aniZipResponse.episodes["1"]?.airDate.let(DATE_FORMATTER::tryParse)
        } else {
            0L
        }

        return listOf(
            SEpisode.create().apply {
                episode_number = 1.0F
                url = "/stream/movie/kitsu:${mappings.kitsuId}.json"
                name = "Movie"
                date_upload = dateUpload
                preview_url = anilistDetails?.coverImage?.extraLarge
            },
        ).asReversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val mainURL = buildStreamUrl(episode)

        val responseString = client.newCall(GET(mainURL)).awaitSuccess().body.string()
        val streamList = json.decodeFromString<StreamDataTorrent>(responseString)
        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")

        val animeTrackers = buildAnimeTrackers()

        return streamList.streams?.map { stream ->
            val urlOrHash = streamUrlOrHash(stream, animeTrackers, debridProvider)
            val title = ((stream.name?.replace("Torrentio\n", "") ?: "") + "\n" + stream.title)
            Hoster(hosterName = title, internalData = urlOrHash)
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

    private suspend fun buildAnimeTrackers(): String = """http://nyaa.tracker.wf:7777/announce,
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

    private fun streamUrlOrHash(stream: TorrentioStream, animeTrackers: String, debridProvider: String?): String = if (debridProvider == "none") {
        val trackerList = animeTrackers.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString("&tr=")
        "magnet:?xt=urn:btih:${stream.infoHash}&dn=${stream.infoHash}&tr=$trackerList&index=${stream.fileIdx}"
    } else {
        stream.url ?: ""
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

    private val codecPreferences
        get() = preferences.getStringSet(PREF_CODEC_KEY, PREF_CODEC_DEFAULT) ?: setOf()

    override fun List<Video>.sortVideos(): List<Video> {
        val isDub = preferences.getBoolean(IS_DUB_KEY, IS_DUB_DEFAULT)
        val isEfficient = preferences.getBoolean(IS_EFFICIENT_KEY, IS_EFFICIENT_DEFAULT)

        return if (codecPreferences.isNotEmpty()) {
            // Filter to only show videos matching selected codecs
            filter { video ->
                video.detectCodec() in codecPreferences
            }.sortedWith(
                compareBy(
                    { Regex("\\[(.+?) download]").containsMatchIn(it.videoTitle) },
                    { isDub && !it.videoTitle.contains("dubbed", true) },
                ),
            )
        } else {
            // If no codec preferences, use old sorting logic
            sortedWith(
                compareBy(
                    { Regex("\\[(.+?) download]").containsMatchIn(it.videoTitle) },
                    { isDub && !it.videoTitle.contains("dubbed", true) },
                    { isEfficient && !arrayOf("hevc", "265", "av1").any { q -> it.videoTitle.contains(q, true) } },
                ),
            )
        }
    }

    private fun Video.detectCodec(): String = when {
        videoTitle.contains("264", true) -> "x264"
        videoTitle.contains("265", true) || videoTitle.contains("hevc", true) -> "x265"
        videoTitle.contains("av1", true) -> "av1"
        videoTitle.contains("vp9", true) -> "vp9"
        else -> "other"
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        addTorrentioAnimePreferences(screen)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }
    }
}
