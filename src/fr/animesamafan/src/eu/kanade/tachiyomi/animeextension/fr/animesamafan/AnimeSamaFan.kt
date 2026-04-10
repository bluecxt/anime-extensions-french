package eu.kanade.tachiyomi.animeextension.fr.animesamafan

import android.app.Application
import android.content.SharedPreferences
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
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeSamaFan :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Anime-Sama-Fan"
    override val baseUrl = "https://animesama.fan"
    override val lang = "fr"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        )

    // ================== Utils ==================
    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$baseUrl$url"

    private fun parseAnimePage(document: Document): AnimesPage {
        val items = document.select("a.card-base").map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".card-title")?.text() ?: ""
                thumbnail_url = element.selectFirst(".card-image")?.attr("abs:src")
                url = fixUrl(element.attr("href")).replace(baseUrl, "")
            }
        }
        val hasNextPage = document.selectFirst("ul.pagination li.active + li a") != null
        return AnimesPage(items, hasNextPage)
    }

    // ================== Catalogue ===============
    private fun catalogueRequest(page: Int, sort: String = "", query: String = ""): Request {
        val url = "$baseUrl/anime/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("search", query)
            .addQueryParameter("sort", sort)
            .build()

        return GET(url.toString(), headers)
    }

    // ================== Popular ==================
    override fun popularAnimeRequest(page: Int): Request = catalogueRequest(page, "views")
    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimePage(response.asJsoup())

    // ================== Latest ==================
    override fun latestUpdatesRequest(page: Int): Request = catalogueRequest(page, "recent")
    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimePage(response.asJsoup())

    // ================== Search ==================
    private fun parseSearchPage(document: Document): AnimesPage {
        val items = document.select("a.asn-search-result").map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".asn-search-result-title")?.text() ?: ""
                thumbnail_url = element.selectFirst(".asn-search-result-img")?.attr("abs:src")
                url = fixUrl(element.attr("href")).replace(baseUrl, "")
            }
        }
        return AnimesPage(items, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val formBody = FormBody.Builder()
            .add("query", query)
            .build()

        return Request.Builder()
            .url("$baseUrl/template-php/defaut/fetch.php")
            .post(formBody)
            .headers(headers)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseSearchPage(response.asJsoup())

    // ================== Details ==================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1.anime-title")?.text() ?: ""
        anime.description = document.selectFirst(".synopsis-content p")?.text()
        anime.genre = document.select(".genre-link").joinToString { it.text() }
        anime.status = SAnime.UNKNOWN
        anime.thumbnail_url = document.selectFirst(".anime-cover img")?.attr("abs:src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        return anime
    }

    // ================== Episodes ==================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        document.select(".seasons-grid a.season-card").forEach { seasonLink ->
            val sUrl = seasonLink.attr("abs:href")
            try {
                val sDoc = client.newCall(GET(sUrl, headers)).execute().asJsoup()
                episodes.addAll(parseEpisodesFromDocument(sDoc))
            } catch (e: Exception) {}
        }
        return episodes.distinctBy { it.url }.reversed()
    }

    private fun parseEpisodesFromDocument(document: Document): List<SEpisode> {
        val url = document.location()
        val seasonMatch = Regex("""saison-(\d+)""").find(url)
        val sName = seasonMatch?.let { "Saison ${it.groupValues[1]}" } ?: ""

        return document.select("a.episode-card").map { card ->
            val availableLangs = mutableListOf<String>()
            val langs = card.attr("data-langs").uppercase()
            if (langs.contains("VOSTFR")) availableLangs.add("VOSTFR")
            if (langs.contains("VF")) availableLangs.add("VF")

            SEpisode.create().apply {
                this.url = card.attr("abs:href")
                this.name = if (sName.isNotEmpty()) {
                    "$sName - ${card.selectFirst("h3.episode-title")?.text()}"
                } else {
                    card.selectFirst("h3.episode-title")?.text() ?: "Épisode"
                }
                this.episode_number = (card.selectFirst(".episode-number")?.text() ?: "0")
                    .replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                this.scanlator = availableLangs.joinToString(", ")
            }
        }
    }

    // ================== Video (Extracteurs) ==================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = mutableListOf<Video>()

        val langs = episode.scanlator?.split(", ") ?: listOf("VOSTFR")

        langs.forEach { lang ->
            try {
                val urlWithLang = if (episode.url.contains("?")) {
                    "${episode.url}&lang=${lang.lowercase()}"
                } else {
                    "${episode.url}?lang=${lang.lowercase()}"
                }
                val doc = client.newCall(GET(urlWithLang, headers)).execute().asJsoup()
                doc.select("iframe").forEach { iframe ->
                    addVideosFromUrl(iframe.attr("abs:src"), lang, videoList)
                }
            } catch (e: Exception) {
            }
        }
        return videoList.sort()
    }

    private fun addVideosFromUrl(serverUrl: String, lang: String, videoList: MutableList<Video>) {
        val server = when {
            serverUrl.contains("sibnet.ru") -> "Sibnet"
            serverUrl.contains("sendvid.com") -> "Sendvid"
            serverUrl.contains("streamtape") || serverUrl.contains("shavetape") -> "Streamtape"
            serverUrl.contains("dood") -> "Doodstream"
            serverUrl.contains("vidoza.net") -> "Vidoza"
            serverUrl.contains("voe.sx") -> "Voe"
            else -> return
        }
        val prefix = "($lang) $server - "
        when (server) {
            "Sibnet" -> SibnetExtractor(client).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }

            "Sendvid" -> SendvidExtractor(client, headers).videosFromUrl(serverUrl, prefix)
                .forEach { videoList.add(it) }

            "Streamtape" -> StreamTapeExtractor(client).videoFromUrl(serverUrl, prefix)?.let { videoList.add(it) }

            "Doodstream" -> DoodExtractor(client).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }

            "Vidoza" -> VidoExtractor(client).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }

            "Voe" -> VoeExtractor(client, headers).videosFromUrl(serverUrl, prefix).forEach { videoList.add(it) }
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val prefVoice = preferences.getString("preferred_voices", "VOSTFR")!!
        val prefServer = preferences.getString("preferred_server", "sibnet")!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(prefVoice, true) },
                { it.quality.contains(prefServer, true) },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val voicesPref = ListPreference(screen.context).apply {
            key = "preferred_voices"
            title = "Préférence des voix"
            entries = arrayOf("Préférer VOSTFR", "Préférer VF")
            entryValues = arrayOf("VOSTFR", "VF")
            setDefaultValue("VOSTFR")
            summary = "%s"
        }
        val serverPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Serveur préféré"
            entries = arrayOf("Sibnet", "Sendvid", "Voe", "Streamtape", "Doodstream", "Vidoza")
            entryValues = arrayOf("sibnet", "sendvid", "voe", "streamtape", "dood", "vidoza")
            setDefaultValue("sibnet")
            summary = "%s"
        }
        screen.addPreference(voicesPref)
        screen.addPreference(serverPref)
    }

    override fun popularAnimeSelector(): String = ""
    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun popularAnimeNextPageSelector(): String? = ""
    override fun latestUpdatesSelector(): String = ""
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String? = ""
    override fun searchAnimeSelector(): String = ""
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector(): String? = ""
    override fun episodeListSelector(): String = ""
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()
    override fun videoListSelector(): String = ""
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()
}
