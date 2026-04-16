package eu.kanade.tachiyomi.animeextension.fr.adkami

import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.luluextractor.LuluExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ADKami :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "ADKami"
    override val lang = "fr"
    override val supportsLatest = true

    override val baseUrl by lazy {
        preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(CloudflareInterceptor(network.client))
        .build()

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/hentai-streaming?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div#hentai-block-populaire div.h-card, div.video-item-list").map {
            animeFromElement(it)
        }
        return AnimesPage(animes, document.select("a[rel=next]").isNotEmpty())
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/hentai-streaming?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.hentai-block-new div.h-card").map { element ->
            val anime = animeFromElement(element)
            anime.url = cleanUrl(anime.url)
            anime
        }
        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("video")
            .addQueryParameter("type", "4")
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.video-item-list").map {
            animeFromElement(it)
        }
        return AnimesPage(animes, document.select("a[rel=next]").isNotEmpty())
    }

    override fun getFilterList() = AnimeFilterList()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.title-header-video")?.text()?.substringBefore(" - Episode")
                ?: document.selectFirst(".fiche-info h1")?.text() ?: ""
            description = document.select("#look-video br").first()?.nextSibling()?.toString()?.trim()
                ?: document.select(".fiche-info h4[itemprop=alternateName]").next().text() ?: ""
            genre = document.select("a.label span[itemprop=genre]").joinToString { it.text() }
            thumbnail_url = document.selectFirst("#row-nav-episode img")?.attr("abs:src")
                ?: document.selectFirst(".fiche-info img")?.attr("abs:src")
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        var currentSeason = 1
        val seasonCount = document.select("#row-nav-episode ul li.saison").size

        val elements = document.select("#row-nav-episode ul li")
        if (elements.isNotEmpty()) {
            elements.forEach { el ->
                if (el.hasClass("saison")) {
                    currentSeason = el.text().filter { it.isDigit() }.toIntOrNull() ?: currentSeason
                    return@forEach
                }

                val a = el.selectFirst("a") ?: return@forEach
                val rawName = a.text().trim()
                val lang = when {
                    rawName.contains("vostfr", true) -> "VOSTFR"
                    rawName.contains("vf", true) -> "VF"
                    rawName.contains("raw", true) -> "RAW"
                    else -> "VOSTFR"
                }

                val parts = rawName.split(" ")
                val typeStr = parts.getOrNull(0)?.uppercase() ?: ""
                val numStr = parts.getOrNull(1)?.trimStart('0')?.ifEmpty { "0" } ?: "1"

                val finalType = when (typeStr) {
                    "OAV" -> "Episode OAV "
                    "ONA" -> "Episode ONA "
                    "SPECIAL", "SPÉCIAL" -> "Episode Special "
                    "FILM" -> "Film "
                    else -> "Episode "
                }

                episodes.add(
                    SEpisode.create().apply {
                        name = (if (seasonCount > 1) "Season $currentSeason " else "") + finalType + numStr
                        episode_number = numStr.toFloatOrNull() ?: 1f
                        setUrlWithoutDomain(a.attr("abs:href") + "?lang=$lang")
                    },
                )
            }
        } else {
            val sEp = SEpisode.create().apply {
                name = "Episode 1"
                episode_number = 1f
                url = response.request.url.toString().removePrefix(baseUrl) + "?lang=VOSTFR"
            }
            episodes.add(sEp)
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = (baseUrl + episode.url).toHttpUrl()
        val response = client.newCall(GET(url.newBuilder().query(null).build(), headers)).execute()
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        val lang = url.queryParameter("lang")?.ifBlank { "VOSTFR" } ?: "VOSTFR"

        val sibnetExtractor = SibnetExtractor(client)
        val doodExtractor = DoodExtractor(client)
        val voeExtractor = VoeExtractor(client, headers)
        val vidMolyExtractor = VidMolyExtractor(client, headers)
        val luluExtractor = LuluExtractor(client, headers)
        val filemoonExtractor = FilemoonExtractor(client)

        document.select("div.video-iframe").forEach { block ->
            val encodedUrl = block.attr("data-url")
            val serverName = block.attr("data-name").trim()
            val prefix = "($lang) $serverName - "

            if (encodedUrl.isNotBlank()) {
                val decodedUrl = decodeAdkamiUrl(encodedUrl)
                if (decodedUrl != null) {
                    when {
                        decodedUrl.contains("sibnet") -> videos.addAll(sibnetExtractor.videosFromUrl(decodedUrl, prefix))

                        decodedUrl.contains("dood") -> videos.addAll(doodExtractor.videosFromUrl(decodedUrl, prefix))

                        decodedUrl.contains("voe") -> videos.addAll(voeExtractor.videosFromUrl(decodedUrl, prefix))

                        decodedUrl.contains("vidmoly") -> videos.addAll(vidMolyExtractor.videosFromUrl(decodedUrl, prefix))

                        decodedUrl.contains("luluvid") || decodedUrl.contains("vidnest") || decodedUrl.contains("vidzy") -> {
                            videos.addAll(luluExtractor.videosFromUrl(decodedUrl, prefix))
                        }

                        decodedUrl.contains("filemoon") -> videos.addAll(filemoonExtractor.videosFromUrl(decodedUrl, prefix))

                        else -> videos.add(Video(decodedUrl, "$prefix Original", decodedUrl))
                    }
                }
            }
        }

        return videos.parallelCatchingFlatMap { video ->
            if (isLinkValid(video)) {
                listOf(Video(video.url, cleanQuality(video.quality), video.videoUrl, video.headers, video.subtitleTracks, video.audioTracks))
            } else {
                emptyList()
            }
        }.sort()
    }
    private fun isLinkValid(video: Video): Boolean {
        val url = video.videoUrl ?: return false
        if (url.isBlank()) return false
        return try {
            val headers = (video.headers ?: Headers.Builder().build()).newBuilder()
                .add("Range", "bytes=0-1")
                .build()
            val request = Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val contentType = response.header("Content-Type")?.lowercase() ?: ""
                !contentType.contains("text/html") && !contentType.contains("text/plain")
            }
        } catch (e: Exception) {
            false
        }
    } private fun cleanQuality(quality: String): String {
        var cleaned = quality.replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s"), "")
            .replace(Regex("\\s*\\(\\d+x\\d+\\)"), "")
            .replace(Regex("(?i)Sendvid:default"), "")
            .replace(Regex("(?i)Sibnet:default"), "")
            .replace(Regex("(?i)VK:default"), "")
            .replace(Regex("(?i)Vidmoly:default"), "")
            .replace(Regex("(?i)Filemoon:default"), "")
            .replace(" - - ", " - ")
            .trim()
            .removeSuffix("-")
            .trim()

        val servers = listOf("Vidmoly", "Sibnet", "Sendvid", "VK", "Filemoon", "Voe", "Dood", "Luluvid", "Streamtape", "Byse", "Lulustream")
        for (server in servers) {
            cleaned = cleaned.replace(Regex("(?i)$server\\s*-\\s*$server(?!:)", RegexOption.IGNORE_CASE), server)
            cleaned = cleaned.replace(Regex("(?i)$server:", RegexOption.IGNORE_CASE), "")
        }
        return cleaned.replace(Regex("\\s+"), " ").replace(" - - ", " - ").trim()
    }

    override fun List<Video>.sort(): List<Video> = this.sortedWith(
        compareBy(
            {
                Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            },
        ),
    ).reversed()

    override fun videoListParse(response: Response): List<Video> = emptyList()
    override fun videoUrlParse(response: Response): String = ""

    // ============================ Helpers =============================
    private fun animeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a[href*=/hentai/], a[href*=/anime/]") ?: return anime
        anime.setUrlWithoutDomain(link.attr("abs:href"))
        anime.title = element.selectFirst(".title")?.text()?.trim() ?: link.text().trim()
        anime.thumbnail_url = element.selectFirst(".visual img, img")?.let { img ->
            val url = img.attr("abs:data-original").ifBlank { img.attr("abs:src") }
            if (url.contains("base64")) {
                img.attr("abs:src")
            } else {
                url
            }
        }
        return anime
    }

    private fun cleanUrl(url: String): String {
        if (url.contains("/hentai/")) {
            val parts = url.split("/")
            if (parts.size > 3) {
                return "/${parts[1]}/${parts[2]}"
            }
        }
        return url
    }

    private fun decodeAdkamiUrl(encodedUrl: String): String? {
        val part = encodedUrl.substringAfter("embed/", "")
        if (part.isBlank()) return null

        return try {
            val e = String(Base64.decode(part, Base64.DEFAULT), Charsets.ISO_8859_1)
            var t = ""
            val n = "ETEfazefzeaZa13MnZEe"
            var i = 0
            for (o in e) {
                t += ((175 xor o.code) - n[i].code).toChar()
                i = if (i > n.length - 2) 0 else i + 1
            }
            t
        } catch (e: Exception) {
            null
        }
    }

    // ============================= Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = "URL de base"
            setDefaultValue(PREF_URL_DEFAULT)
            summary = baseUrl
            setOnPreferenceChangeListener { preference, newValue ->
                preferences.edit().putString(PREF_URL_KEY, newValue as String).commit()
                (preference as EditTextPreference).summary = newValue as String
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val PREF_URL_KEY = "preferred_baseUrl_v5"
        private const val PREF_URL_DEFAULT = "https://hentai.adkami.com"
    }
}
