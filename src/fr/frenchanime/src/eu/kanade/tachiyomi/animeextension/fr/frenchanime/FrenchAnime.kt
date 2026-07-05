package eu.kanade.tachiyomi.animeextension.fr.frenchanime

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.SelectorException
import fr.bluecxt.core.Source
import fr.bluecxt.core.fetchTmdbMetadata
import fr.bluecxt.core.safeRelativePath
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
// import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
// import eu.kanade.tachiyomi.lib.streamhubextractor.StreamHubExtractor
// import eu.kanade.tachiyomi.lib.streamvidextractor.StreamVidExtractor
// import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
// import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
// import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor

class FrenchAnime :
    Source(),
    CommonPreferences {

    override val name = "French Anime"
    override val defaultBaseUrl = "https://french-anime.com"

    override val lang = "fr"
    override val supportsLatest = true

    override val supportedServers = listOf(
        "Filemoon",
        "Lulu",
        "Dood",
        "Uqload",
        "Vidoza",
        "Sibnet",
        "Vidmoly",
        "Voe",
        "Vudeo",
        "Vido",
    )

    override val json: Json by injectLazy()

// ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/animes-vostfr/page/$page/", headers)).awaitSuccess()
        val document = response.asJsoup()
        val animes = document.select("div#dle-content > div.mov").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a[href]") ?: throw SelectorException("link not found")
                url = link.safeRelativePath()

                thumbnail_url = element.selectFirst("img[src]")?.absUrl("src") ?: ""
                title = "${link.text()} ${element.selectFirst("span.block-sai")?.text() ?: ""}".trim()
            }
        }
        val hasNextPage = document.selectFirst("span.navigation > span:not(.nav_ext) + a") != null
        return AnimesPage(animes, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/page/$page/", headers)).awaitSuccess()
        return getPopularAnime(page) // Reuse logic
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.newCall(GET("$baseUrl/index.php?do=search&subaction=search&story=$query&search_start=$page", headers)).awaitSuccess()
        val document = response.asJsoup()
        val animes = document.select("div#dle-content > div.mov").mapNotNull { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a[href]") ?: throw SelectorException("link not found")
                url = link.safeRelativePath()

                thumbnail_url = element.selectFirst("img[src]")?.absUrl("src") ?: ""

                val details = element.selectFirst("span.block-sai")?.text()?.trim() ?: ""
                val season = details.filter { it.isDigit() }.toIntOrNull()
                val lang = Regex("(?i)vostfr|vf").find(details)?.value?.uppercase()

                title = buildString {
                    append(link.text())
                    if (season != null) append(season)
                    if (lang != null) append(lang)
                }
            }
        }
        val hasNextPage = document.selectFirst("span.navigation > span:not(.nav_ext) + a") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess()
        val document = response.asJsoup()

        val h1 = document.selectFirst("h1")
        anime.thumbnail_url = document.selectFirst("#posterimg")?.absUrl("src") ?: anime.thumbnail_url

        val movList = document.select("ul.mov-list li")
        val synopsis = movList.select("div.mov-label:contains(Synopsis:) + div.mov-desc").text()

        anime.description = synopsis
        anime.genre = movList.select("div.mov-label:contains(GENRE:) + div.mov-desc a").joinToString { it.text() }
        anime.artist = movList.select("div.mov-label:contains(RÉALISATEUR:) + div.mov-desc").text().ifBlank { null }

        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess()
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val lang = if (document.baseUri().contains("-vf")) "VF" else "VOSTFR"

        val epsData = document.selectFirst("div.eps")?.text() ?: return emptyList()

        val sNumRegex = Regex("""(?i)(?:Saison|Season)\s*(\d+)""")
        val sNumMatch = sNumRegex.find(anime.title)
        val sNum = sNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val sPrefix = if (sNumMatch != null && sNum > 1) "[S$sNum] " else ""

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
                },
            )
        }

        return episodeList.asReversed()
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("|")
        val lang = parts.getOrNull(0) ?: "VOSTFR"
        val rawUrl = parts.getOrNull(1) ?: episode.url

        return listOf(
            Hoster(
                hosterName = lang,
                hosterUrl = rawUrl,
            ),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val rawUrl = hoster.hosterUrl
        val lang = hoster.hosterName

        return rawUrl.split(",").filter { it.isNotBlank() }.flatMap { playerUrl ->
            extractVideos(playerUrl, lang, supportedServers)
        }
    }
}
