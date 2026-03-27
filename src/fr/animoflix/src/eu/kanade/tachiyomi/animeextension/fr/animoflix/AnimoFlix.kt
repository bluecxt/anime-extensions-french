package eu.kanade.tachiyomi.animeextension.fr.animoflix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimoFlix :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "AnimoFlix"
    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!
    }
    override val lang = "fr"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val json = Json { ignoreUnknownKeys = true }
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://animoflix.com"

        private const val PREF_VOICES_KEY = "preferred_voices"
        private const val PREF_VOICES_TITLE = "Préférence des voix"
        private val VOICES_ENTRIES = arrayOf("Préférer VOSTFR", "Préférer VF")
        private val VOICES_VALUES = arrayOf("VOSTFR", "VF")
        private const val PREF_VOICES_DEFAULT = "VOSTFR"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Serveur préféré"
        private val SERVER_ENTRIES = arrayOf("Sibnet", "Sendvid", "Voe", "Streamtape", "Doodstream", "Vidoza")
        private val SERVER_VALUES = arrayOf("sibnet", "sendvid", "voe", "streamtape", "dood", "vidoza")
        private const val PREF_SERVER_DEFAULT = "sibnet"
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

        ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = PREF_VOICES_TITLE
            entries = VOICES_ENTRIES
            entryValues = VOICES_VALUES
            setDefaultValue(PREF_VOICES_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_VOICES_KEY, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = SERVER_ENTRIES
            entryValues = SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_SERVER_KEY, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    // ================== Popular ==================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("#sliderContainerTop .card-base-simple, #sliderContainer1 .card-base-simple").map {
            SAnime.create().apply {
                val link = it.selectFirst("a")!!
                url = link.attr("abs:href").toHttpUrl().encodedPath
                title = it.selectFirst(".card-title, h3, .anime-title")?.text() ?: ""
                thumbnail_url = it.selectFirst("img")?.attr("abs:src")
            }
        }.distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    override fun popularAnimeSelector() = throw UnsupportedOperationException()
    override fun popularAnimeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun popularAnimeNextPageSelector() = throw UnsupportedOperationException()

    // ================== Latest ==================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("#sliderContainerVostfr .card-base, #sliderContainerVf .card-base").map {
            SAnime.create().apply {
                val link = it.selectFirst("a")!!
                var animeUrl = link.attr("abs:href").substringBefore("/vostfr/").substringBefore("/vf/")
                if (!animeUrl.endsWith("/")) animeUrl += "/"
                url = animeUrl.toHttpUrl().encodedPath
                title = it.selectFirst(".card-title, h3, .anime-title")?.text() ?: ""
                thumbnail_url = it.selectFirst("img")?.attr("abs:src")
            }
        }.distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // ================== Search ==================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search-autocomplete.php?q=$query", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsonStr = response.body.string()
        if (jsonStr.isBlank()) return AnimesPage(emptyList(), false)
        return try {
            val jsonArray = JSONArray(jsonStr)
            val animes = mutableListOf<SAnime>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                animes.add(
                    SAnime.create().apply {
                        title = obj.getString("title")
                        url = "/anime/${obj.getString("slug")}/"
                        thumbnail_url = "$baseUrl/covers/${obj.getString("cover")}"
                    },
                )
            }
            AnimesPage(animes, false)
        } catch (e: Exception) {
            AnimesPage(emptyList(), false)
        }
    }

    override fun searchAnimeSelector() = throw UnsupportedOperationException()
    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    // ================== Details ==================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1.anime-title-pro")?.text() ?: ""
        val sName = document.selectFirst(".current-season .season-title")?.text()
        if (sName != null && !anime.title.contains(sName, true)) {
            anime.title += " $sName"
        }
        anime.description = document.selectFirst(".synopsis-text")?.text()
        anime.genre = document.select(".genre-tag").joinToString { it.text() }
        val statusText = document.selectFirst(".status-ongoing")?.text() ?: document.selectFirst(".status-completed")?.text()
        anime.status = when {
            statusText?.contains("En Cours", true) == true -> SAnime.ONGOING
            statusText?.contains("Terminé", true) == true -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        anime.thumbnail_url = document.selectFirst("img.poster-image")?.attr("abs:src") ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        return anime
    }

    // ================== Episodes ==================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val seasonCards = document.select(".seasons-grid a.season-card")

        return if (seasonCards.isNotEmpty()) {
            seasonCards.flatMap { card ->
                val seasonName = card.selectFirst(".season-title")?.text() ?: ""
                val seasonResponse = client.newCall(GET(card.attr("abs:href"), headers)).execute()
                parseEpisodesFromSeasonPage(seasonResponse.asJsoup(), seasonName)
            }.reversed()
        } else {
            val seasonName = document.selectFirst(".current-season .season-title")?.text() ?: ""
            parseEpisodesFromSeasonPage(document, seasonName).reversed()
        }
    }

    private fun parseEpisodesFromSeasonPage(document: Document, seasonName: String): List<SEpisode> {
        val allEpisodeCards = document.select("a.episode-card")
        val episodesMap = mutableMapOf<Float, MutableMap<String, String>>()
        val titlesMap = mutableMapOf<Float, String>()

        allEpisodeCards.forEach { card ->
            val url = card.attr("abs:href")
            val epTitle = card.selectFirst("h3.episode-title")?.text() ?: "Épisode"
            val epNumStr = card.selectFirst(".episode-number")?.text() ?: epTitle.replace(Regex("[^0-9]"), "")
            val epNum = epNumStr.toFloatOrNull() ?: 0f

            val lang = if (url.contains("/vf/")) "VF" else "VOSTFR"

            if (!episodesMap.containsKey(epNum)) {
                episodesMap[epNum] = mutableMapOf()
                titlesMap[epNum] = epTitle
            }
            episodesMap[epNum]!![lang] = url
        }

        return episodesMap.keys.sorted().map { epNum ->
            val langs = episodesMap[epNum]!!
            SEpisode.create().apply {
                episode_number = epNum
                val baseTitle = titlesMap[epNum] ?: "Épisode $epNum"
                name = if (seasonName.isNotEmpty()) "$seasonName - $baseTitle" else baseTitle

                val scanlators = mutableListOf<String>()
                if (langs.containsKey("VOSTFR")) scanlators.add("VOSTFR")
                if (langs.containsKey("VF")) scanlators.add("VF")
                scanlator = scanlators.joinToString(", ")

                url = json.encodeToString(langs)
            }
        }
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ================== Video ==================
    override fun videoListParse(response: Response): List<Video> = emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = mutableListOf<Video>()
        val langsMap = try {
            json.decodeFromString<Map<String, String>>(episode.url)
        } catch (e: Exception) {
            return emptyList()
        }

        langsMap.forEach { (lang, url) ->
            try {
                val response = client.newCall(GET(url, headers)).execute()
                val doc = response.asJsoup()

                doc.select("select#lecteurSelect option").forEach { option ->
                    val serverUrl = option.attr("value")
                    val serverName = option.text().trim()
                    val prefix = "($lang) $serverName - "

                    when {
                        serverUrl.contains("sibnet.ru") -> SibnetExtractor(client).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
                        serverUrl.contains("sendvid.com") -> SendvidExtractor(client, headers).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
                        serverUrl.contains("streamtape") || serverUrl.contains("shavetape") -> StreamTapeExtractor(client).videoFromUrl(serverUrl, prefix)?.let { videoList.add(it) }
                        serverUrl.contains("dood") -> DoodExtractor(client).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
                        serverUrl.contains("vidoza.net") -> VidoExtractor(client).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
                        serverUrl.contains("voe.sx") -> VoeExtractor(client, headers).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
                    }
                }
            } catch (e: Exception) {}
        }

        return videoList.map { video ->
            Video(video.url, cleanQuality(video.quality), video.videoUrl, video.headers)
        }.sort()
    }

    private fun cleanQuality(quality: String): String = quality.replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s"), "")
        .replace(Regex("\\s*\\(\\d+x\\d+\\)"), "")
        .replace(Regex("(?i)Sendvid:default"), "")
        .replace(Regex("(?i)Sibnet:default"), "")
        .replace(Regex("(?i)Doodstream:default"), "")
        .replace(Regex("(?i)Voe:default"), "")
        .replace(" - - ", " - ")
        .trim()
        .removeSuffix("-")
        .trim()

    override fun List<Video>.sort(): List<Video> {
        val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(prefVoice, true) },
                { it.quality.contains(prefServer, true) },
            ),
        ).reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
}
