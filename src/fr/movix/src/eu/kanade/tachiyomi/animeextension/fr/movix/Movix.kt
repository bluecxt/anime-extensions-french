package eu.kanade.tachiyomi.animeextension.fr.movix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.AnimeItem
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.TmdbDiscoverResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.Top10Response
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parallelMap
import fr.bluecxt.core.Source
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

class Movix : Source() {

    override val name = "Movix"

    override val baseUrl = "https://movix.tax"

    private val apiUrl = "https://api.movix.tax"

    override val lang = "fr"

    override val supportsLatest = true

    override val json: Json by injectLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:150.0) Gecko/20100101 Firefox/150.0")

    private val animeCache = mutableMapOf<String, AnimeItem>()

    companion object {
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://movix.tax"
        const val PREFIX_SEARCH = "id:"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = "URL de base"
            setDefaultValue(PREF_URL_DEFAULT)
            summary = baseUrl
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_URL_KEY, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)
    }

    private fun getAnimeId(url: String): String = URLEncoder.encode(url, "UTF-8")

    // = :::::::::::::::::::::::::: Popular :::::::::::::::::::::::::: =
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val response = client.newCall(GET("$apiUrl/api/top10/overview?type=anime", headers)).execute()
        val data = json.decodeFromString<Top10Response>(response.body.string())

        val animes = data.top10.map { item ->
            SAnime.create().apply {
                title = item.title
                thumbnail_url = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val encodedId = URLEncoder.encode(item.title, "UTF-8").replace("+", "%20")
                url = "/anime/${PREFIX_SEARCH}$encodedId"
                initialized = false
            }
        }
        return AnimesPage(animes, false)
    }

    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    // = :::::::::::::::::::::::::: Latest :::::::::::::::::::::::::: =
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val tmdbUrl = "https://api.themoviedb.org/3/discover/tv?api_key=f3d757824f08ea2cff45eb8f47ca3a1e&with_genres=&page=$page&language=fr-FR&vote_average_gte=0&sort_by=first_air_date.desc&with_watch_providers=283&watch_region=FR&with_release_type=2%7C3"
        val response = client.newCall(GET(tmdbUrl)).execute()
        val data = json.decodeFromString<TmdbDiscoverResponse>(response.body.string())

        val animes = data.results.mapNotNull { item ->
            val titleStr = item.name ?: item.title ?: return@mapNotNull null
            SAnime.create().apply {
                title = titleStr
                thumbnail_url = item.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                val encodedId = URLEncoder.encode(titleStr, "UTF-8").replace("+", "%20")
                url = "/anime/${PREFIX_SEARCH}$encodedId"
                initialized = false
            }
        }
        return AnimesPage(animes, data.results.isNotEmpty())
    } override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    // = :::::::::::::::::::::::::: Search :::::::::::::::::::::::::: =
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return AnimesPage(emptyList(), false)

        if (trimmedQuery.lowercase().startsWith(PREFIX_SEARCH)) {
            val id = trimmedQuery.substring(PREFIX_SEARCH.length).trim()
            val decodedUrl = java.net.URLDecoder.decode(id, "UTF-8")
            val name = decodedUrl.split("/").filter { it.isNotBlank() }.last()
            return fetchAndCache(name, decodedUrl)
        }

        return fetchAndCache(trimmedQuery)
    }

    private suspend fun fetchAndCache(query: String, targetUrl: String? = null): AnimesPage {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val response = client.newCall(GET("$apiUrl/anime/search/$encodedQuery?includeSeasons=true&includeEpisodes=true", headers)).execute()
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

    // = :::::::::::::::::::::::::: Details :::::::::::::::::::::::::: =
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val id = anime.url.substringAfter("/anime/").substringBefore("#").substringBefore("?")
        var item = animeCache[id]

        if (item == null) {
            val decodedUrl = java.net.URLDecoder.decode(id, "UTF-8")
            val name = decodedUrl.split("/").filter { it.isNotBlank() }.last()
            fetchAndCache(name, decodedUrl)
            item = animeCache[id]
        }

        if (item != null) {
            val tmdbMetadata = fetchTmdbMetadata(item.name)
            tmdbMetadata?.summary?.let { anime.description = it }
            tmdbMetadata?.posterUrl?.let { anime.thumbnail_url = it }
            tmdbMetadata?.author?.let { anime.author = it }
            tmdbMetadata?.artist?.let { anime.artist = it }

            if (item.seasons.size > 1) {
                anime.coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Seasons)
            } else {
                anime.coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
            }
        }

        anime.initialized = true
        return anime
    }

    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val id = anime.url.substringAfter("/anime/").substringBefore("#").substringBefore("?")
        val cleanId = id.removePrefix(PREFIX_SEARCH)
        val decodedName = java.net.URLDecoder.decode(cleanId, "UTF-8").split("/").filter { it.isNotBlank() }.last()
        val item = animeCache[id] ?: animeCache.values.firstOrNull { it.name.equals(decodedName, true) } ?: return emptyList()

        val siteSeasons = item.seasons.mapIndexed { index, season ->
            val seasonNum = index + 1
            val sTitle = season.name
            val sUrl = "/anime/$id#s$index"
            Triple(sTitle, sUrl, seasonNum)
        }

        return coreBuildSeasonList(item.name, siteSeasons, SAnime.UNKNOWN)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.substringAfter("/anime/").substringBefore("#").substringBefore("?")
        val cleanId = id.removePrefix(PREFIX_SEARCH)
        val decodedName = java.net.URLDecoder.decode(cleanId, "UTF-8").split("/").filter { it.isNotBlank() }.last()
        val item = animeCache[id] ?: animeCache.values.firstOrNull { it.name.equals(decodedName, true) } ?: return emptyList()

        val sIdxFromUrl = anime.url.substringAfter("#s", "").toIntOrNull()

        val seasonsToProcess = if (sIdxFromUrl != null) {
            listOf(item.seasons[sIdxFromUrl] to (sIdxFromUrl + 1))
        } else {
            item.seasons.mapIndexed { index, season -> season to (index + 1) }
        }

        val allEpisodes = mutableListOf<SEpisode>()
        for ((season, seasonNumber) in seasonsToProcess) {
            val tmdbSNum = season.name.replace("Saison ", "").toDoubleOrNull()?.toInt() ?: seasonNumber
            val tmdbMetadata = fetchTmdbMetadata(item.name, tmdbSNum)

            val rawEpisodes = season.episodes.map { ep ->
                SEpisode.create().apply {
                    name = ep.name
                    episode_number = ep.index.toFloat()
                    url = "/anime/$id?s=${seasonNumber - 1}&e=${ep.index - 1}"
                }
            }

            val mappedEpisodes = coreMapEpisodes(
                rawEpisodes = rawEpisodes,
                tmdbMetadata = tmdbMetadata,
                tmdbS0Metadata = null,
                offsets = Pair(0, 0),
                sNum = tmdbSNum,
                isMovie = anime.title.contains("Film", true) || season.name.contains("Film", true),
                isOav = season.name.contains("OAV", true) || season.name.contains("Special", true),
            )

            mappedEpisodes.forEach { it.scanlator = "Season $seasonNumber" }
            allEpisodes.addAll(mappedEpisodes)
        }

        return allEpisodes.reversed()
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
        val data = hoster.internalData.split("|")
        val players = json.decodeFromString<List<String>>(data[0])
        val langLabel = data[1]

        val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
        val sibnetExtractor by lazy { SibnetExtractor(client) }
        val vkExtractor by lazy { VkExtractor(client, headers) }
        val vidMolyExtractor by lazy { VidMolyExtractor(client) }
        val filemoonExtractor by lazy { FilemoonExtractor(client) }

        return players.parallelMap { playerUrl ->
            try {
                val serverName = when {
                    playerUrl.contains("sibnet.ru") -> "Sibnet"
                    playerUrl.contains("vidmoly") -> "Vidmoly"
                    playerUrl.contains("vk.com") -> "VK"
                    playerUrl.contains("sendvid") -> "Sendvid"
                    playerUrl.contains("filemoon") -> "Filemoon"
                    else -> "Serveur"
                }
                val prefix = "($langLabel) $serverName - "

                when {
                    playerUrl.contains("sibnet.ru") -> sibnetExtractor.videosFromUrl(playerUrl, prefix)
                    playerUrl.contains("vidmoly") -> vidMolyExtractor.videosFromUrl(playerUrl, prefix)
                    playerUrl.contains("vk.com") -> vkExtractor.videosFromUrl(playerUrl, prefix)
                    playerUrl.contains("sendvid") -> sendvidExtractor.videosFromUrl(playerUrl, prefix)
                    playerUrl.contains("filemoon") -> filemoonExtractor.videosFromUrl(playerUrl, prefix)
                    else -> emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }.flatten().map { video ->
            val updatedHeaders = (video.headers ?: Headers.Builder().build()).newBuilder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .set("Referer", baseUrl)
                .build()
            Video(
                videoUrl = video.videoUrl,
                videoTitle = coreCleanQuality(video.videoTitle),
                headers = updatedHeaders,
                subtitleTracks = video.subtitleTracks,
                audioTracks = video.audioTracks,
            )
        }.coreSortVideos()
    }
}
