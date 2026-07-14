package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.util.Log
import eu.kanade.tachiyomi.animeextension.fr.animesama.dto.UrlContent
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.ANIMESAMA_LOG
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.Source
import fr.bluecxt.core.safeRelativePath
import fr.bluecxt.core.utils.selectFirstLog
import fr.bluecxt.core.utils.selectLog
import keiyoushi.utils.get
import keiyoushi.utils.toJsonString
import keiyoushi.utils.parseAs
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

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
        val url = "$baseUrl/catalogue?page=$page"

        val document = client.get(url, headers).asJsoup()

        return parseCatalogue(document, page)
    }

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val document = client.get(baseUrl, headers).asJsoup()
        return parseMainPage(document)
    }

    // ============================== Search ===============================
    override fun getFilterList() = AnimeSamaFilters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder()
        val params = AnimeSamaFilters.getSearchFilters(filters)

        params.types.forEach { url.addQueryParameter("type[]", it) }
        params.language.forEach { url.addQueryParameter("langue[]", it) }
        params.genres.forEach { url.addQueryParameter("genre[]", it) }

        url.addQueryParameter("search", query)
        url.addQueryParameter("page", "$page")

        val document = client.get(url.build(), headers).asJsoup()
        return parseCatalogue(document, page)
    }

    // ============================== Utils ===============================
    private fun parseMainPage(document: Document): AnimesPage = parseCatalogue(
        document,
        0,
        "#containerSorties > div, #containerAjoutsAnimes > div, #containerJeudi > div, #akTrack > div",
    )

    private fun parseCatalogue(
        document: Document,
        page: Int,
        animesSelector: String = "div.catalog-card",
    ): AnimesPage {
        val animes = document.selectLog(animesSelector).mapNotNull { anime ->
            if (anime.selectLog(".info-row:has(.info-label:contains(Types)) .info-value").text().trim().equals("Scans", true)) return@mapNotNull null

            val link = anime.selectFirstLog("a")?.safeRelativePath() ?: return@mapNotNull null
            val realLink = link.split("/").take(3).joinToString("/")
            val thumbnail = anime.selectLog("img:not(.ak-cta-flag)").attr("abs:src")
            val name = anime.selectFirstLog(".card-title, h2")?.text() ?: "unknown title"
            val names: List<String> = anime.selectFirstLog("p.alternate-titles")?.text()?.split(",") ?: listOf(name)

            val jsonUrl = UrlContent(url = realLink, titles = names).toJsonString()

            Log.d(ANIMESAMA_LOG, "title = $name, url = $realLink")

            SAnime.create().apply {
                title = name
                thumbnail_url = thumbnail
                url = jsonUrl
            }
        }

        val lastPage = document.selectLog("#list_pagination a:last-child").text().toIntOrNull() ?: 0

        val hasNextPage = if (lastPage != 0 && page < lastPage) true else false

        return AnimesPage(animes.distinctBy { it.url }, hasNextPage)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        Log.d(ANIMESAMA_LOG, "url dans details = ${anime.url}")
        return anime
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
