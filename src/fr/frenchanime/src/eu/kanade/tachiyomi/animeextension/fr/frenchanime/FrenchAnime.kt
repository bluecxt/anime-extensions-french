package eu.kanade.tachiyomi.animeextension.fr.frenchanime

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
import eu.kanade.tachiyomi.multisrc.datalifeengine.DataLifeEngine
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FrenchAnime :
    DataLifeEngine(
        "French Anime",
        "https://french-anime.com",
        "fr",
    ) {

    override val categories = arrayOf(
        Pair("<Sélectionner>", ""),
        Pair("Animes VF", "/animes-vf/"),
        Pair("Animes VOSTFR", "/animes-vostfr/"),
        Pair("Films VF et VOSTFR", "/films-vf-vostfr/"),
    )

    override val genres = arrayOf(
        Pair("<Sélectionner>", ""),
        Pair("Action", "/genre/action/"),
        Pair("Aventure", "/genre/aventure/"),
        Pair("Arts martiaux", "/genre/arts-martiaux/"),
        Pair("Combat", "/genre/combat/"),
        Pair("Comédie", "/genre/comedie/"),
        Pair("Drame", "/genre/drame/"),
        Pair("Epouvante", "/genre/epouvante/"),
        Pair("Fantastique", "/genre/fantastique/"),
        Pair("Fantasy", "/genre/fantasy/"),
        Pair("Mystère", "/genre/mystere/"),
        Pair("Romance", "/genre/romance/"),
        Pair("Shonen", "/genre/shonen/"),
        Pair("Surnaturel", "/genre/surnaturel/"),
        Pair("Sci-Fi", "/genre/sci-fi/"),
        Pair("School life", "/genre/school-life/"),
        Pair("Ninja", "/genre/ninja/"),
        Pair("Seinen", "/genre/seinen/"),
        Pair("Horreur", "/genre/horreur/"),
        Pair("Tranche de vie", "/genre/tranchedevie/"),
        Pair("Psychologique", "/genre/psychologique/"),
    )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes-vostfr/page/$page/")

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val lang = if (response.request.url.toString().contains("-vf")) "VF" else "VOSTFR"

        val epsData = document.selectFirst("div.eps")?.text() ?: return emptyList()
        epsData.split(" ").filter { it.isNotBlank() }.forEach {
            val data = it.split("!", limit = 2)
            val episode = SEpisode.create()
            episode.episode_number = data[0].toFloatOrNull() ?: 0F
            episode.name = "Episode ${data[0]}"
            episode.url = "$lang|${data[1]}"
            episodeList.add(episode)
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

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

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("|")
        val lang = parts.getOrNull(0) ?: "VOSTFR"
        val rawUrl = parts.getOrNull(1) ?: episode.url

        val list = rawUrl.split(",").filter { it.isNotBlank() }.parallelCatchingFlatMap {
            val server = when {
                it.contains("filemoon") -> "Filemoon"
                it.contains("lulu") || it.contains("vidzy") || it.contains("vidnest") -> "Lulu"
                it.contains("wish") -> "StreamWish"
                it.contains("dood") || it.contains("d0000d") -> "Doodstream"
                it.contains("upstream") -> "Upstream"
                it.contains("vudeo") -> "Vudeo"
                it.contains("uqload") -> "Uqload"
                it.contains("guccihide") || it.contains("streamhide") -> "StreamHide"
                it.contains("streamvid") -> "StreamVid"
                it.contains("vido") -> "Vidoza"
                it.contains("sibnet") -> "Sibnet"
                it.contains("ok.ru") -> "Okru"
                it.contains("streamhub.gg") -> "StreamHub"
                it.contains("vidmoly") -> "Vidmoly"
                it.contains("voe.sx") -> "Voe"
                else -> "Serveur"
            }
            val videoPrefix = "($lang) $server - "
            with(it) {
                when (server) {
                    "Filemoon" -> filemoonExtractor.videosFromUrl(this, videoPrefix)
                    "Lulu" -> luluExtractor.videosFromUrl(this, videoPrefix)
                    "StreamWish" -> streamWishExtractor.videosFromUrl(this, videoNameGen = { quality -> "$videoPrefix$quality" })
                    "Doodstream" -> doodExtractor.videosFromUrl(this, videoPrefix)
                    "Upstream" -> upstreamExtractor.videosFromUrl(this, videoPrefix)
                    "Vudeo" -> vudeoExtractor.videosFromUrl(this, videoPrefix)
                    "Uqload" -> uqloadExtractor.videosFromUrl(this, videoPrefix)
                    "StreamHide" -> streamHideVidExtractor.videosFromUrl(this) { quality -> "$videoPrefix$quality" }
                    "StreamVid" -> streamVidExtractor.videosFromUrl(this, videoPrefix)
                    "Vidoza" -> vidoExtractor.videosFromUrl(this, videoPrefix)
                    "Sibnet" -> sibnetExtractor.videosFromUrl(this, videoPrefix)
                    "Okru" -> okruExtractor.videosFromUrl(this, videoPrefix)
                    "StreamHub" -> streamHubExtractor.videosFromUrl(this, videoPrefix)
                    "Vidmoly" -> vidmolyExtractor.videosFromUrl(this, videoPrefix)
                    "Voe" -> voeExtractor.videosFromUrl(this, videoPrefix)
                    else -> emptyList()
                }
            }
        }
        return list.map {
            Video(it.url, cleanQuality(it.quality), it.videoUrl, it.headers, it.subtitleTracks, it.audioTracks)
        }.sort()
    }

    private val qualityCleanRegex = Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s")
    private val qualityResRegex = Regex("\\s*\\(\\d+x\\d+\\)")
    private val qualityServerRegex = Regex("(?i)(?:Sendvid|Sibnet|Doodstream|Voe):default")
    private val qualityExtraSpaceRegex = Regex("\\s+")
    private val qualityRegex = Regex("""(\d+)p""")

    override fun List<Video>.sort(): List<Video> = this.sortedWith(
        compareBy {
            qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        },
    ).reversed()

    private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(qualityCleanRegex, "")
            .replace(qualityResRegex, "")
            .replace(qualityServerRegex, "")
            .replace(" - - ", " - ")
            .trim()
            .removeSuffix("-")
            .trim()

        val servers = listOf("Lulu", "Sibnet", "Voe", "Doodstream", "Sendvid", "Vidmoly", "Filemoon", "Upstream", "Vudeo", "Uqload", "StreamHide", "StreamVid", "Vidoza", "StreamHub", "StreamWish")
        for (server in servers) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server(?!:)", RegexOption.IGNORE_CASE), server)
            cleaned = cleaned.replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(qualityExtraSpaceRegex, " ").replace(" - - ", " - ").trim()
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()
}
// Force build update for FrenchAnime
