// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("SpellCheckingInspection")

package eu.kanade.tachiyomi.animeextension.fr.voiranime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.Source
import fr.bluecxt.core.tmdb.fetchTmdbMetadata
import fr.bluecxt.core.utils.safeRelativePath
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class VoirAnime :
    Source(),
    CommonPreferences {

    override val name = "VoirAnime"
    override val defaultBaseUrl = "https://voiranime.rip"

    override val lang = "fr"
    override val supportsLatest = true

    override val supportedServers = listOf("Embed4me", "Uqload", "Voe", "Dood", "Vidmoly", "Sendvid", "Filemoon")

    // ============================== Popular & Latest ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/catalogue/" else "$baseUrl/catalogue/?page=$page"
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val document = response.useAsJsoup()
        val items = document.select("div.anime-grid a.card-base").mapNotNull { link ->
            val path = link.safeRelativePath() ?: return@mapNotNull null
            val title = link.selectFirst("h2.card-title")?.text()
                ?: link.selectFirst("img.card-image")?.attr("alt")
                ?: return@mapNotNull null
            val thumb = link.selectFirst("img.card-image")?.attr("abs:src")
            SAnime.create().apply {
                this.url = path
                this.title = title
                this.thumbnail_url = thumb
            }
        }
        val hasNextPage = document.selectFirst("link[rel=next]") != null
        return AnimesPage(items, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (page > 1) {
            return AnimesPage(emptyList(), false)
        }
        val response = client.newCall(GET(baseUrl, headers)).awaitSuccess()
        val document = response.useAsJsoup()

        // 1. Try 'Derniers animes sortis' (completedAnimeSlider)
        val completedItems = document.select("#completedAnimeSlider div.xcatalog a").mapNotNull { link ->
            val path = link.safeRelativePath() ?: return@mapNotNull null
            val title = link.selectFirst("div.xcatalog-title")?.text()?.trim() ?: return@mapNotNull null
            val thumb = link.selectFirst("img.xcard-img")?.attr("abs:src")
            SAnime.create().apply {
                this.url = path
                this.title = title
                this.thumbnail_url = thumb
            }
        }

        // 2. Also extract from latest episode sliders (VOSTFR and VF) to catch actively updating ongoing series
        val recentEpisodeItems = document.select("#latestVostfrSlider div.xcard a, #latestVfSlider div.xcard a").mapNotNull { link ->
            val epPath = link.safeRelativePath() ?: return@mapNotNull null
            val title = link.selectFirst("div.xcard-title")?.text()?.trim() ?: return@mapNotNull null
            val thumb = link.selectFirst("img.xcard-img")?.attr("abs:src")

            // Derive anime URL from episode path: e.g. "/bleach/thousand-year.../episode-10/vostfr/" -> "/bleach/"
            val animePath = "/" + epPath.trim('/').substringBefore('/') + "/"
            SAnime.create().apply {
                this.url = animePath
                this.title = title
                this.thumbnail_url = thumb
            }
        }

        val allItems = (completedItems + recentEpisodeItems)
            .distinctBy { it.url }

        return if (allItems.isNotEmpty()) {
            AnimesPage(allItems, false)
        } else {
            getPopularAnime(page)
        }
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .apply {
                if (page > 1) addQueryParameter("page", page.toString())
            }
            .build()
        val response = client.newCall(GET(url.toString(), headers)).awaitSuccess()
        val document = response.useAsJsoup()
        val items = document.select("a.card-base").mapNotNull { link ->
            val path = link.safeRelativePath() ?: return@mapNotNull null
            val title = link.selectFirst("h2.card-title")?.text()
                ?: link.selectFirst("img.card-image")?.attr("alt")
                ?: return@mapNotNull null
            val thumb = link.selectFirst("img.card-image")?.attr("abs:src")
            SAnime.create().apply {
                this.url = path
                this.title = title
                this.thumbnail_url = thumb
            }
        }
        val hasNextPage = document.selectFirst("link[rel=next]") != null
        return AnimesPage(items, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess()
        val document = response.useAsJsoup()

        anime.title = document.selectFirst("h1.hero-title")?.text() ?: anime.title
        anime.description = document.selectFirst("div.synopsis-content")?.text()
            ?: document.selectFirst("p.synopsis")?.text()
            ?: anime.description
        anime.genre = document.select("div.genre-item span, div.genre-tag").joinToString { it.text() }
        anime.thumbnail_url = document.selectFirst("img.hero-poster, div.card-image-container img")?.attr("abs:src")
            ?: anime.thumbnail_url

        val tmdbMetadata = fetchTmdbMetadata(anime.title)
        tmdbMetadata?.summary?.let { anime.description = it }
        tmdbMetadata?.mainPosterUrl?.let { anime.thumbnail_url = it }
        tmdbMetadata?.author?.let { anime.author = it }
        tmdbMetadata?.artist?.let { anime.artist = it }
        tmdbMetadata?.status?.let { anime.status = it }

        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess()
        val document = response.useAsJsoup()

        val seasonCards = document.select("div.seasons-grid a.season-card")
        if (seasonCards.isNotEmpty()) {
            val episodes = mutableListOf<SEpisode>()
            val multipleSeasons = seasonCards.size > 1
            seasonCards.forEachIndexed { sIdx, seasonCard ->
                val sUrl = seasonCard.safeRelativePath() ?: return@forEachIndexed
                val sTitle = seasonCard.selectFirst("h3.season-title")?.text() ?: "Saison ${sIdx + 1}"
                val sNum = Regex("""(?i)(?:saison|partie)\s*(\d+)""").find(sTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (sIdx + 1)
                val sDoc = client.newCall(GET("$baseUrl$sUrl", headers)).awaitSuccess().useAsJsoup()
                val sEpisodes = parseEpisodesFromDoc(sDoc, if (multipleSeasons) sNum else null)
                episodes.addAll(sEpisodes)
            }
            return episodes.asReversed()
        }

        return parseEpisodesFromDoc(document, null).asReversed()
    }

    private fun parseEpisodesFromDoc(document: org.jsoup.nodes.Document, seasonNumber: Int?): List<SEpisode> {
        val sPrefix = if (seasonNumber != null) "[S$seasonNumber] " else ""
        return document.select("div.episodes-grid a.episode-card").mapNotNull { card ->
            val path = card.safeRelativePath() ?: return@mapNotNull null
            val rawTitle = card.selectFirst("h3.episode-title")?.text()?.trim() ?: "Episode"
            val epNumStr = Regex("""(?i)(?:episode|épisode)\s*(\d+(?:\.\d+)?)""").find(rawTitle)?.groupValues?.get(1) ?: "1"
            val langs = card.select(".episode-langs span, .lang-badge")
                .map { it.text().trim().uppercase() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")

            SEpisode.create().apply {
                url = path
                name = "$sPrefix$rawTitle"
                episode_number = epNumStr.toFloatOrNull() ?: 1f
                scanlator = langs.ifBlank { "VOSTFR" }
            }
        }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.newCall(GET("$baseUrl${episode.url}", headers)).awaitSuccess()
        val html = response.use { it.body.string() }
        val readersJson = Regex("""const\s+readers\s*=\s*(\{.*?\});""").find(html)?.groupValues?.get(1)
            ?: return emptyList()

        val json = JSONObject(readersJson)
        val hosters = mutableListOf<Hoster>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val langKey = keys.next()
            val array = json.optJSONArray(langKey) ?: continue
            val urls = (0 until array.length())
                .map { array.optString(it) }
                .filter { it.isNotBlank() }
                .distinct()

            if (urls.isNotEmpty()) {
                val lang = langKey.uppercase()
                hosters.add(
                    Hoster(
                        hosterName = lang,
                        hosterUrl = urls.first(),
                        internalData = "$lang|${urls.toJsonString()}",
                    ),
                )
            }
        }
        return hosters.sortHosters()
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|", limit = 2)
        val lang = data.getOrNull(0)?.uppercase() ?: "VOSTFR"
        val payload = data.getOrNull(1) ?: return emptyList()

        val urls = runCatching {
            payload.parseAs<List<String>>()
        }.getOrElse {
            if (payload.startsWith("http")) listOf(payload) else emptyList()
        }

        return urls.parallelCatchingFlatMap { playerUrl ->
            extractVideos(playerUrl, lang, supportedServers)
        }
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
