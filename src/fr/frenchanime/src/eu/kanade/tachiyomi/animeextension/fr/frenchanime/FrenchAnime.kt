package eu.kanade.tachiyomi.animeextension.fr.frenchanime

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.luluextractor.LuluExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamhubextractor.StreamHubExtractor
import eu.kanade.tachiyomi.lib.streamvidextractor.StreamVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.Source
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class FrenchAnime : Source() {

    override val name = "French Anime"
    override val baseUrl = "https://french-anime.com"
    override val lang = "fr"
    override val supportsLatest = true

    override val json: Json by injectLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/animes-vostfr/page/$page/", headers)).execute()
        val document = response.asJsoup()
        val animes = document.select("div#dle-content > div.mov").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a[href]")!!
                setUrlWithoutDomain(link.attr("href"))
                thumbnail_url = element.selectFirst("img[src]")?.absUrl("src") ?: ""
                title = "${link.text()} ${element.selectFirst("span.block-sai")?.text() ?: ""}".trim()
            }
        }
        val hasNextPage = document.selectFirst("span.navigation > span:not(.nav_ext) + a") != null
        return AnimesPage(animes, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/page/$page/", headers)).execute()
        return getPopularAnime(page) // Reuse logic
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.newCall(GET("$baseUrl/index.php?do=search&subaction=search&story=$query&search_start=$page", headers)).execute()
        val document = response.asJsoup()
        val animes = document.select("div#dle-content > div.mov").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a[href]")!!
                setUrlWithoutDomain(link.attr("href"))
                thumbnail_url = element.selectFirst("img[src]")?.absUrl("src") ?: ""
                title = "${link.text()} ${element.selectFirst("span.block-sai")?.text() ?: ""}".trim()
            }
        }
        val hasNextPage = document.selectFirst("span.navigation > span:not(.nav_ext) + a") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()

        val h1 = document.selectFirst("h1")
        anime.title = h1?.text()?.trim() ?: anime.title
        anime.thumbnail_url = document.selectFirst("#posterimg")?.absUrl("src") ?: anime.thumbnail_url

        val movList = document.select("ul.mov-list li")
        val synopsis = movList.select("div.mov-label:contains(Synopsis:) + div.mov-desc").text()

        anime.description = synopsis
        anime.genre = movList.select("div.mov-label:contains(GENRE:) + div.mov-desc a").joinToString { it.text() }
        anime.artist = movList.select("div.mov-label:contains(RÉALISATEUR:) + div.mov-desc").text().ifBlank { null }

        // TMDB Metadata
        val tmdbMetadata = fetchTmdbMetadata(anime.title)
        tmdbMetadata?.summary?.let { anime.description = it }

        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val lang = if (response.request.url.toString().contains("-vf")) "VF" else "VOSTFR"

        val epsData = document.selectFirst("div.eps")?.text() ?: return emptyList()

        val tmdbMetadata = fetchTmdbMetadata(anime.title)
        val sNumRegex = Regex("""(?i)(?:Saison|Season)\s*(\d+)""")
        val sNumMatch = sNumRegex.find(anime.title)
        val sNum = sNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val sPrefix = if (sNumMatch != null) "[S$sNum] " else ""

        epsData.split(" ").filter { it.isNotBlank() }.forEach {
            val data = it.split("!", limit = 2)
            val epNumStr = data[0]
            val epNum = epNumStr.toIntOrNull() ?: 1

            episodeList.add(
                SEpisode.create().apply {
                    this.episode_number = epNumStr.toFloatOrNull() ?: 0F
                    this.name = "${sPrefix}Episode $epNumStr"
                    this.url = "$lang|${data[1]}"
                    this.scanlator = lang

                    // TMDB Metadata
                    val epMeta = tmdbMetadata?.episodeSummaries?.get(epNum)
                    preview_url = epMeta?.second
                    summary = epMeta?.third
                },
            )
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val vudeoExtractor by lazy { VudeoExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val streamVidExtractor by lazy { StreamVidExtractor(client) }
    private val vidoExtractor by lazy { VidoExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamHubExtractor by lazy { StreamHubExtractor(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("|")
        val lang = parts.getOrNull(0) ?: "VOSTFR"
        val rawUrl = parts.getOrNull(1) ?: episode.url

        val hosters = mutableListOf<Hoster>()
        rawUrl.split(",").filter { it.isNotBlank() }.forEach { playerUrl ->
            val server = when {
                playerUrl.contains("filemoon") -> "Filemoon"
                playerUrl.contains("lulu") || playerUrl.contains("vidzy") || playerUrl.contains("vidnest") -> "Lulu"
                playerUrl.contains("wish") -> "StreamWish"
                playerUrl.contains("dood") || playerUrl.contains("d0000d") -> "Doodstream"
                playerUrl.contains("upstream") -> "Upstream"
                playerUrl.contains("vudeo") -> "Vudeo"
                playerUrl.contains("uqload") -> "Uqload"
                playerUrl.contains("guccihide") || playerUrl.contains("streamhide") -> "StreamHide"
                playerUrl.contains("streamvid") -> "StreamVid"
                playerUrl.contains("vido") -> "Vidoza"
                playerUrl.contains("sibnet") -> "Sibnet"
                playerUrl.contains("ok.ru") -> "Okru"
                playerUrl.contains("streamhub.gg") -> "StreamHub"
                playerUrl.contains("vidmoly") -> "Vidmoly"
                playerUrl.contains("voe.sx") -> "Voe"
                else -> "Serveur"
            }
            hosters.add(Hoster(hosterName = "($lang) $server", internalData = "$playerUrl|$lang|$server"))
        }
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|")
        val playerUrl = data[0]
        val lang = data[1]
        val server = data[2]
        val prefix = "($lang) $server - "

        val videos = when (server) {
            "Filemoon" -> filemoonExtractor.videosFromUrl(playerUrl, prefix)
            "Lulu" -> luluExtractor.videosFromUrl(playerUrl, prefix)
            "StreamWish" -> streamWishExtractor.videosFromUrl(playerUrl, videoNameGen = { quality -> "$prefix$quality" })
            "Doodstream" -> doodExtractor.videosFromUrl(playerUrl, prefix)
            "Upstream" -> upstreamExtractor.videosFromUrl(playerUrl, prefix)
            "Vudeo" -> vudeoExtractor.videosFromUrl(playerUrl, prefix)
            "Uqload" -> uqloadExtractor.videosFromUrl(playerUrl, prefix)
            "StreamHide" -> streamHideVidExtractor.videosFromUrl(playerUrl) { quality -> "$prefix$quality" }
            "StreamVid" -> streamVidExtractor.videosFromUrl(playerUrl, prefix)
            "Vidoza" -> vidoExtractor.videosFromUrl(playerUrl, prefix)
            "Sibnet" -> sibnetExtractor.videosFromUrl(playerUrl, prefix)
            "Okru" -> okruExtractor.videosFromUrl(playerUrl, prefix)
            "StreamHub" -> streamHubExtractor.videosFromUrl(playerUrl, prefix)
            "Vidmoly" -> vidmolyExtractor.videosFromUrl(playerUrl, prefix)
            "Voe" -> voeExtractor.videosFromUrl(playerUrl, prefix)
            else -> emptyList()
        }

        return videos.map {
            Video(videoUrl = it.videoUrl, videoTitle = cleanQuality(it.videoTitle), headers = it.headers, subtitleTracks = it.subtitleTracks, audioTracks = it.audioTracks)
        }.sortVideos()
    }

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s"), "")
            .replace(Regex("\\s*\\(\\d+x\\d+\\)"), "")
            .replace(Regex("(?i)(?:Sendvid|Sibnet|Doodstream|Voe):default"), "")
            .replace(" - - ", " - ")
            .trim()
            .removeSuffix("-")
            .trim()

        val servers = listOf("Lulu", "Sibnet", "Voe", "Doodstream", "Sendvid", "Vidmoly", "Filemoon", "Upstream", "Vudeo", "Uqload", "StreamHide", "StreamVid", "Vidoza", "StreamHub", "StreamWish")
        for (server in servers) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server(?!:)", RegexOption.IGNORE_CASE), server)
            cleaned = cleaned.replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(Regex("\\s+"), " ").replace(" - - ", " - ").trim()
    }

    override fun List<Video>.sortVideos(): List<Video> = this.sortedWith(
        compareBy {
            Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        },
    ).reversed()
}
