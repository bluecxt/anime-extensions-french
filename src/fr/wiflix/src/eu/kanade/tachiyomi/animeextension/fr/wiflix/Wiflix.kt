package eu.kanade.tachiyomi.animeextension.fr.wiflix

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.luluextractor.LuluExtractor
import eu.kanade.tachiyomi.lib.streamdavextractor.StreamDavExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidaraextractor.VidaraExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.multisrc.datalifeengine.DataLifeEngine
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

/**
 * Domain list: https://wiflix-news.site | https://ww1.wiflix-adresses.fun
 */
class Wiflix :
    DataLifeEngine(
        "Wiflix",
        "https://flemmix.win/",
        "fr",
    ) {
    override val categories = arrayOf(
        Pair("<Sélectionner>", ""),
        Pair("Séries", "/serie-en-streaming/"),
        Pair("Films", "/film-en-streaming/"),
    )

    override val genres = arrayOf(
        Pair("<Sélectionner>", ""),
        Pair("Action", "/film-en-streaming/action/"),
        Pair("Animation", "/film-en-streaming/animation/"),
        Pair("Arts Martiaux", "/film-en-streaming/arts-martiaux/"),
        Pair("Aventure", "/film-en-streaming/aventure/"),
        Pair("Biopic", "/film-en-streaming/biopic/"),
        Pair("Comédie", "/film-en-streaming/comedie/"),
        Pair("Comédie Dramatique", "/film-en-streaming/comedie-dramatique/"),
        Pair("Épouvante Horreur", "/film-en-streaming/horreur/"),
        Pair("Drame", "/film-en-streaming/drame/"),
        Pair("Documentaire", "/film-en-streaming/documentaire/"),
        Pair("Espionnage", "/film-en-streaming/espionnage/"),
        Pair("Famille", "/film-en-streaming/famille/"),
        Pair("Fantastique", "/film-en-streaming/fantastique/"),
        Pair("Guerre", "/film-en-streaming/guerre/"),
        Pair("Historique", "/film-en-streaming/historique/"),
        Pair("Musical", "/film-en-streaming/musical/"),
        Pair("Policier", "/film-en-streaming/policier/"),
        Pair("Romance", "/film-en-streaming/romance/"),
        Pair("Science-Fiction", "/film-en-streaming/science-fiction/"),
        Pair("Spectacles", "/film-en-streaming/spectacles/"),
        Pair("Thriller", "/film-en-streaming/thriller/"),
        Pair("Western", "/film-en-streaming/western/"),
    )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/serie-en-streaming/page/$page/")

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = "ul.eplist li.clicbtn"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val anime = SAnime.create() // Dummy for prepareNewEpisode

        return document.select(episodeListSelector()).map { element ->
            episodeFromElement(element).also { prepareNewEpisode(it, anime) }
        }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
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
            val name = it.text().trim().ifBlank { videoUrl.substringAfter("//").substringBefore("/").removePrefix("www.") }
            Hoster(hosterName = name, hosterUrl = videoUrl, lazy = true)
        }
    }

    private fun SEpisode.getAnimeUrl(): String = this.url.substringBefore("#")

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        Log.d("Wiflix", "Extracting video for: $url")
        val videos = when {
            url.contains("doods.pro") -> {
                Log.d("Wiflix", "Using DoodExtractor for $url")
                DoodExtractor(client).videosFromUrl(url)
            }

            url.contains("vido.lol") -> {
                Log.d("Wiflix", "Using VidoExtractor for $url")
                VidoExtractor(client).videosFromUrl(url)
            }

            url.contains("uqload.co") -> {
                Log.d("Wiflix", "Using UqloadExtractor for $url")
                UqloadExtractor(client).videosFromUrl(url)
            }

            url.contains("waaw1.tv") -> {
                Log.d("Wiflix", "Skipping waaw1.tv")
                emptyList()
            }

            url.contains("vudeo.co") -> {
                Log.d("Wiflix", "Using VudeoExtractor for $url")
                VudeoExtractor(client).videosFromUrl(url)
            }

            url.contains("streamvid.net") -> {
                Log.d("Wiflix", "Using VidHideExtractor for $url")
                VidHideExtractor(client, headers).videosFromUrl(url)
            }

            url.contains("upstream.to") -> {
                Log.d("Wiflix", "Using UpstreamExtractor for $url")
                UpstreamExtractor(client).videosFromUrl(url)
            }

            url.contains("upns.pro") || url.contains("vidaraa.cc") -> {
                Log.d("Wiflix", "Using VidaraExtractor for $url")
                VidaraExtractor(client).videosFromUrl(url, "")
            }

            url.contains("streamdav.com") -> {
                Log.d("Wiflix", "Using StreamDavExtractor for $url")
                StreamDavExtractor(client).videosFromUrl(url)
            }

            url.contains("voe.sx") || url.contains("bryantenunder.com") || url.contains("vickisaveworker.com") -> {
                Log.d("Wiflix", "Using VoeExtractor for $url")
                VoeExtractor(client, headers).videosFromUrl(url, "")
            }

            url.contains("luluvdo.com") || url.contains("luluvid.com") || url.contains("vidsonic.net") -> {
                Log.d("Wiflix", "Using LuluExtractor for $url")
                LuluExtractor(client, headers).videosFromUrl(url, "")
            }

            else -> {
                Log.d("Wiflix", "No specific extractor found for $url")
                emptyList()
            }
        }

        if (videos.isEmpty()) {
            Log.e("Wiflix", "No videos found for hoster: ${hoster.hosterName} at $url")
        }

        return videos
    }
}
