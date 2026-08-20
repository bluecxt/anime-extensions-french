// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.voirdrama

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.Source
import fr.bluecxt.core.tvdb.fetchTvdbMetadata
import fr.bluecxt.core.utils.JsoupExtensions
import fr.bluecxt.core.utils.parseStatus
import fr.bluecxt.core.utils.safeRelativePath
import keiyoushi.utils.get
import keiyoushi.utils.parallelMap
import keiyoushi.utils.post
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

class VoirDrama :
    Source(),
    CommonPreferences,
    JsoupExtensions {

    override val name = "VoirDrama"
    override val defaultBaseUrl = "https://voirdrama.to"

    override val supportedServers = listOf("")
    override val supportedVoices: Array<String> = arrayOf("")
    override val lang = "fr"
    override val supportsLatest = true

    override fun getAnimeUrl(anime: SAnime): String = throw UnsupportedOperationException()

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage = throw UnsupportedOperationException()

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage = throw UnsupportedOperationException()

    // ============================== Search ===============================

    override fun getFilterList(): AnimeFilterList = VoirDramaFilters.getFilterList()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val f = VoirDramaFilters.getSearchFilters(filters)

        val url = "$baseUrl".toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegment("page/$page")

            addQueryParameter("s", query)
            addQueryParameter("post_type", "wp-manga")

            if (f.order.isNotBlank()) addQueryParameter("m_orderby", f.order)
            if (f.type.isNotBlank()) addQueryParameter("type", f.type)
            if (f.language.isNotBlank()) addQueryParameter("lang", f.language)
            if (f.country.isNotBlank()) addQueryParameter("country", f.country)
            if (f.adult.isNotBlank()) addQueryParameter("adult", f.adult)
            if (f.year.isNotBlank()) addQueryParameter("year", f.year)
            if (f.genreOp.isNotBlank()) addQueryParameter("op", f.genreOp)

            f.status.forEach { addQueryParameter("status[]", it) }
            f.genres.forEach { addQueryParameter("genre[]", it) }
        }.build()

        val document = client.get(url, headers).asJsoup()

        return parseAnimes(document, page)
    }

    private suspend fun parseAnimes(document: Document, page: Int): AnimesPage {
        val animeList: List<SAnime> = document.select(".c-tabs-item > div").mapNotNull { element ->
            val name = element.selectFirst("h3.h4")?.text() ?: ""
            val link = element.selectFirst(".tab-thumb > a")
                ?.safeRelativePath()
                ?.takeIf { it.isNotBlank() }
                ?.replace("-vf", "")
                ?: return@mapNotNull null
            val hasVf = name.contains("(VF)")
            if (hasVf) preferences.edit().putBoolean("meta_has_vf_$link", hasVf).apply()

            SAnime.create().apply {
                url = link
                thumbnail_url = element.selectFirst("img")?.attr("src") ?: ""
                title = name.replace(" (VF)", "")
                genre = element.select(".mg_genres a").joinToString { it.text() }
                status = element.selectFirst(".mg_status .summary-content")?.text()?.parseStatus() ?: SAnime.UNKNOWN
            }
        }
        val hasNextPage = document.selectFirst(".nextpostslink") != null
        return AnimesPage(animeList.distinctBy { it.url }, hasNextPage)
    }

    // ============================== Anime Details ===============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val isVf = preferences.getBoolean("meta_has_vf_${anime.url}", false)
        Log.d("voirdrama", "$isVf")
        return anime
    }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Episodes ===============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = throw UnsupportedOperationException()

    // ============================== Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = throw UnsupportedOperationException()

    // ============================== Videos ===============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    // ============================== Utils ===============================
}
