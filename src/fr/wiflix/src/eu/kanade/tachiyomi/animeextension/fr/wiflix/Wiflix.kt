package eu.kanade.tachiyomi.animeextension.fr.wiflix

import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.embed4meextractor.Embed4meExtractor
import eu.kanade.tachiyomi.lib.luluextractor.LuluExtractor
import eu.kanade.tachiyomi.lib.minochinosextractor.MinoChinosExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamdavextractor.StreamDavExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidaraextractor.VidaraExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.Source
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class Wiflix : Source() {
    private val log = "WiflixDebug"

    override val name = "Wiflix"

    override val baseUrl: String
        get() = preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!

    override val lang = "fr"

    override val supportsLatest = false

    override val client = network.client

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)
        .add("Referer", "$baseUrl/")

    override val json: Json by injectLazy()

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/index.php".toHttpUrl().newBuilder()
            .addQueryParameter("controller", "ajax")
            .addQueryParameter("mod", "search")
            .build()

        val requestHeaders = headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:151.0) Gecko/20100101 Firefox/151.0")
            .set("Accept", "*/*")
            .set("Accept-Language", "fr,fr-FR;q=0.9")
            .set("Accept-Encoding", "identity")
            .set("Referer", "$baseUrl/")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Origin", baseUrl)
            .set("DNT", "1")
            .set("Sec-GPC", "1")
            .set("Alt-Used", "flemmix.win")
            .set("Connection", "keep-alive")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "same-origin")
            .build()

        val formBody = FormBody.Builder()
            .add("query", query)
            .add("skin", "flemmixnew")
            .add("user_hash", "14eb8ac2f7a4034b330ba5749c65ece40bb94912") // pour l'instant sa marche mais faudra peut être le rendre dynamique
            .build()

        return POST(url.toString(), requestHeaders, formBody)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val anime = document.select("div.fast-search-result").mapNotNull { element ->
            val anchor = element.selectFirst("a.fsr-wrap") ?: return@mapNotNull null

            SAnime.create().apply {
                title = anchor.selectFirst("span.fsr-title")?.text() ?: "Title not found"
                url = anchor.selectFirst("a.fsr-wrap")?.attr("href")?.substringAfter(baseUrl) ?: ""
                thumbnail_url = anchor.selectFirst("img")?.attr("abs:src") ?: "https://http.cat/404.jpg"
            }
        }

        return AnimesPage(anime, false)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/serie-en-streaming/page/$page/")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select("div.mov").mapNotNull { element ->
            SAnime.create().apply {
                title = element.selectFirst("a.mov-t")?.text() ?: "failed selector"
                url = element.selectFirst("a.mov-t")?.attr("abs:href")?.substringAfter(baseUrl) ?: ""
                Log.d(log, url)
                thumbnail_url = element.selectFirst("img")?.attr("abs:src") ?: "https://http.cat/404.jpg"
            }
        }
        val hasNextPage = document.selectFirst("span.pnext > a") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = "$baseUrl${anime.url}"
        Log.d(log, url)
        val response = client.newCall(GET(url)).execute()
        val document = response.asJsoup()

        anime.description = document.selectFirst("div.mov-desc > span[itemprop=description]")?.text()
        anime.genre = document.select("span[itemprop=genre]").joinToString { it.text() }

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val anime = SAnime.create() // Dummy for prepareNewEpisode

        return document.select("ul.eplist li.clicbtn").map { element ->
            episodeFromElement(element).also { prepareNewEpisode(it, anime) }
        }.reversed()
    }

    private fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val epNum = element.text().filter { it.isDigit() }.ifBlank { "1" }.toFloat()
        episode_number = epNum
        val lang = if (element.parents().hasClass("blocvostfr")) "VOSTFR" else "VF"
        name = "Épisode ${epNum.toInt()} ($lang)"
        scanlator = lang
        // Store both the anime URL and the rel ID
        val animeUrl = element.ownerDocument()!!.location().substringAfter(baseUrl.removeSuffix("/"))
        url = "$animeUrl#${element.attr("rel")}"
    }

    // ============================ Video Links =============================

    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val minochinosExtractor by lazy { MinoChinosExtractor(client) }
    private val embed4meExtractor by lazy { Embed4meExtractor(client) }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val animeUrl = episode.url.substringBefore("#")
        val rel = episode.url.substringAfter("#")

        // We need the document to find the clichost links inside the div with class=rel
        val response = client.newCall(GET(baseUrl.removeSuffix("/") + animeUrl)).execute()
        val document = response.asJsoup()

        val hosterElements = document.select("div.$rel a")
        if (hosterElements.isEmpty()) {
            Log.e("Wiflix", "No hoster found for rel: $rel at $animeUrl")
        }

        return hosterElements.map {
            val onclick = it.attr("onclick")
            val videoUrl = onclick.substringAfter("'").substringBefore("'")
            val name =
                it.text().trim().ifBlank { videoUrl.substringAfter("//").substringBefore("/").removePrefix("www.") }
            Hoster(hosterName = name, hosterUrl = videoUrl, lazy = true)
        }
    }

    private fun SEpisode.getAnimeUrl(): String = this.url.substringBefore("#")

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        return when {
            url.contains("sendvid") -> SendvidExtractor(client, headers).videosFromUrl(url)
            url.contains("sibnet") -> SibnetExtractor(client).videosFromUrl(url)
            url.contains("vidmoly") -> VidMolyExtractor(client).videosFromUrl(url)
            url.contains("minochinos") -> MinoChinosExtractor(client).videosFromUrl(url)
            url.contains("embed4me") -> Embed4meExtractor(client).videosFromUrl(url)
            url.contains("doods.pro") || url.contains("doodstream") -> DoodExtractor(client).videosFromUrl(url)
            url.contains("streamdav.com") || url.contains("streamdav") -> StreamDavExtractor(client).videosFromUrl(url)
            url.contains("upstream.to") || url.contains("upstream") -> UpstreamExtractor(client).videosFromUrl(url)
            url.contains("uqload.co") || url.contains("uqload") -> UqloadExtractor(client).videosFromUrl(url)
            url.contains("vido.lol") || url.contains("vido") -> VidoExtractor(client).videosFromUrl(url)
            url.contains("vudeo.co") || url.contains("vudeo") -> VudeoExtractor(client).videosFromUrl(url)
            url.contains("streamvid.net") || url.contains("vidhide") -> VidHideExtractor(client, headers).videosFromUrl(url)
            url.contains("upns.pro") || url.contains("vidaraa.cc") || url.contains("vidara") -> VidaraExtractor(client).videosFromUrl(url, "")
            url.contains("voe.sx") || url.contains("bryantenunder.com") || url.contains("vickisaveworker.com") || url.contains("voe") -> VoeExtractor(client, headers).videosFromUrl(url, "")
            url.contains("luluvdo.com") || url.contains("luluvid.com") || url.contains("vidsonic.net") || url.contains("lulustream") -> LuluExtractor(client, headers).videosFromUrl(url, "")
            else -> emptyList()
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = PREF_URL_TITLE
            summary = PREF_URL_SUMMARY
            setDefaultValue(PREF_URL_DEFAULT)
            dialogTitle = PREF_URL_TITLE
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val cleanUrl = (newValue as String).trim().removeSuffix("/")
                    preferences.edit().putString(PREF_URL_KEY, cleanUrl).apply()
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = "Voices preference"
            entries = VOICES
            entryValues = VOICES_VALUES
            setDefaultValue(PREF_VOICES_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_PLAYER_KEY
            title = "Default player"
            entries = PLAYERS
            entryValues = PLAYERS_VALUES
            setDefaultValue(PREF_PLAYER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_URL_KEY = "base_url_pref"
        private const val PREF_URL_TITLE = "Base URL"
        private const val PREF_URL_DEFAULT = "https://flemmix.win"
        private const val PREF_URL_SUMMARY = "https://ww1.wiflix-adresses.fun | https://wiflix-news.site"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private val voicesMap = mapOf(
            "Prefer VOSTFR" to "vostfr",
            "Prefer VF" to "vf",
        )
        private val VOICES = voicesMap.keys.toTypedArray()
        private val VOICES_VALUES = voicesMap.values.toTypedArray()

        private val playersMap = mapOf(
            "Sendvid" to "sendvid",
            "Sibnet" to "sibnet",
            "VidMoly" to "vidmoly",
            "MinoChinos" to "minochinos",
            "Embed4me" to "embed4me",
            "DoodStream" to "doodstream",
            "LuluStream" to "lulustream",
            "StreamDav" to "streamdav",
            "Upstream" to "upstream",
            "Uqload" to "uqload",
            "Vidara" to "vidara",
            "VidHide" to "vidhide",
            "Vido" to "vido",
            "Voe" to "voe",
            "Vudeo" to "vudeo",
        )
        private val PLAYERS = playersMap.keys.toTypedArray()
        private val PLAYERS_VALUES = playersMap.values.toTypedArray()
    }
}
