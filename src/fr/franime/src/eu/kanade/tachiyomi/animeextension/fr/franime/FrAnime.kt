package eu.kanade.tachiyomi.animeextension.fr.franime

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Anime
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
import fr.bluecxt.core.Source
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class FrAnime : Source() {

    override val name = "FrAnime"

    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!
    }

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (url.contains("nautiljon.com")) {
                val newRequest = request.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Dest", "image")
                    .build()
                chain.proceed(newRequest)
            } else {
                chain.proceed(request)
            }
        }
        .build()

    private val domain: String
        get() = baseUrl.toHttpUrl().host

    private val baseApiUrl: String
        get() = "https://api.$domain/api"

    private val baseApiAnimeUrl: String
        get() = "$baseApiUrl/anime"

    override val lang = "fr"

    override val supportsLatest = true

    // REVERTED TO VERSION 38 HEADERS (FIXES REDIRECTION BUG)
    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    override val json: Json by injectLazy()

    companion object {
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://franime.fr"

        private const val PREF_VOICES_KEY = "preferred_voices"
        private const val PREF_VOICES_TITLE = "Préférence des voix"
        private val VOICES_ENTRIES = arrayOf("Préférer VOSTFR", "Préférer VF")
        private val VOICES_VALUES = arrayOf("VOSTFR", "VF")
        private const val PREF_VOICES_DEFAULT = "VOSTFR"
        const val PREFIX_SEARCH = "id:"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = "URL de base"
            setDefaultValue(PREF_URL_DEFAULT)
            summary = baseUrl
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_URL_KEY, newValue as String).commit()
                true
            }
        }.also(screen::addPreference)

        val videoLanguagePref = ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = PREF_VOICES_TITLE
            entries = VOICES_ENTRIES
            entryValues = VOICES_VALUES
            setDefaultValue(PREF_VOICES_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
                true
            }
        }
        screen.addPreference(videoLanguagePref)
    }

    private val database by lazy {
        client.newCall(GET("$baseApiUrl/animes/", headers)).execute()
            .body.string()
            .let { json.decodeFromString<List<Anime>>(it) }
            .distinctBy { it.sourceUrl.ifEmpty { it.id.toString() } }
    }

    // = :::::::::::::::::::::::::: Popular :::::::::::::::::::::::::: =
    override suspend fun getPopularAnime(page: Int) = pagesToAnimesPage(database.sortedByDescending { it.note }, page)

    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()

    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    // = :::::::::::::::::::::::::: Latest :::::::::::::::::::::::::: =
    override suspend fun getLatestUpdates(page: Int) = pagesToAnimesPage(database.reversed(), page)

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    // = :::::::::::::::::::::::::: Search :::::::::::::::::::::::::: =
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val pages = database.filter {
            it.title.contains(query, true) ||
                it.originalTitle.contains(query, true) ||
                it.titlesAlt.en?.contains(query, true) == true ||
                it.titlesAlt.enJp?.contains(query, true) == true ||
                it.titlesAlt.jaJp?.contains(query, true) == true ||
                titleToUrl(it.originalTitle).contains(query)
        }
        return pagesToAnimesPage(pages, page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    // = :::::::::::::::::::::::::: Details :::::::::::::::::::::::::: =
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val stem = (baseUrl + anime.url).toHttpUrl().encodedPathSegments.last()
        val animeData = database.firstOrNull { titleToUrl(it.originalTitle) == stem }

        // Fetch HD Metadata from TMDB using title or original title
        val searchTitle = animeData?.title?.takeIf { it.isNotBlank() } ?: animeData?.originalTitle ?: anime.title
        val tmdbMetadata = fetchTmdbMetadata(searchTitle)

        tmdbMetadata?.posterUrl?.let { anime.thumbnail_url = it }
        tmdbMetadata?.summary?.let { anime.description = it }

        return anime
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // = :::::::::::::::::::::::::: Episodes :::::::::::::::::::::::::: =
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = (baseUrl + anime.url).toHttpUrl()
        val stem = url.encodedPathSegments.last()
        val animeData = database.first { titleToUrl(it.originalTitle) == stem }

        val searchTitle = animeData.title.takeIf { it.isNotBlank() } ?: animeData.originalTitle

        var globalEpisodeNumber = 1f
        val episodes = animeData.seasons.flatMapIndexed { sIndex, season ->
            val seasonNum = sIndex + 1
            val tmdbMetadata = fetchTmdbMetadata(searchTitle, seasonNum)

            season.episodes.mapIndexedNotNull { eIndex, episode ->
                val hasPlayers = episode.languages.vo.players.isNotEmpty() || episode.languages.vf.players.isNotEmpty()
                if (!hasPlayers) return@mapIndexedNotNull null

                SEpisode.create().apply {
                    val epNumber = eIndex + 1
                    setUrlWithoutDomain(anime.url + "?s=$seasonNum&ep=$epNumber")

                    val format = animeData.format.uppercase()
                    var epTitle = episode.title?.trim() ?: ""

                    // Clean site title: replace French 'Épisode' with English 'Episode'
                    epTitle = epTitle.replace("Épisode", "Episode", true)

                    // Metadata from TMDB Engine
                    val epMeta = tmdbMetadata?.episodeSummaries?.get(epNumber)
                    val tmdbName = epMeta?.first

                    // GEMINI.md Naming Rules
                    name = when {
                        format == "FILM" -> if (season.episodes.size > 1) "Film $epNumber" else "Film"

                        epTitle.contains("Episode", true) -> {
                            // If site title is generic 'Episode X', try to use TMDB title
                            if (tmdbName != null) "Episode $epNumber - $tmdbName" else epTitle
                        }

                        epTitle.isBlank() || epTitle.equals("Unknown", true) -> {
                            if (tmdbName != null) "Episode $epNumber - $tmdbName" else "Episode $epNumber"
                        }

                        else -> "Episode $epNumber - $epTitle"
                    }

                    episode_number = globalEpisodeNumber++
                    scanlator = "Season $seasonNum"

                    preview_url = epMeta?.second
                    summary = epMeta?.third
                }
            }
        }
        return episodes.sortedByDescending { it.episode_number }
    }

    // = :::::::::::::::::::::::::: Video Links :::::::::::::::::::::::::: =
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val url = (baseUrl + episode.url).toHttpUrl()
        val seasonNumber = url.queryParameter("s")?.toIntOrNull() ?: 1
        val episodeNumber = url.queryParameter("ep")?.toIntOrNull() ?: 1
        val animeData = database.first { titleToUrl(it.originalTitle) == url.encodedPathSegments.last() }
        val episodeData = animeData.seasons[seasonNumber - 1].episodes[episodeNumber - 1]

        val hosters = mutableListOf<Hoster>()

        episodeData.languages.vo.players.withIndex().forEach { (index, playerName) ->
            hosters.add(Hoster(hosterName = "VOSTFR - $playerName", internalData = "${animeData.id}|${seasonNumber - 1}|${episodeNumber - 1}|vo|$index|$playerName"))
        }
        episodeData.languages.vf.players.withIndex().forEach { (index, playerName) ->
            hosters.add(Hoster(hosterName = "VF - $playerName", internalData = "${animeData.id}|${seasonNumber - 1}|${episodeNumber - 1}|vf|$index|$playerName"))
        }

        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|")
        val animeId = data[0]
        val seasonIdx = data[1]
        val episodeIdx = data[2]
        val lang = data[3]
        val playerIdx = data[4]
        val playerName = data[5]

        val videoBaseUrl = "$baseApiAnimeUrl/$animeId/$seasonIdx/$episodeIdx"
        val langLabel = if (lang == "vo") "VOSTFR" else "VF"

        val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
        val sibnetExtractor by lazy { SibnetExtractor(client) }
        val vkExtractor by lazy { VkExtractor(client, headers) }
        val vidMolyExtractor by lazy { VidMolyExtractor(client) }
        val filemoonExtractor by lazy { FilemoonExtractor(client) }

        val responseBody = client.newCall(GET("$videoBaseUrl/$lang/$playerIdx", headers)).execute().body.string()

        // TON SYSTÈME DE DÉCHIFFRAGE ORIGINAL (RÉTABLI)
        val playerUrl = if (responseBody.contains("watch2")) {
            val uri = responseBody.toHttpUrl()
            decryptFrAnime(uri.queryParameter("a") ?: "")
                ?: decryptFrAnime(uri.queryParameter("b") ?: "")
                ?: decryptFrAnime(uri.queryParameter("c") ?: "") ?: ""
        } else {
            responseBody
        }

        val server = when (playerName.lowercase()) {
            "sendvid" -> "Sendvid"
            "sibnet" -> "Sibnet"
            "vk" -> "VK"
            "vidmoly" -> "Vidmoly"
            "filemoon" -> "Filemoon"
            else -> playerName.replaceFirstChar { it.uppercase() }
        }
        val prefix = "($langLabel) $server - "

        val videos = when (playerName.lowercase()) {
            "sendvid" -> sendvidExtractor.videosFromUrl(playerUrl, prefix)
            "sibnet" -> sibnetExtractor.videosFromUrl(playerUrl, prefix)
            "vk" -> vkExtractor.videosFromUrl(playerUrl, prefix)
            "vidmoly" -> vidMolyExtractor.videosFromUrl(playerUrl, prefix)
            "filemoon" -> filemoonExtractor.videosFromUrl(playerUrl, prefix)
            else -> emptyList()
        }

        return videos.map {
            Video(videoUrl = it.videoUrl, videoTitle = cleanQuality(it.videoTitle), headers = it.headers, subtitleTracks = it.subtitleTracks, audioTracks = it.audioTracks)
        }.sortVideos()
    }

    private val qualityCleanRegex = Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s")
    private val qualityResRegex = Regex("\\s*\\(\\d+x\\d+\\)")
    private val qualityServerRegex = Regex("(?i)(?:Sendvid|Sibnet|VK|Vidmoly|Filemoon):default")

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(qualityCleanRegex, "")
            .replace(qualityResRegex, "")
            .replace(qualityServerRegex, "")
            .replace(" - - ", " - ")
            .trim()
            .removeSuffix("-")
            .trim()

        val servers = listOf("Vidmoly", "Sibnet", "Sendvid", "VK", "Filemoon")
        for (server in servers) {
            val pattern = Regex("(?i)$server\\s*-\\s*$server")
            cleaned = cleaned.replace(pattern, server)
        }
        return cleaned
    }

    private val pQualityRegex = Regex("""(\d+)p""")

    override fun List<Video>.sortVideos(): List<Video> {
        val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(prefVoice, true) },
                {
                    pQualityRegex.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
            ),
        ).reversed()
    }

    // = :::::::::::::::::::::::::: Utilities :::::::::::::::::::::::::: =
    private fun pagesToAnimesPage(pages: List<Anime>, page: Int): AnimesPage {
        val chunks = pages.chunked(50)
        val hasNextPage = chunks.size > page
        val entries = pageToSAnimes(chunks.getOrNull(page - 1) ?: emptyList())
        return AnimesPage(entries, hasNextPage)
    }

    private val titleRegex by lazy { Regex("[^A-Za-z0-9 ]") }
    private fun titleToUrl(title: String) = titleRegex.replace(title, "").replace(" ", "-").lowercase()

    private fun pageToSAnimes(page: List<Anime>): List<SAnime> = page.map { anime ->
        SAnime.create().apply {
            title = anime.title
            thumbnail_url = anime.poster
            genre = anime.genres.joinToString()
            status = parseStatus(anime.status, anime.seasons.size, anime.seasons.size)
            description = buildString {
                if (anime.startDate != null && anime.startDate.isNotBlank()) append("Diffusion : ${anime.startDate}\n")
                if (anime.format.isNotBlank()) append("Format : ${anime.format}\n")
                append("Note : ${anime.note} / 10\n\n")
                append(anime.description)
            }
            setUrlWithoutDomain("/anime/${titleToUrl(anime.originalTitle)}")
            initialized = true
        }
    }

    private fun parseStatus(statusString: String?, seasonCount: Int = 1, season: Int = 1): Int {
        if (season < seasonCount) return SAnime.COMPLETED
        return when (statusString?.trim()) {
            "EN COURS" -> SAnime.ONGOING
            "TERMINÉ" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun decryptFrAnime(encrypted: String): String? {
        if (encrypted.isEmpty()) return null
        val hexData = try {
            String(Base64.decode(encrypted, Base64.DEFAULT))
        } catch (e: Exception) {
            return null
        }
        if (hexData.isEmpty()) return null

        for (key in 0..255) {
            val sb = StringBuilder()
            try {
                var i = 0
                while (i < hexData.length) {
                    val hex = hexData.substring(i, i + 2)
                    val charCode = hex.toInt(16)
                    sb.append((charCode xor key).toChar())
                    i += 2
                }
                val result = sb.toString()
                if (result.startsWith("http")) return result
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }
}
