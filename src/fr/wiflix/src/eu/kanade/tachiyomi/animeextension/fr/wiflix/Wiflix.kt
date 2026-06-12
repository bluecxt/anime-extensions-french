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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.Source
import fr.bluecxt.core.WIFLIX_LOG
import fr.bluecxt.core.safeRelativePath
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class Wiflix :
    Source(),
    CommonPreferences {

    override val name = "Wiflix"

    override val defaultBaseUrl = "https://flemmix.team"

    override val baseUrl: String get() = currentBaseUrl

    override val lang = "fr"

    override val supportsLatest = false

    override val baseUrlSummary = "https://ww1.wiflix-adresses.fun | https://wiflix-news.site"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super<CommonPreferences>.setupPreferenceScreen(screen)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)

    override val json: Json by injectLazy()

    override val client: okhttp3.OkHttpClient by lazy {
        super.client.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES) // todo c'est que pour la recherche pour le reste il faudrait une solution
            .build()
    }

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val hashDoc = client.newCall(GET(baseUrl)).execute().asJsoup()

        val userHash = hashDoc.select("script")
            .firstOrNull { it.data().contains("dle_login_hash") }

        val scriptElement = hashDoc.select("script").firstOrNull { it.data().contains("h_check") }
        val scriptData = scriptElement?.data() ?: ""

        Log.d(WIFLIX_LOG, scriptData)

        val hash = userHash?.data()?.substringAfter("var dle_login_hash = '")?.substringBefore("';") ?: ""

        Log.d(WIFLIX_LOG, "hash = $hash")

        val url = "$baseUrl/index.php?controller=ajax&mod=search"

        val formBody = FormBody.Builder()
            .add("query", query)
            .add("skin", "flemmixnew")
            .add("user_hash", hash)
            .build()

        val finalHeaders = Headers.Builder()
            .add("Accept", "*/*")
            .add("Cookie", "h_check=25")
            .build()

        return POST(url, finalHeaders, formBody)
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
                thumbnail_url = element.selectFirst("img")?.attr("abs:src") ?: "https://http.cat/404.jpg"
            }
        }
        val hasNextPage = document.selectFirst("span.pnext > a") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = "$baseUrl${anime.url}"
        Log.d(WIFLIX_LOG, url)
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
                extractVideos(videoUrl, lang, supportedServers)
            }
        }.awaitAll().flatten().coreSortVideos()
    }
}
