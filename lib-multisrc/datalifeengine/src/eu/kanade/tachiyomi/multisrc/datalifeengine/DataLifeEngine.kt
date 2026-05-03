package eu.kanade.tachiyomi.multisrc.datalifeengine

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

abstract class DataLifeEngine(
    override val name: String,
    private val defaultBaseUrl: String,
    override val lang: String,
) : ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val baseUrl: String
        get() = preferences.getString("preferred_baseUrl", defaultBaseUrl)!!

    override val supportsLatest = false

    open val categories = emptyArray<Pair<String, String>>()
    open val genres = emptyArray<Pair<String, String>>()

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================

    override fun popularAnimeSelector(): String = "div#dle-content > div.mov"

    override fun popularAnimeNextPageSelector(): String? = "span.navigation > span:not(.nav_ext) + a"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a[href]")!!.attr("href").toHttpUrl().encodedPath)
        thumbnail_url = element.selectFirst("img[src]")?.absUrl("src") ?: ""
        title = "${element.selectFirst("a[href]")!!.text()} ${element.selectFirst("span.block-sai")?.text() ?: ""}"
    }

    override fun popularAnimeRequest(page: Int): Request = if (page > 1) {
        GET("$baseUrl/page/$page/", headers)
    } else {
        GET(baseUrl, headers)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String? = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotEmpty()) {
        val body = "do=search&subaction=search&search_start=$page&full_search=0&result_from=${(page - 1) * 15 + 1}&story=$query"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        POST("$baseUrl/index.php?do=search", headers, body)
    } else {
        val genre = (filters.find { it is GenreFilter } as? GenreFilter)?.selected ?: ""
        if (genre.isNotEmpty()) {
            if (page > 1) {
                GET("$baseUrl/$genre/page/$page/", headers)
            } else {
                GET("$baseUrl/$genre/", headers)
            }
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeNextPageSelector(): String? = popularAnimeNextPageSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.mov-title")?.text() ?: ""
        description = document.selectFirst("div.mov-desc")?.text()
        thumbnail_url = document.selectFirst("div.mov-img img")?.absUrl("src")
        genre = document.select("div.mov-desc li:contains(Genre) a").joinToString { it.text() }
        author = document.select("div.mov-desc li:contains(Studio) a").joinToString { it.text() }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = "div.mov-desc li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val animeUrl = response.request.url.toString()

        val videoElements = document.select("div.video-player iframe, div.video-player video, div.video-player source")
        if (videoElements.isNotEmpty()) {
            episodeList.add(
                SEpisode.create().apply {
                    name = "Film / OAV"
                    episode_number = 1f
                    setUrlWithoutDomain(animeUrl)
                },
            )
        }

        return episodeList
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun seasonListSelector(): String = throw UnsupportedOperationException()
    override fun seasonFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    protected open fun hosterListSelector(): String = throw UnsupportedOperationException()
    protected open fun hosterFromElement(element: Element): Hoster = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val response = client.newCall(GET(hoster.internalData, headers)).execute()
        return videoListParse(response, hoster)
    }

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        document.select("div.video-player iframe").forEach { iframe ->
            val url = iframe.absUrl("src")
            videoList.addAll(extractVideosFromUrl(url))
        }

        return videoList
    }

    private fun extractVideosFromUrl(url: String): List<Video> {
        // Extraction logic would go here
        return emptyList()
    }

    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "preferred_baseUrl"
            title = "URL de base"
            setDefaultValue(defaultBaseUrl)
            summary = baseUrl
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString("preferred_baseUrl", newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    private class GenreFilter(val genres: Array<Pair<String, String>>) : AnimeFilter.Select<String>("Genre", genres.map { it.first }.toTypedArray()) {
        val selected: String
            get() = genres[state].second
    }
}
