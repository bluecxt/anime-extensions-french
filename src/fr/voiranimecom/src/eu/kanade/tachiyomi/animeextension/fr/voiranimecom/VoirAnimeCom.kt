package eu.kanade.tachiyomi.animeextension.fr.voiranimecom

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class VoirAnimeCom :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "VoirAnime.com"

    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!
    }

    override val lang = "fr"

    override val supportsLatest = true

    override val client = network.client

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    private val preferences by getPreferencesLazy()

    private val json: Json by injectLazy()

    @Serializable
    data class AnimeUrl(
        val vostfr: String? = null,
        val vf: String? = null,
    )

    @Serializable
    data class EpisodeUrl(
        val vostfr: String? = null,
        val vf: String? = null,
    )

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/?page=$page&m_orderby=views", headers)

    override fun popularAnimeSelector(): String = "div.page-listing-item .page-item-detail, .c-tabs-item__content, .search-wrap .page-item-detail"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst(".post-title h3 a, .post-title a")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        title = link.text()
        thumbnail_url = extractImage(element)
    }

    private fun extractImage(element: Element): String? {
        val img = element.selectFirst("img") ?: return null

        // Try srcset first for high res
        val srcset = img.attr("srcset").takeIf { it.isNotEmpty() }
            ?: img.attr("data-srcset").takeIf { it.isNotEmpty() }

        if (srcset != null) {
            val urls = srcset.split(",").map { it.trim().split(" ")[0] }
            // Prefer URLs that don't look like thumbnails (110x150, 175x238, etc.)
            val bestUrl = urls.find { !it.contains(Regex("""\d+x\d+""")) } ?: urls.last()
            return if (bestUrl.startsWith("http")) bestUrl else img.absUrl(if (srcset == img.attr("srcset")) "srcset" else "data-srcset").substringBefore(" ")
        }

        val url = img.attr("abs:data-src").takeIf { it.isNotEmpty() }
            ?: img.attr("abs:data-lazy-src").takeIf { it.isNotEmpty() }
            ?: img.attr("abs:src").takeIf { it.isNotEmpty() && !it.contains("data:image") }

        return url
    }
    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map(::popularAnimeFromElement)
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(unifyAnimes(animes), hasNextPage)
    }

    private fun unifyAnimes(animes: List<SAnime>): List<SAnime> {
        val cleanRegex = Regex("(?i)\\s*\\((VF|VOSTFR|VOST|UNCUT)\\)")
        return animes.distinctBy { it.url }.groupBy {
            it.title.replace(cleanRegex, "").trim()
        }.map { (title, versions) ->
            val anime = SAnime.create()
            anime.title = title
            anime.thumbnail_url = versions.firstOrNull { it.thumbnail_url != null }?.thumbnail_url ?: versions.first().thumbnail_url

            val urlMap = AnimeUrl(
                vostfr = versions.firstOrNull { !it.title.contains("VF", true) }?.url,
                vf = versions.firstOrNull { it.title.contains("VF", true) }?.url,
            )
            anime.url = json.encodeToString(urlMap)
            anime
        }
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination a.next, .wp-pagenavi a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/?page=$page&m_orderby=latest", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return GET("$baseUrl/anime/$id", headers)
        }
        return GET("$baseUrl/page/$page/?s=$query&post_type=wp-manga", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val urlMap = json.decodeFromString<AnimeUrl>(anime.url)
        val prefLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val targetUrl = if (prefLang == "VF") urlMap.vf ?: urlMap.vostfr else urlMap.vostfr ?: urlMap.vf
        if (targetUrl == null) return anime

        val response = client.newCall(GET(baseUrl + targetUrl, headers)).execute()
        val document = response.asJsoup()

        return SAnime.create().apply {
            title = anime.title
            description = document.select(".entry-content[itemprop=description], .description-summary .summary-content").text()
            genre = document.select(".genxed a, .genres-content a").joinToString { it.text() }
            author = document.select(".item:contains(Studio) a, .summary-content:contains(Studio) a").joinToString { it.text() }
            status = when (document.select("span:contains(Status) i, .post-status .summary-content").text().lowercase()) {
                "ongoing", "en cours" -> SAnime.ONGOING
                "completed", "terminé" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            thumbnail_url = anime.thumbnail_url
            url = targetUrl
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "div.list-chapter div.chapter-item, li.wp-manga-chapter"

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val urlMap = json.decodeFromString<AnimeUrl>(anime.url)
        val episodes = mutableListOf<SEpisode>()

        val fetchFrom = { path: String? ->
            if (path != null) {
                val response = client.newCall(GET(baseUrl + path, headers)).execute()
                val document = response.asJsoup()
                document.select(episodeListSelector()).map { element ->
                    val link = element.selectFirst("a")!!
                    val fullText = element.selectFirst(".chapter")?.text() ?: link.text()
                    val epNumRaw = Regex("""\d+""").findAll(fullText).lastOrNull()?.value ?: "1"
                    val epNum = epNumRaw.trimStart('0').ifEmpty { "0" }
                    epNum to link.attr("abs:href")
                }
            } else {
                emptyList()
            }
        }

        val vostfrEps = fetchFrom(urlMap.vostfr)
        val vfEps = fetchFrom(urlMap.vf)

        val allNums = (vostfrEps.map { it.first } + vfEps.map { it.first }).distinct()

        allNums.forEach { num ->
            val ep = SEpisode.create()
            ep.name = "Épisode $num"
            ep.episode_number = num.toFloatOrNull() ?: 0f

            val epUrlMap = EpisodeUrl(
                vostfr = vostfrEps.firstOrNull { it.first == num }?.second,
                vf = vfEps.firstOrNull { it.first == num }?.second,
            )
            ep.url = json.encodeToString(epUrlMap)
            ep.scanlator = listOfNotNull(
                if (epUrlMap.vostfr != null) "VOSTFR" else null,
                if (epUrlMap.vf != null) "VF" else null,
            ).joinToString(" / ")
            episodes.add(ep)
        }

        // Le plus petit numéro en bas de la liste
        return episodes.sortedByDescending { it.episode_number }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListSelector(): String = "unused"

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urlMap = json.decodeFromString<EpisodeUrl>(episode.url)
        val videoList = mutableListOf<Video>()

        val fetchVideos = suspend { url: String?, prefix: String ->
            if (url != null) {
                val response = client.newCall(GET(url, headers)).execute()
                val document = response.asJsoup()

                val scripts = document.select("script")
                val sourceScript = scripts.find { it.html().contains("var thisChapterSources =") }?.html() ?: ""
                val jsonContent = sourceScript.substringAfter("var thisChapterSources = ", "").substringBefore("};")

                if (jsonContent.isNotEmpty()) {
                    val rawJson = jsonContent + "}"
                    val sources = try {
                        json.decodeFromString<Map<String, String>>(rawJson)
                    } catch (e: Exception) {
                        val iframeRegex = Regex("""<iframe[^>]+src=\\?["']([^\\"']+)""")
                        iframeRegex.findAll(jsonContent).map { it.groupValues[1] }.associateBy { "Player" }
                    }

                    val doodExtractor = DoodExtractor(client)
                    val sibnetExtractor = SibnetExtractor(client)
                    val voeExtractor = VoeExtractor(client, headers)
                    val vidMolyExtractor = VidMolyExtractor(client, headers)
                    val okruExtractor = OkruExtractor(client)
                    val vkExtractor = VkExtractor(client, headers)
                    val filemoonExtractor = FilemoonExtractor(client)

                    sources.values.forEach { iframeHtml ->
                        val playerUrl = if (iframeHtml.startsWith("http")) {
                            iframeHtml
                        } else {
                            Jsoup.parse(iframeHtml).selectFirst("iframe")?.attr("src") ?: ""
                        }

                        if (playerUrl.isNotEmpty()) {
                            val absoluteUrl = if (playerUrl.startsWith("//")) "https:$playerUrl" else playerUrl
                            val cleanedUrl = absoluteUrl.replace("\\/", "/")

                            val videos = when {
                                cleanedUrl.contains("vidmoly") -> vidMolyExtractor.videosFromUrl(cleanedUrl, prefix)
                                cleanedUrl.contains("voe") -> voeExtractor.videosFromUrl(cleanedUrl, prefix)
                                cleanedUrl.contains("dood") -> doodExtractor.videosFromUrl(cleanedUrl, prefix)
                                cleanedUrl.contains("sibnet") -> sibnetExtractor.videosFromUrl(cleanedUrl, prefix)
                                cleanedUrl.contains("ok.ru") -> okruExtractor.videosFromUrl(cleanedUrl, prefix)
                                cleanedUrl.contains("vk.com") -> vkExtractor.videosFromUrl(cleanedUrl, prefix)
                                cleanedUrl.contains("cleanedUrl") -> filemoonExtractor.videosFromUrl(cleanedUrl, prefix)
                                else -> emptyList()
                            }
                            videoList.addAll(videos)
                        }
                    }
                }
            }
        }

        fetchVideos(urlMap.vostfr, "(VOSTFR) ")
        fetchVideos(urlMap.vf, "(VF) ")

        return videoList.sort()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QLTY_KEY, PREF_QLTY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(lang) }
                .thenByDescending { it.quality.contains(quality) }
                .thenByDescending { it.quality.contains("1080") || it.quality.contains("720") },
        )
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Préférence des voix"
            entries = arrayOf("VF", "VOSTFR")
            entryValues = arrayOf("VF", "VOSTFR")
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_LANG_KEY, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QLTY_KEY
            title = "Qualité préférée"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QLTY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_QLTY_KEY, newValue as String).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = "URL de base"
            setDefaultValue(PREF_URL_DEFAULT)
            summary = baseUrl
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_URL_KEY, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val PREF_URL_KEY = "preferred_baseUrl"
        private const val PREF_URL_DEFAULT = "https://v6.voiranime.com"
        private const val PREF_QLTY_KEY = "preferred_quality"
        private const val PREF_QLTY_DEFAULT = "1080"
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_DEFAULT = "VOSTFR"
    }
}
