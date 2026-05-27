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
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.embed4meextractor.Embed4meExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
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

    private var dynamicBaseUrl: String? = null

    override val baseUrl: String
        get() {
            if (dynamicBaseUrl != null) return dynamicBaseUrl!!

            val prefUrl = preferences.getString(PREF_URL_KEY, "")?.trim()
            if (!prefUrl.isNullOrEmpty() && prefUrl != "https://movix.online") {
                dynamicBaseUrl = prefUrl
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
                preferences.edit().putString(PREF_URL_KEY, newDomain).apply()
                dynamicBaseUrl = newDomain
                return newDomain
            }
        } catch (e: Exception) {
            // Ignore and fallback
        }
        return PREF_URL_DEFAULT
    }

    private val domain: String
        get() = baseUrl.toHttpUrl().host

    private val apiUrl: String
        get() = "https://api.$domain"

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
        private const val PREF_URL_DEFAULT = "https://movix.cloud"
        const val PREFIX_SEARCH = "id:"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = "URL de base"
            setDefaultValue("")
            summary = "Laissez vide pour trouver automatiquement le domaine actif via movix.online. Actuel: $baseUrl"
            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                preferences.edit().putString(PREF_URL_KEY, newUrl).apply()
                dynamicBaseUrl = null // Force refresh
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

        // We must fetch the real URL from Movix API to ensure getAnimeDetails works
        val animes = data.top10.parallelMap { item ->
            try {
                val encodedQuery = URLEncoder.encode(item.title, "UTF-8").replace("+", "%20")
                android.util.Log.d("MovixDebug", "Popular: Fetching URL for ${item.title} -> $encodedQuery")
                val searchRes = client.newCall(GET("$apiUrl/anime/search/$encodedQuery?includeSeasons=false&includeEpisodes=false", headers)).execute()
                val results = json.decodeFromString<List<AnimeItem>>(searchRes.body.string())
                val exactMatch = results.firstOrNull { it.name.equals(item.title, true) } ?: results.firstOrNull()

                android.util.Log.d("MovixDebug", "Popular: Results for ${item.title} -> Found: ${exactMatch != null}")
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

    // = :::::::::::::::::::::::::: Latest :::::::::::::::::::::::::: =
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val tmdbUrl = "https://api.themoviedb.org/3/discover/tv?api_key=f3d757824f08ea2cff45eb8f47ca3a1e&with_genres=&page=$page&language=fr-FR&vote_average_gte=0&sort_by=first_air_date.desc&with_watch_providers=283&watch_region=FR&with_release_type=2%7C3"
        val response = client.newCall(GET(tmdbUrl)).execute()
        val data = json.decodeFromString<TmdbDiscoverResponse>(response.body.string())

        val animes = data.results.parallelMap { item ->
            try {
                val titleStr = item.name ?: item.title ?: return@parallelMap null
                val encodedQuery = URLEncoder.encode(titleStr, "UTF-8").replace("+", "%20")
                android.util.Log.d("MovixDebug", "Latest: Fetching URL for $titleStr -> $encodedQuery")

                val searchRes = client.newCall(GET("$apiUrl/anime/search/$encodedQuery?includeSeasons=false&includeEpisodes=false", headers)).execute()
                val results = json.decodeFromString<List<AnimeItem>>(searchRes.body.string())
                val exactMatch = results.firstOrNull { it.name.equals(titleStr, true) } ?: results.firstOrNull()

                android.util.Log.d("MovixDebug", "Latest: Results for $titleStr -> Found: ${exactMatch != null}")

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
                android.util.Log.d("MovixDebug", "Latest Error: ${e.message}")
                null
            }
        }.filterNotNull()

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
            // DO NOT filter by targetUrl if the id is just the title (like from Popular/Latest)
            val target = if (decodedUrl.startsWith("http")) decodedUrl else null
            return fetchAndCache(name, target)
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
        android.util.Log.d("MovixDebug", "-----------------------------------------")
        android.util.Log.d("MovixDebug", "getAnimeDetails START for: ${anime.title}")
        android.util.Log.d("MovixDebug", "Incoming URL: ${anime.url}")

        val id = anime.url.substringAfter("/anime/").substringBefore("#").substringBefore("?")
        android.util.Log.d("MovixDebug", "Parsed ID (Cache Key): $id")

        var item = animeCache[id]
        android.util.Log.d("MovixDebug", "Item found in cache? ${item != null}")

        if (item == null) {
            val decodedUrl = java.net.URLDecoder.decode(id, "UTF-8")
            val name = decodedUrl.split("/").filter { it.isNotBlank() }.last().replace("-", " ")
            android.util.Log.d("MovixDebug", "Cache miss! Forcing fetchAndCache for name: $name, decodedUrl: $decodedUrl")
            fetchAndCache(name, decodedUrl)
            item = animeCache[id]
            android.util.Log.d("MovixDebug", "Item found after forced fetch? ${item != null}")
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
        val item = animeCache[id] ?: animeCache.values.firstOrNull { it.name.equals(decodedName, true) }
        if (item == null) {
            return emptyList()
        }

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
        android.util.Log.d("MovixDebug", "getVideoList START for hoster: ${hoster.hosterName}")
        val data = hoster.internalData.split("|")
        val players = json.decodeFromString<List<String>>(data[0])
        android.util.Log.d("MovixDebug", "Raw players list: $players")
        val langLabel = data[1]

        val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
        val sibnetExtractor by lazy { SibnetExtractor(client) }
        val vkExtractor by lazy { VkExtractor(client, headers) }
        val vidMolyExtractor by lazy { VidMolyExtractor(client, headers) }
        val filemoonExtractor by lazy { FilemoonExtractor(client) }
        val doodExtractor by lazy { eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor(client) }
        val streamTapeExtractor by lazy { eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor(client) }
        val vidoExtractor by lazy { eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor(client) }
        val voeExtractor by lazy { eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor(client, headers) }
        val embed4meExtractor by lazy { Embed4meExtractor(client) }

        return players.parallelMap { playerUrl ->
            try {
                val serverName = when {
                    playerUrl.contains("sibnet.ru", true) -> "Sibnet"
                    playerUrl.contains("vidmoly", true) -> "Vidmoly"
                    playerUrl.contains("vk.com", true) -> "VK"
                    playerUrl.contains("sendvid", true) -> "Sendvid"
                    playerUrl.contains("filemoon", true) -> "Filemoon"
                    playerUrl.contains("dood", true) -> "DoodStream"
                    playerUrl.contains("streamtape", true) || playerUrl.contains("shavetape", true) -> "StreamTape"
                    playerUrl.contains("vidoza", true) -> "Vidoza"
                    playerUrl.contains("voe", true) -> "Voe"
                    playerUrl.contains("embed4me", true) || playerUrl.contains("seekstreaming", true) -> "Embed4me"
                    else -> "Serveur"
                }
                val prefix = "($langLabel) $serverName - "

                val extracted = when {
                    playerUrl.contains("sibnet.ru", true) -> sibnetExtractor.videosFromUrl(playerUrl, prefix)

                    playerUrl.contains("vidmoly", true) -> vidMolyExtractor.videosFromUrl(playerUrl, prefix)

                    playerUrl.contains("vk.com", true) -> vkExtractor.videosFromUrl(playerUrl, prefix)

                    playerUrl.contains("sendvid", true) -> sendvidExtractor.videosFromUrl(playerUrl, prefix)

                    playerUrl.contains("filemoon", true) -> filemoonExtractor.videosFromUrl(playerUrl, prefix)

                    playerUrl.contains("dood", true) -> doodExtractor.videosFromUrl(playerUrl, prefix)

                    playerUrl.contains("streamtape", true) || playerUrl.contains("shavetape", true) -> streamTapeExtractor.videoFromUrl(playerUrl, prefix)?.let { listOf(it) } ?: emptyList()

                    playerUrl.contains("vidoza", true) -> vidoExtractor.videosFromUrl(playerUrl, prefix)

                    playerUrl.contains("voe", true) -> voeExtractor.videosFromUrl(playerUrl, prefix)

                    playerUrl.contains("embed4me", true) || playerUrl.contains("seekstreaming", true) -> embed4meExtractor.videosFromUrl(playerUrl, prefix)

                    else -> {
                        android.util.Log.d("MovixDebug", "No extractor found for: $playerUrl")
                        emptyList()
                    }
                }
                if (extracted.isEmpty() && serverName != "Serveur") {
                    android.util.Log.d("MovixDebug", "Extractor returned empty for: $serverName -> $playerUrl")
                }
                extracted
            } catch (e: Exception) {
                android.util.Log.d("MovixDebug", "Exception extracting $playerUrl : ${e.message}")
                emptyList()
            }
        }.flatten().map { video ->
            val updatedHeaders = (video.headers ?: Headers.Builder().build()).newBuilder().apply {
                set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                if (video.headers?.get("Referer") == null) set("Referer", "$baseUrl/")
            }.build()
            Video(
                videoUrl = video.videoUrl,
                videoTitle = coreCleanQuality(video.videoTitle),
                headers = updatedHeaders,
                subtitleTracks = video.subtitleTracks,
                audioTracks = video.audioTracks,
            )
        }.coreSortVideos().also {
            android.util.Log.d("MovixDebug", "Final sorted videos list: ${it.map { v -> v.videoTitle + " -> " + v.videoUrl }}")
        }
    }
}
