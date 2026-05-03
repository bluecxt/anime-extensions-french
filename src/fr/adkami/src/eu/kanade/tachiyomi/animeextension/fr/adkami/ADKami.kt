package eu.kanade.tachiyomi.animeextension.fr.adkami

import android.util.Base64
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
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
import fr.bluecxt.core.Source
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class ADKami : Source() {

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

    override val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/hentai-streaming?page=$page", headers)).execute()
        val document = response.asJsoup()
        val animes = document.select("div#hentai-block-populaire div.h-card, div.video-item-list").map {
            animeFromElement(it)
        }
        return AnimesPage(animes, document.select("a[rel=next]").isNotEmpty())
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/hentai-streaming?page=$page", headers)).execute()
        val document = response.asJsoup()
        val animes = document.select("div.hentai-block-new div.h-card").map { element ->
            val anime = animeFromElement(element)
            anime.url = cleanUrl(anime.url)
            anime
        }
        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("video")
            .addQueryParameter("type", "4")
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .build()
        val response = client.newCall(GET(url, headers)).execute()
        val document = response.asJsoup()
        val animes = document.select("div.video-item-list").map {
            animeFromElement(it)
        }
        return AnimesPage(animes, document.select("a[rel=next]").isNotEmpty())
    }

    override fun getFilterList() = AnimeFilterList()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()

        anime.title = document.selectFirst("h1.title-header-video")?.text()?.substringBefore(" - Episode")
            ?: document.selectFirst(".fiche-info h1")?.text() ?: ""

        val descElement = document.selectFirst("p.m-hidden")
        anime.description = if (descElement != null) {
            val tempDesc = descElement.clone()
            tempDesc.select("a").remove()
            tempDesc.text().trim()
        } else {
            document.select("#look-video br").first()?.nextSibling()?.toString()?.trim()
                ?: document.select(".fiche-info h4[itemprop=alternateName]").next().text()
        }

        anime.genre = document.select("a.label span[itemprop=genre]").joinToString { it.text() }
        anime.thumbnail_url = document.selectFirst("#row-nav-episode img")?.attr("abs:src")
            ?: document.selectFirst(".fiche-info img")?.attr("abs:src")

        // TMDB Description Fallback
        val tmdbMetadata = fetchTmdbMetadata(anime.title)
        tmdbMetadata?.summary?.let { anime.description = it }

        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        var currentSeason = 1
        val seasonCount = document.select("#row-nav-episode ul li.saison").size

        val animeTitle = document.selectFirst(".fiche-info h1")?.text() ?: anime.title
        val tmdbMetadata = fetchTmdbMetadata(animeTitle)

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
                val num = numStr.toIntOrNull() ?: 1

                val sPrefix = if (seasonCount > 1) "[S$currentSeason] " else ""
                val sType = when (typeStr) {
                    "OAV", "OVA" -> "[OVA] "
                    "ONA" -> "[ONA] "
                    "SPECIAL", "SPÉCIAL" -> "[Special] "
                    "FILM", "MOVIE" -> "[Movie] "
                    else -> sPrefix
                }

                episodes.add(
                    SEpisode.create().apply {
                        name = "${sType}Episode $numStr"
                        episode_number = numStr.toFloatOrNull() ?: 1f
                        scanlator = "Season $currentSeason ($lang)"
                        setUrlWithoutDomain(a.attr("abs:href") + "?lang=$lang")

                        // TMDB Metadata
                        val epMeta = tmdbMetadata?.episodeSummaries?.get(num)
                        preview_url = epMeta?.second
                        summary = epMeta?.third
                    },
                )
            }
        } else {
            val sEp = SEpisode.create().apply {
                name = "Episode 1"
                episode_number = 1f
                url = anime.url + "?lang=VOSTFR"

                tmdbMetadata?.episodeSummaries?.get(1)?.let {
                    preview_url = it.first
                    summary = it.second
                }
            }
            episodes.add(sEp)
        }

        val mergedEpisodes = episodes.groupBy { it.name }.map { entry ->
            val first = entry.value.first()
            val combinedUrl = entry.value.map { it.url }.distinct().joinToString("|")
            val combinedLangs = entry.value.map { it.scanlator?.substringAfter("(")?.substringBefore(")") ?: "VOSTFR" }.distinct().joinToString(", ")

            SEpisode.create().apply {
                name = entry.key
                episode_number = first.episode_number
                url = combinedUrl
                scanlator = "Season ${first.scanlator?.substringBefore(" (") ?: "1"} ($combinedLangs)"
                preview_url = first.preview_url
                summary = first.summary
            }
        }

        return mergedEpisodes.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val urls = episode.url.split("|")
        val hosters = mutableListOf<Hoster>()

        urls.forEach { rawUrl ->
            val fullUrl = if (rawUrl.startsWith("http")) {
                rawUrl.toHttpUrl()
            } else {
                (baseUrl + (if (rawUrl.startsWith("/")) "" else "/") + rawUrl).toHttpUrl()
            }
            val response = client.newCall(GET(fullUrl.newBuilder().query(null).build(), headers)).execute()
            val document = response.asJsoup()
            val lang = fullUrl.queryParameter("lang")?.ifBlank { "VOSTFR" } ?: "VOSTFR"

            document.select("div.video-iframe").forEach { block ->
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
                        hosters.add(Hoster(hosterName = "($lang) $serverName", internalData = "$decodedUrl|$lang"))
                    }
                }
            }
        }
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|")
        val decodedUrl = data[0]
        val lang = data[1]
        val server = hoster.hosterName.substringAfter(") ")
        val prefix = "($lang) $server - "

        val sibnetExtractor = SibnetExtractor(client)
        val doodExtractor = DoodExtractor(client)
        val voeExtractor = VoeExtractor(client, headers)
        val vidMolyExtractor = VidMolyExtractor(client, headers)
        val luluExtractor = LuluExtractor(client, headers)
        val filemoonExtractor = FilemoonExtractor(client)
        val streamTapeExtractor = StreamTapeExtractor(client)
        val vidoExtractor = VidoExtractor(client)

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

                decodedUrl.contains("streamtape") -> streamTapeExtractor.videoFromUrl(decodedUrl, prefix)?.let { videos.add(it) }

                decodedUrl.contains("vidoza") -> videos.addAll(vidoExtractor.videosFromUrl(decodedUrl, prefix))

                else -> videos.add(Video(videoUrl = decodedUrl, videoTitle = "$prefix - Original", headers = headers))
            }
        } catch (_: Exception) {}

        return videos.map {
            Video(videoUrl = it.videoUrl, videoTitle = cleanQuality(it.videoTitle), headers = it.headers, subtitleTracks = it.subtitleTracks, audioTracks = it.audioTracks)
        }.filter { isLinkValid(it) }.sortVideos()
    }

    private fun isLinkValid(video: Video): Boolean {
        val url = video.videoUrl
        if (url.isBlank()) return false
        if (video.videoTitle.contains("Voe:MP4", ignoreCase = true) || video.videoTitle.contains("Unknown", ignoreCase = true)) return false
        if (url.contains(".m3u8")) return true

        return try {
            val request = Request.Builder()
                .url(url)
                .headers(video.headers ?: headers)
                .addHeader("Range", "bytes=0-1")
                .build()
            client.newCall(request).execute().use { response ->
                val code = response.code
                val contentType = response.header("Content-Type")?.lowercase() ?: ""
                (response.isSuccessful || code == 403) && !contentType.contains("text/html")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanQuality(quality: String): String {
        val servers = listOf("Vidmoly", "Sibnet", "Sendvid", "VK", "Filemoon", "Voe", "Doodstream", "Dood", "Luluvid", "Streamtape", "Lulustream", "Vidoza")
        var res = quality.replace(Regex("(?i)\\s*-\\s*\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)(?:/s)?"), "").replace(Regex("\\s*\\(\\d+x\\d+\\)"), "").replace(Regex("(?i):?default|mirror"), "").trim()
        val langMatch = Regex("\\((VOSTFR|VF|VA|RAW)\\)").find(res)
        val lang = langMatch?.value ?: "(VOSTFR)"
        res = res.replace(lang, "").trim()
        val server = servers.firstOrNull { res.contains(it, ignoreCase = true) } ?: ""
        res = res.replace(Regex("(?i)$server"), "").trim()
        var q = res.replace(Regex("^[:\\-\\s]+"), "").trim()
        if (q.equals(server, true) || q.equals("MP4", true) || q.equals("Original", true)) q = ""
        return when {
            q.isEmpty() -> "$lang $server".trim()
            else -> "$lang $server - $q".trim()
        }.replace(Regex("\\s+"), " ").trim()
    }

    override fun List<Video>.sortVideos(): List<Video> = this.sortedWith(
        compareBy {
            Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        },
    ).reversed()

    // ============================ Helpers =============================
    private fun animeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a[href*=/hentai/], a[href*=/anime/]") ?: return anime
        anime.setUrlWithoutDomain(link.attr("abs:href"))
        anime.title = element.selectFirst(".title")?.text()?.trim() ?: link.text().trim()
        element.selectFirst(".visual img, img")?.let { img ->
            val dataOriginal = img.attr("abs:data-original")
            anime.thumbnail_url = if (dataOriginal.isNotBlank()) dataOriginal else img.attr("abs:src")
        }
        return anime
    }

    private fun cleanUrl(url: String): String {
        if (url.contains("/hentai/")) {
            val parts = url.split("/")
            if (parts.size > 3) return "/${parts[1]}/${parts[2]}"
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = "URL de base"
            setDefaultValue(PREF_URL_DEFAULT)
            summary = baseUrl
            setOnPreferenceChangeListener { preference, newValue ->
                preferences.edit().putString(PREF_URL_KEY, newValue as String).apply()
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
