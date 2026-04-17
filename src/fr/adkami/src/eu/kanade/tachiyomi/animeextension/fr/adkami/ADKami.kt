package eu.kanade.tachiyomi.animeextension.fr.adkami

import android.util.Base64
import android.util.Log
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
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
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
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
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

            val descElement = document.selectFirst("p.m-hidden")
            description = if (descElement != null) {
                // Clone to keep original document
                val tempDesc = descElement.clone()
                // Remove genre links
                tempDesc.select("a").remove()
                tempDesc.text().trim()
            } else {
                document.select("#look-video br").first()?.nextSibling()?.toString()?.trim()
                    ?: document.select(".fiche-info h4[itemprop=alternateName]").next().text()
            }

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

                val parts = rawName.split(Regex("\\s+")).filter { it.isNotBlank() }
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
                        name = ((if (seasonCount > 1) "Season $currentSeason " else "") + finalType + numStr).trim()
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

        // Merge episodes with same name (VOSTFR/RAW/VF)
        val mergedEpisodes = episodes.groupBy { it.name }.map { entry ->
            val first = entry.value.first()
            val combinedUrl = entry.value.map { it.url }.distinct().joinToString("|")
            SEpisode.create().apply {
                name = entry.key
                episode_number = first.episode_number
                url = combinedUrl
            }
        }

        return mergedEpisodes.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        Log.d("ADKami", "Fetching video list for episode: ${episode.name}")

        // Handle merged episodes
        val urls = episode.url.split("|")

        return urls.parallelCatchingFlatMap { rawUrl ->
            val fullUrl = if (rawUrl.startsWith("http")) {
                rawUrl.toHttpUrl()
            } else {
                (baseUrl + (if (rawUrl.startsWith("/")) "" else "/") + rawUrl).toHttpUrl()
            }
            val response = client.newCall(GET(fullUrl.newBuilder().query(null).build(), headers)).execute()
            val document = response.asJsoup()
            val lang = fullUrl.queryParameter("lang")?.ifBlank { "VOSTFR" } ?: "VOSTFR"

            val pairList = document.select("div.video-iframe").mapNotNull { block ->
                val encodedUrl = block.attr("data-url")
                val rawServerName = block.attr("data-name").trim().lowercase()

                val serverName = when {
                    rawServerName.contains("videoza") || rawServerName.contains("vidoza") -> "Vidoza"
                    rawServerName.contains("dood") -> "Doodstream"
                    rawServerName.contains("voe") -> "Voe"
                    rawServerName.contains("streamtape") -> "Streamtape"
                    rawServerName.contains("vidmoly") -> "Vidmoly"
                    rawServerName.contains("lulu") -> "Lulustream"
                    rawServerName.contains("sibnet") -> "Sibnet"
                    rawServerName.contains("sendvid") -> "Sendvid"
                    rawServerName.contains("filemoon") -> "Filemoon"
                    rawServerName.contains("vk") -> "VK"
                    else -> rawServerName.replaceFirstChar { it.uppercase() }
                }

                if (encodedUrl.isNotBlank()) {
                    val decodedUrl = decodeAdkamiUrl(encodedUrl)
                    if (decodedUrl != null) {
                        Log.d("ADKami", "Found source: $serverName -> $decodedUrl")
                        Pair(decodedUrl, "($lang) $serverName")
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            val sibnetExtractor = SibnetExtractor(client)
            val doodExtractor = DoodExtractor(client)
            val voeExtractor = VoeExtractor(client, headers)
            val vidMolyExtractor = VidMolyExtractor(client, headers)
            val luluExtractor = LuluExtractor(client, headers)
            val filemoonExtractor = FilemoonExtractor(client)
            val streamTapeExtractor = StreamTapeExtractor(client)
            val vidoExtractor = VidoExtractor(client)

            pairList.parallelCatchingFlatMap { (decodedUrl, prefix) ->
                val videos = mutableListOf<Video>()
                try {
                    when {
                        decodedUrl.contains("sibnet") -> videos.addAll(sibnetExtractor.videosFromUrl(decodedUrl, prefix))

                        decodedUrl.contains("dood") || decodedUrl.contains("vide0") -> {
                            val url = decodedUrl.replace("vide0.net", "doodstream.com")
                                .replace("dood.to", "doodstream.com")
                            videos.addAll(doodExtractor.videosFromUrl(url, prefix))
                        }

                        decodedUrl.contains("voe") -> videos.addAll(voeExtractor.videosFromUrl(decodedUrl, prefix))

                        decodedUrl.contains("vidmoly") || decodedUrl.contains("vtbe") -> {
                            val url = decodedUrl.replace("vtbe.to", "vidmoly.net")
                            videos.addAll(vidMolyExtractor.videosFromUrl(url, prefix))
                        }

                        decodedUrl.contains("luluvid") || decodedUrl.contains("vidnest") || decodedUrl.contains("vidzy") || decodedUrl.contains("lulustream") -> {
                            val url = decodedUrl.replace("lulustream.com", "luluvid.com")
                            videos.addAll(luluExtractor.videosFromUrl(url, prefix))
                        }

                        decodedUrl.contains("filemoon") -> videos.addAll(filemoonExtractor.videosFromUrl(decodedUrl, prefix))

                        decodedUrl.contains("streamtape") -> {
                            streamTapeExtractor.videoFromUrl(decodedUrl, prefix)?.let { videos.add(it) }
                        }

                        decodedUrl.contains("vidoza") -> {
                            videos.addAll(vidoExtractor.videosFromUrl(decodedUrl, prefix))
                        }

                        else -> {
                            videos.add(Video(decodedUrl, "$prefix - Original", decodedUrl))
                        }
                    }
                } catch (_: Exception) {
                    Log.d("ADKami", "Extraction failed")
                }
                videos
            }
        }.map { video ->
            // Force reconstruction for clean labels
            val newQuality = cleanQuality(video.quality)
            Video(video.videoUrl ?: "", newQuality, video.videoUrl ?: "", video.headers)
        }.filter { video ->
            val url = video.videoUrl ?: return@filter false

            // Block dead Voe:MP4/Unknown links
            if (video.quality.contains("Voe:MP4", ignoreCase = true) || video.quality.contains("Unknown", ignoreCase = true)) {
                Log.d("ADKami", "Systematic block: ${video.quality}")
                return@filter false
            }

            // Skip ping for HLS (.m3u8)
            if (url.contains(".m3u8")) return@filter true

            val isValid = isLinkValid(video)
            isValid
        }.distinctBy { it.videoUrl }
            .sort()
    }

    private fun isLinkValid(video: Video): Boolean {
        val url = video.videoUrl ?: return false
        if (url.isBlank()) return false
        return try {
            val request = Request.Builder()
                .url(url)
                .headers(video.headers ?: headers)
                .addHeader("Range", "bytes=0-1")
                .build()
            client.newCall(request).execute().use { response ->
                val code = response.code
                val contentType = response.header("Content-Type")?.lowercase() ?: ""

                // Link is valid if not a web page (HTML/Text) and not a fatal error
                // Accept 403 as many servers block ping but not stream
                // Allow JSON for Streamtape
                val isValid = (response.isSuccessful || code == 403) &&
                    !contentType.contains("text/html") &&
                    !contentType.contains("text/plain") &&
                    (!contentType.contains("application/json") || url.contains("streamtape"))

                if (!isValid) {
                    Log.d("ADKami", "Filtering out: ${video.quality} (Code: $code, Type: $contentType, Url: $url)")
                }
                isValid
            }
        } catch (e: Exception) {
            Log.d("ADKami", "Link check failed for ${video.quality}: ${e.message}")
            false
        }
    }
    private fun cleanQuality(quality: String): String {
        val servers = listOf(
            "Vidmoly", "Sibnet", "Sendvid", "VK", "Filemoon", "Voe",
            "Doodstream", "Dood", "Luluvid", "Streamtape", "Byse",
            "Lulustream", "Vidoza",
        )

        var res = quality
            .replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)/s"), "") // Vitesse
            .replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)"), "") // Poids
            .replace(Regex("\\s*\\(\\d+x\\d+\\)"), "") // Dimensions
            .replace(Regex("(?i):?default"), "")
            .replace(Regex("(?i)mirror"), "")
            .replace(" - - ", " - ")
            .trim()

        // Extract language
        val langMatch = Regex("\\((VOSTFR|VF|VA|RAW)\\)").find(res)
        val lang = langMatch?.value ?: "(VOSTFR)"
        res = res.replace(lang, "").trim()

        // Identify server
        val server = servers.firstOrNull { res.contains(it, ignoreCase = true) } ?: ""
        res = res.replace(Regex("(?i)$server"), "").trim()

        // Clean remaining quality
        var q = res.replace(Regex("^[:\\-\\s]+"), "").trim()
        // Ignore generic labels
        if (q.equals(server, true) || q.equals("MP4", true) || q.equals("Original", true)) q = ""

        // Final label: (Lang) Server - Quality
        return when {
            q.isEmpty() -> "$lang $server".trim()
            else -> "$lang $server - $q".trim()
        }.replace(Regex("\\s+"), " ").trim()
    }

    override fun List<Video>.sort(): List<Video> = this.sortedWith(
        compareBy {
            Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        },
    ).reversed()

    override fun videoListParse(response: Response): List<Video> = emptyList()
    override fun videoUrlParse(response: Response): String = ""

    // ============================ Helpers =============================
    private fun animeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a[href*=/hentai/], a[href*=/anime/]") ?: return anime
        anime.setUrlWithoutDomain(link.attr("abs:href"))
        anime.title = element.selectFirst(".title")?.text()?.trim() ?: link.text().trim()
        val img = element.selectFirst(".visual img, img")
        if (img != null) {
            val dataOriginal = img.attr("abs:data-original")
            val src = img.attr("abs:src")
            anime.thumbnail_url = if (dataOriginal.isNotBlank()) {
                dataOriginal
            } else if (src.contains("base64")) {
                src
            } else {
                src
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
        } catch (_: Exception) {
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
                preferences.edit().putString(PREF_URL_KEY, newValue as String).apply()
                (preference as EditTextPreference).summary = newValue
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
