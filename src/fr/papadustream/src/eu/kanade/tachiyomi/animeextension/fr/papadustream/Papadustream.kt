package eu.kanade.tachiyomi.animeextension.fr.papadustream

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.Source
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class Papadustream : Source() {
    override val name = "PapaDuStream"
    override val baseUrl = "https://papadustream.institute"
    override val lang = "fr"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    private var dleHash: String? = null

    private fun log(msg: String) = Log.d("Papadustream", msg)

    // ============================== Popular ==============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/cat-series/page/$page/", headers)).execute()
        val document = response.asJsoup()
        val animes = document.select("div.upd-item").map { element ->
            SAnime.create().apply {
                val link = element.select("a.upd-in")
                url = link.attr("href").replace(baseUrl, "")
                title = link.select("div.upd-title").text()
                thumbnail_url = link.select("img").attr("data-src").let {
                    if (it.startsWith("/")) baseUrl + it else it
                }
            }
        }
        val hasNextPage = document.select("span.pnext").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)

    // ============================== Search ==============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("search_start", page.toString())
            .add("full_search", "0")
            .add("result_from", ((page - 1) * 20 + 1).toString())
            .add("story", query)
            .build()

        val response = client.newCall(POST("$baseUrl/index.php?do=search", headers, body)).execute()
        val document = response.asJsoup()
        val animes = document.select("div.upd-item, div.search-item").map { element ->
            SAnime.create().apply {
                val link = element.select("a.upd-in")
                url = link.attr("href").replace(baseUrl, "")
                title = link.select("div.upd-title, h3").text()
                thumbnail_url = element.select("img").attr("data-src").let {
                    if (it.startsWith("/")) baseUrl + it else it
                }
            }
        }
        return AnimesPage(animes, false)
    }

    // ============================== Details ==============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()
        anime.title = document.select("h1").text().replace("en Streaming Gratuit", "").trim()
        anime.description = document.select("div.full_content-info").text()
        anime.thumbnail_url = document.select("div.full_content-poster img").attr("data-src")
        genre = document.select("ul.full_info li:contains(Genre) a").joinToString { it.text() }
        status = SAnime.UNKNOWN
        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()
        return document.select("ul.side-bc li a").map { element ->
            SEpisode.create().apply {
                url = element.attr("href").replace(baseUrl, "")
                val episodeNum = element.select("span.side-seas-number").text()
                name = "Épisode $episodeNum"
                episode_number = episodeNum.toFloatOrNull() ?: 1f
            }
        }.reversed()
    }

    // ============================== Hosters ==============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.newCall(GET("$baseUrl${episode.url}", headers)).execute()
        val document = response.asJsoup()
        val buttons = document.select(".player-list li div.lien")
        val id = document.html().substringAfter("getxfield(this, '", "").substringBefore("'", "")

        if (id.isEmpty()) return emptyList()

        return buttons.map { button ->
            val onclick = button.attr("onclick")
            val xfield = onclick.substringAfter("', '", "").substringBefore("'", "")
            val type = onclick.substringAfterLast("', '", "").substringBefore("')", "")
            val lang = if (button.select("img[src*=VF]").isNotEmpty()) "VF" else "VOSTFR"
            val hosterName = button.select("span.serv").text().lowercase()

            // Encode data in URL for getVideoList
            val encodedData = "id=$id&xfield=$xfield&type=$type&lang=$lang&hoster=$hosterName"
            Hoster(name = "$lang $hosterName", url = encodedData)
        }
    }

    // ============================== Videos ==============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.url
        val id = data.substringAfter("id=", "").substringBefore("&")
        val xfield = data.substringAfter("xfield=", "").substringBefore("&")
        val type = data.substringAfter("type=", "").substringBefore("&")
        val lang = data.substringAfter("lang=", "").substringBefore("&")
        val hosterName = data.substringAfter("hoster=", "")

        val iframe = try {
            fetchXField(id, xfield, type)
        } catch (e: Exception) {
            log("Error fetching xfield $xfield: ${e.message}")
            return emptyList()
        }

        if (iframe.contains("captcha") || iframe.contains("error")) {
            log("Hoster ($lang) $hosterName is protected by Captcha/Turnstile, skipping.")
            return emptyList()
        }

        var iframeUrl = Jsoup.parse(iframe).select("iframe").attr("src").let {
            if (it.startsWith("//")) "https:$it" else it
        }

        if (iframeUrl.isEmpty()) {
            // Try fallback
            if (!xfield.contains("_")) {
                val suffix = if (lang == "VF") "_vf" else "_vostfr"
                val fallback = fetchXField(id, xfield + suffix, type)
                if (!fallback.contains("captcha") && !fallback.contains("error")) {
                    iframeUrl = Jsoup.parse(fallback).select("iframe").attr("src").let {
                        if (it.startsWith("//")) "https:$it" else it
                    }
                }
            }
        }

        if (iframeUrl.isEmpty()) return emptyList()

        log("Final Video Iframe URL: $iframeUrl")
        return extractVideosFromIframe(iframeUrl, "$lang $hosterName")
    }

    private fun extractVideosFromIframe(url: String, prefix: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            when {
                url.contains("voe.sx") -> videos.addAll(VoeExtractor(client, headers).videosFromUrl(url, "$prefix "))

                url.contains("dood") -> videos.addAll(DoodExtractor(client).videosFromUrl(url, "$prefix "))

                url.contains("uqload") -> videos.addAll(UqloadExtractor(client).videosFromUrl(url, "$prefix "))

                url.contains("filemoon") -> videos.addAll(FilemoonExtractor(client).videosFromUrl(url, "$prefix "))

                url.contains("streamwish") || url.contains("awish") || url.contains("dwish") ->
                    videos.addAll(StreamWishExtractor(client, headers).videosFromUrl(url, "$prefix "))

                url.contains("sibnet") -> videos.addAll(SibnetExtractor(client).videosFromUrl(url, "$prefix "))

                url.contains("sendvid") -> videos.addAll(SendvidExtractor(client, headers).videosFromUrl(url, "$prefix "))

                url.contains("vido.") || url.contains("vidoza") -> videos.addAll(VidoExtractor(client).videosFromUrl(url, "$prefix "))

                url.contains("vidmoly") -> videos.addAll(VidMolyExtractor(client, headers).videosFromUrl(url, "$prefix "))

                // Upstream and clones
                url.contains("uptostream") || url.contains("upstream") || url.contains("upvid") ||
                    url.contains("utost") || url.contains("uprostream") -> {
                    val fixedUrl = url.replace(".link", ".com").replace(".co", ".com")
                    try {
                        videos.addAll(UpstreamExtractor(client).videosFromUrl(fixedUrl, "$prefix "))
                    } catch (_: Exception) {
                        videos.addAll(UpstreamExtractor(client).videosFromUrl(url, "$prefix "))
                    }
                }
            }
        } catch (e: Exception) {
            log("Error extracting from $url: ${e.message}")
        }
        return videos
    }

    private suspend fun fetchXField(id: String, xfield: String, type: String): String {
        val hash = dleHash ?: getDleHash().also { dleHash = it }
        val url = "$baseUrl/engine/ajax/controller.php?mod=getxfield"
        val body = FormBody.Builder()
            .add("id", id)
            .add("xfield", xfield)
            .add("type", type)
            .add("user_hash", hash)
            .build()

        val res = client.newCall(POST(url, headers, body)).execute()
        return res.body.string()
    }

    private fun getDleHash(): String {
        val res = client.newCall(GET(baseUrl, headers)).execute()
        val doc = res.asJsoup()
        return doc.html().substringAfter("var dle_login_hash = '", "").substringBefore("';", "")
    }

    // V16 Mandatory Stubs
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()
}
