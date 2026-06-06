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
import fr.bluecxt.core.addBaseUrlPreference
import fr.bluecxt.core.safeRelativePath
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            .set("User-Agent", DEFAULT_USER_AGENT)
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
                url = element.selectFirst("a.mov-t")?.safeRelativePath() ?: ""
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

        val description = document.selectFirst("p[itemprop=description]")?.text()
            ?.substringAfter("Synopsis:")?.trim()
            ?: document.selectFirst("div.mov-desc > span[itemprop=description]")?.text()

        val year = document.select("li").find { it.selectFirst(".mov-label")?.text()?.contains("Date de sortie", true) == true }?.selectFirst(".mov-desc")?.text()?.trim()

        anime.description = if (year != null) "Date de sortie : $year\n\n$description" else description
        anime.genre = document.select("span[itemprop=genre]").joinToString { it.text() }

        if (anime.url.contains("/film-en-streaming/")) {
            anime.status = SAnime.COMPLETED
        }

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val anime = SAnime.create()
        val animeUrl = response.request.url.encodedPath

        val serieEpisodes = document.select("ul.eplist li.clicbtn")
        if (serieEpisodes.isNotEmpty()) {
            // Group episodes by number to create Super Packs per language later
            return serieEpisodes.groupBy { it.text().filter { c -> c.isDigit() }.ifBlank { "1" } }
                .map { (epNum, _) ->
                    SEpisode.create().apply {
                        this.episode_number = epNum.toFloat()
                        this.name = "Épisode $epNum"
                        this.url = "$animeUrl#group-$epNum"
                        prepareNewEpisode(this, anime)
                    }
                }.reversed()
        }

        // Movie case (Rule 1: [Movie] prefix)
        val episodes = mutableListOf<SEpisode>()
        if (document.selectFirst("div.tabs-sel.linkstab > a") != null || document.selectFirst("div.vostfr-links a") != null) {
            val movieTitle = document.selectFirst("h1[itemprop=name]")?.text() ?: "Film"
            episodes.add(
                SEpisode.create().apply {
                    name = "[Movie] $movieTitle"
                    episode_number = 1f
                    this.url = "$animeUrl#movie"
                    prepareNewEpisode(this, anime)
                },
            )
        }

        return episodes
    }

    // ============================ Video Links =============================

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val minochinosExtractor by lazy { MinoChinosExtractor(client) }
    private val embed4meExtractor by lazy { Embed4meExtractor(client) }
    private val streamDavExtractor by lazy { StreamDavExtractor(client) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidoExtractor by lazy { VidoExtractor(client) }
    private val vudeoExtractor by lazy { VudeoExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vidaraExtractor by lazy { VidaraExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val animeUrl = episode.url.substringBefore("#")
        val fragment = episode.url.substringAfter("#")

        val response = client.newCall(GET(baseUrl.removeSuffix("/") + animeUrl)).execute()
        val document = response.asJsoup()

        val hosters = mutableListOf<Hoster>()

        if (fragment.startsWith("group-")) {
            val epNum = fragment.substringAfter("group-")
            val elements = document.select("ul.eplist li.clicbtn").filter { it.text().filter { c -> c.isDigit() }.ifBlank { "1" } == epNum }

            if (elements.any { !it.parents().hasClass("blocvostfr") }) {
                hosters.add(Hoster(hosterName = "VF", hosterUrl = "$baseUrl$animeUrl#$fragment-vf", lazy = true))
            }
            if (elements.any { it.parents().hasClass("blocvostfr") }) {
                hosters.add(Hoster(hosterName = "VOSTFR", hosterUrl = "$baseUrl$animeUrl#$fragment-vostfr", lazy = true))
            }
        } else if (fragment == "movie") {
            if (document.selectFirst("div.tabs-sel.linkstab > a") != null) {
                hosters.add(Hoster(hosterName = "VF", hosterUrl = "$baseUrl$animeUrl#movie-vf", lazy = true))
            }
            if (document.selectFirst("div.vostfr-links a") != null) {
                hosters.add(Hoster(hosterName = "VOSTFR", hosterUrl = "$baseUrl$animeUrl#movie-vostfr", lazy = true))
            }
        }

        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = coroutineScope {
        val url = hoster.hosterUrl
        val lang = hoster.hosterName // "VF" or "VOSTFR"
        val animeUrl = url.substringBefore("#")
        val fragment = url.substringAfter("#")

        val response = client.newCall(GET(animeUrl)).execute()
        val document = response.asJsoup()

        val serverLinks = if (fragment.startsWith("group-")) {
            val epNum = fragment.substringAfter("group-").substringBefore("-")
            val isVostfr = fragment.endsWith("-vostfr")
            val elements = document.select("ul.eplist li.clicbtn").filter {
                it.text().filter { c -> c.isDigit() }.ifBlank { "1" } == epNum &&
                    (if (isVostfr) it.parents().hasClass("blocvostfr") else !it.parents().hasClass("blocvostfr"))
            }
            elements.flatMap { el ->
                val rel = el.attr("rel")
                document.select("div.$rel a")
            }
        } else {
            val isVostfr = fragment.endsWith("-vostfr")
            val selector = if (isVostfr) "div.vostfr-links a" else "div.tabs-sel.linkstab > a"
            document.select(selector)
        }

        serverLinks.map { link ->
            async {
                val onclick = link.attr("onclick")
                val videoUrl = onclick.substringAfter("'").substringBefore("'")
                videosFromUrl(videoUrl, lang)
            }
        }.awaitAll().flatten().coreSortVideos()
    }

    private suspend fun videosFromUrl(url: String, lang: String): List<Video> {
        val videos = when {
            url.contains("sendvid") -> sendvidExtractor.videosFromUrl(url)
            url.contains("sibnet") -> sibnetExtractor.videosFromUrl(url)
            url.contains("vidmoly") -> vidmolyExtractor.videosFromUrl(url)
            url.contains("minochinos") -> minochinosExtractor.videosFromUrl(url)
            url.contains("embed4me") -> embed4meExtractor.videosFromUrl(url)
            url.contains("doods.pro") || url.contains("doodstream") -> doodExtractor.videosFromUrl(url).let { if (it.isEmpty()) emptyList() else it }
            url.contains("streamdav.com") || url.contains("streamdav") -> streamDavExtractor.videosFromUrl(url)
            url.contains("upstream.to") || url.contains("upstream") -> upstreamExtractor.videosFromUrl(url)
            url.contains("uqload.co") || url.contains("uqload") -> uqloadExtractor.videosFromUrl(url)
            url.contains("vido.lol") || url.contains("vido") -> vidoExtractor.videosFromUrl(url)
            url.contains("vudeo.co") || url.contains("vudeo") -> vudeoExtractor.videosFromUrl(url)
            url.contains("streamvid.net") || url.contains("vidhide") -> vidHideExtractor.videosFromUrl(url)
            url.contains("upns.pro") || url.contains("vidaraa.cc") || url.contains("vidara") -> vidaraExtractor.videosFromUrl(url, "")
            url.contains("voe.sx") || url.contains("bryantenunder.com") || url.contains("vickisaveworker.com") || url.contains("voe") -> voeExtractor.videosFromUrl(url, "")
            url.contains("luluvdo.com") || url.contains("luluvid.com") || url.contains("vidsonic.net") || url.contains("lulustream") -> luluExtractor.videosFromUrl(url, "")
            else -> emptyList()
        }

        // Rule 2: (Langue) Serveur - Qualité
        return videos.map {
            Video(videoUrl = it.videoUrl, videoTitle = "($lang) ${coreCleanQuality(it.videoTitle)}", headers = it.headers, subtitleTracks = it.subtitleTracks, audioTracks = it.audioTracks)
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(preferences, PREF_URL_DEFAULT, PREF_URL_TITLE, PREF_URL_KEY, PREF_URL_SUMMARY)

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
        private const val PREF_URL_DEFAULT = "https://flemmix.team"
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
