package eu.kanade.tachiyomi.animeextension.fr.animesama

import fr.bluecxt.core.ANIMESAMA_LOG
import fr.bluecxt.core.CommonPreferences
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.get
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.safeRelativePath
import org.jsoup.nodes.Document
import eu.kanade.tachiyomi.animeextension.fr.animesama.dto.UrlContent

class AnimeSama :
    Source(),
    CommonPreferences {

    override val name = "Anime-Sama"

    override val defaultBaseUrl = "https://anime-sama.to"
    override val supportedServers = listOf("Sibnet", "Sendvid", "Vidmoly", "Embed4me", "Minochinos")
    override val supportedVoices = arrayOf("VOSTFR", "VF", "VA")
    override val lang = "fr"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(baseUrl)
            .addPathSegment("search")
            .addQueryParameter("page", page)
            .build()

        val document = client.get(url, headers).awaitSuccess().toJsoup()

        return parseCatalogue(document, page)
    }

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val document = client.get(baseUrl, headers).awaitSuccess().toJsoup()
        return parseCatalogue(document, page)
    }

    // ============================== Search ===============================

    // ============================== Utils ===============================
    private fun parseCatalogue(document: Document, page: Int): AnimesPage {
        val animes = document.select("div.catalog-card, #containerSorties > div, #containerAjoutsAnimes > div, #containerJeudi > div, #akTrack > div").mapNotNull { anime ->
            if (anime.select(".info-row:has(.info-label:contains(Types)) .info-value").text().trim().equals("Scans", true)) return@mapNotNull null

            val link = anime.selectFirst("a") ?: return@mapNotNull null
            val realLink = link.split("/").take(3).joinToString("/")
            val name = anime.selectFirst(".card-title, h2").text() ?: "unknown title"
            val names = anime.selectFirst("p.alternate-titles")?.text()?.split(",") ?: emptyList() + name

            val jsonUrl = json.encodeToString(UrlContent(
                url = realLink,
                titles = alternateNames,
            ))

            SAnime.create().apply {
                title = name
                thumbnail_url = anime.select("img:not(.ak-cta-flag)").attr("abs:src")
                url = jsonUrl
            }
        }

        val lastPage = document.select("#list_pagination a:last-child").text().toIntOrNull() ?: 0

        val hasNextPage = if (lastPage != 0 && page < lastPage) true else false

        return AnimesPage(animes.distinctBy { it.url }, hasNextPage)
    }
}