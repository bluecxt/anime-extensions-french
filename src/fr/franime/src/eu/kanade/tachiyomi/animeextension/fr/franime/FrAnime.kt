package eu.kanade.tachiyomi.animeextension.fr.franime

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Anime
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class FrAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "FrAnime"

    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!
    }

    private val domain: String
        get() = baseUrl.toHttpUrl().host

    private val baseApiUrl: String
        get() = "https://api.$domain/api"

    private val baseApiAnimeUrl: String
        get() = "$baseApiUrl/anime"

    override val lang = "fr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

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
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // = :::::::::::::::::::::::::: Episodes :::::::::::::::::::::::::: =
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = (baseUrl + anime.url).toHttpUrl()
        val stem = url.encodedPathSegments.last()
        val animeData = database.first { titleToUrl(it.originalTitle) == stem }

        val episodes = animeData.seasons.flatMapIndexed { sIndex, season ->
            season.episodes.mapIndexedNotNull { eIndex, episode ->
                val hasPlayers = episode.languages.vo.players.isNotEmpty() || episode.languages.vf.players.isNotEmpty()
                if (!hasPlayers) return@mapIndexedNotNull null

                SEpisode.create().apply {
                    setUrlWithoutDomain(anime.url + "?s=${sIndex + 1}&ep=${eIndex + 1}")
                    name = (if (animeData.seasons.size > 1) "S${sIndex + 1} " else "") + (episode.title ?: "Épisode ${eIndex + 1}")
                    episode_number = (sIndex * 1000 + eIndex + 1).toFloat()
                }
            }
        }
        return episodes.sortedByDescending { it.episode_number }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // = :::::::::::::::::::::::::: Video Links :::::::::::::::::::::::::: =
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = (baseUrl + episode.url).toHttpUrl()
        val seasonNumber = url.queryParameter("s")?.toIntOrNull() ?: 1
        val episodeNumber = url.queryParameter("ep")?.toIntOrNull() ?: 1
        val animeData = database.first { titleToUrl(it.originalTitle) == url.encodedPathSegments.last() }
        val episodeData = animeData.seasons[seasonNumber - 1].episodes[episodeNumber - 1]
        val videoBaseUrl = "$baseApiAnimeUrl/${animeData.id}/${seasonNumber - 1}/${episodeNumber - 1}"

        val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
        val sibnetExtractor by lazy { SibnetExtractor(client) }
        val vkExtractor by lazy { VkExtractor(client, headers) }
        val vidMolyExtractor by lazy { VidMolyExtractor(client) }
        val filemoonExtractor by lazy { FilemoonExtractor(client) }

        suspend fun extractPlayerVideos(playerName: String, apiUrl: String, lang: String): List<Video> {
            val responseBody = client.newCall(GET(apiUrl, headers)).execute().body.string()
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
            val prefix = "($lang) $server - "

            return when (playerName.lowercase()) {
                "sendvid" -> sendvidExtractor.videosFromUrl(playerUrl, prefix)
                "sibnet" -> sibnetExtractor.videosFromUrl(playerUrl, prefix)
                "vk" -> vkExtractor.videosFromUrl(playerUrl, prefix)
                "vidmoly" -> vidMolyExtractor.videosFromUrl(playerUrl, prefix)
                "filemoon" -> filemoonExtractor.videosFromUrl(playerUrl, prefix)
                else -> emptyList()
            }
        }

        val voVideos = episodeData.languages.vo.players.withIndex().toList().parallelCatchingFlatMap { (index, playerName) ->
            extractPlayerVideos(playerName, "$videoBaseUrl/vo/$index", "VOSTFR")
        }
        val vfVideos = episodeData.languages.vf.players.withIndex().toList().parallelCatchingFlatMap { (index, playerName) ->
            extractPlayerVideos(playerName, "$videoBaseUrl/vf/$index", "VF")
        }

        return (voVideos + vfVideos).map {
            Video(it.url, cleanQuality(it.quality), it.videoUrl, it.headers, it.subtitleTracks, it.audioTracks)
        }.sort()
    }

    private fun cleanQuality(quality: String): String = quality.replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s"), "")
        .replace(Regex("\\s*\\(\\d+x\\d+\\)"), "")
        .replace(Regex("(?i)Sendvid:default"), "")
        .replace(Regex("(?i)Sibnet:default"), "")
        .replace(Regex("(?i)VK:default"), "")
        .replace(Regex("(?i)Vidmoly:default"), "")
        .replace(Regex("(?i)Filemoon:default"), "")
        .replace(" - - ", " - ")
        .trim()
        .removeSuffix("-")
        .trim()

    override fun List<Video>.sort(): List<Video> {
        val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        return this.sortedWith(compareBy({ it.quality.contains(prefVoice, true) })).reversed()
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
            description = anime.description
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
