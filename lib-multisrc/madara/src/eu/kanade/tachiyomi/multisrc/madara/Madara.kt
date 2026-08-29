// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.multisrc.madara

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.Source
import fr.bluecxt.core.filters.FilterSpec
import fr.bluecxt.core.utils.JsoupExtensions
import fr.bluecxt.core.utils.parseStatus
import fr.bluecxt.core.utils.safeRelativePath
import keiyoushi.core.R
import keiyoushi.utils.get
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

abstract class Madara(
    override val name: String,
    override val defaultBaseUrl: String,
    override val lang: String,
) : Source(),
    CommonPreferences,
    JsoupExtensions {

    override val supportsLatest = true

    open val animeSubString: String = "drama"

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage = parseAnime(
        client.get(if (page > 1) "$baseUrl/page/$page" else baseUrl, headers).asJsoup(),
        popularAnimeSelector(),
        popularAnimeNameSelector(),
    )

    open fun popularAnimeSelector(): String = ".video"
    open fun popularAnimeNameSelector(): String = "h3.h5"

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage = parseAnime(
        client.get(if (page > 1) "$baseUrl/nouveaux-ajouts/page/$page/" else "$baseUrl/nouveaux-ajouts", headers).asJsoup(),
        latestAnimeSelector(),
        latestAnimeNameSelector(),
    )

    open fun latestAnimeSelector(): String = ".video"
    open fun latestAnimeNameSelector(): String = "h3.h5"

    // ============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = MadaraFilters.buildFilterList(customFilters)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val trimmedQuery = query.trim()

        if (trimmedQuery.startsWith(PREFIX_SEARCH)) {
            val slug = trimmedQuery.removePrefix(PREFIX_SEARCH).trim().removePrefix("/").removeSuffix("/")
            val directAnime = SAnime.create().apply {
                url = if (slug.startsWith("$animeSubString/")) "/$slug/" else "/$animeSubString/$slug/"
            }
            return try {
                val details = getAnimeDetails(directAnime)
                AnimesPage(listOf(details), false)
            } catch (_: Exception) {
                AnimesPage(emptyList(), false)
            }
        }

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegment("page/$page")

            addQueryParameter("s", trimmedQuery)
            addQueryParameter("post_type", "wp-manga")
            applyFilters(filters)
        }.build()

        val document = client.get(url, headers).asJsoup()

        return parseAnime(document, searchAnimeSelector(), searchAnimeNameSelector())
    }

    open fun searchAnimeSelector(): String = ".c-tabs-item > div"
    open fun searchAnimeNameSelector(): String = "h3.h4"

    // ============================== Parsing ===============================
    open fun parseAnime(
        document: Document,
        animesSelector: String,
        nameSelector: String,
    ): AnimesPage = with(document) {
        val animes = select(animesSelector).mapNotNull { element ->
            val name = element.selectFirst(nameSelector)?.text() ?: ""
            val link = element.selectFirst("$nameSelector a")
                ?.safeRelativePath()
                ?: return@mapNotNull null
            val genres = element.select(".mg_genres a").joinToString { it.text() }

            SAnime.create().apply {
                url = link
                thumbnail_url = element.selectFirst("img")?.attr("src")?.replace("110x150", "193x278") ?: ""
                title = name
                genre = genres
                status = element.selectFirst(".mg_status .summary-content")?.text()?.parseStatus() ?: SAnime.UNKNOWN
            }
        }.distinctBy { it.url }

        val hasNextPage = selectFirst(".nextpostslink") != null

        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Anime Details ===============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime.apply {
        val document = client.get("$baseUrl$url", headers).asJsoup()

        if (description.isNullOrBlank()) {
            val date = document.selectFirst("div:contains(Start date) > div.summary-content")?.text()
            val synopsis = document.selectFirst(".summary__content p")?.text()

            if (!date.isNullOrBlank() || !synopsis.isNullOrBlank()) {
                description = buildString {
                    if (!date.isNullOrBlank()) appendLine("${getString(R.string.metadata_release_date_prefix)}$date")
                    if (!synopsis.isNullOrBlank()) append(synopsis)
                }
            }
        }
        if (genre.isNullOrBlank()) genre = document.select(".genres-content > a").joinToString { it.text() }
        if (status == SAnime.UNKNOWN) status = document.selectFirst("div:contains(Status) > div.summary-content")?.text()?.parseStatus() ?: SAnime.UNKNOWN
        if (author.isNullOrBlank()) author = document.selectFirst("div:contains(Studios) > div.summary-content")?.text()
    }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Episodes ===============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = throw UnsupportedOperationException()

    // ============================== Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = throw UnsupportedOperationException()

    // ============================== Videos ===============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
