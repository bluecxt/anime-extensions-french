package eu.kanade.tachiyomi.animeextension.fr.adkami

import android.util.Base64
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import fr.bluecxt.core.Source
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
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
        val response = client.newCall(GET("$baseUrl/video?t=4&order=3&page=$page", headers)).execute()
        return parseAnimesPage(response)
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/hentai-streaming?page=$page", headers)).execute()
        return parseAnimesPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val response = client.newCall(GET("$baseUrl/hentai/$id", headers)).execute()
            val document = response.asJsoup()
            val anime = SAnime.create().apply {
                title = document.selectFirst(".fiche-info h1")?.text() ?: ""
                setUrlWithoutDomain(document.location())
                thumbnail_url = document.selectFirst(".fiche-info img")?.attr("abs:src")
            }
            return AnimesPage(listOf(anime), false)
        }

        val searchFilters = ADKamiCatalogueFilters.getSearchFilters(filters)

        // Random mode
        if (searchFilters.randomOnly) {
            val response = client.newCall(GET("$baseUrl/hentai-streaming", headers)).execute()
            return parseAnimesPage(response, "div.hentai-random-block:nth-child(2) > div.h-card")
        }

        // Simple search uses the AJAX API as requested
        if (query.isNotBlank() && searchFilters.isDefault()) {
            val body = okhttp3.FormBody.Builder()
                .add("query", query)
                .build()

            val ajaxHeaders = headersBuilder()
                .add("Accept", "*/*")
                .add("Accept-Language", "fr,fr-FR;q=0.9")
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .add("Origin", baseUrl)
                .add("Referer", "$baseUrl/hentai-streaming")
                .build()

            val response = client.newCall(
                okhttp3.Request.Builder()
                    .url("$baseUrl/api/search/hentai")
                    .post(body)
                    .headers(ajaxHeaders)
                    .build(),
            ).execute()

            return parseAnimesPage(response)
        }

        // Advanced search uses the browse page
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("video")
            .addQueryParameter("t", "4")
            .addQueryParameter("search", query)
            .addQueryParameter("order", searchFilters.order)
            .addQueryParameter("s", searchFilters.status)
            .addQueryParameter("p", searchFilters.pays)
            .addQueryParameter("e", searchFilters.episode)
            .addQueryParameter("q", searchFilters.quality)
            .addQueryParameter("n", searchFilters.noteMin)
            .addQueryParameter("n2", searchFilters.noteMax)
            .addQueryParameter("page", page.toString())

        if (searchFilters.vfOnly) {
            url.addQueryParameter("v", "1")
        }

        searchFilters.genres.forEach { genreId ->
            url.addQueryParameter("genres[]", genreId)
        }

        val response = client.newCall(GET(url.build(), headers)).execute()
        return parseAnimesPage(response)
    }

    override fun getFilterList() = ADKamiCatalogueFilters.FILTER_LIST

    private object ADKamiCatalogueFilters {
        private val filterData by lazy {
            val jsonStream = ADKami::class.java.getResourceAsStream("filters.json")
            val jsonString = jsonStream?.bufferedReader()?.use { it.readText() } ?: "{}"
            try {
                Json.decodeFromString<Map<String, List<List<String>>>>(jsonString)
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun getOptions(key: String): List<Pair<String, String>> {
            val list = filterData[key] ?: return emptyList()
            return list.map { it[0] to it[1] }
        }

        class OrderFilter : AnimeFilter.Select<String>("Trier par", getOptions("ORDER").map { it.first }.toTypedArray().ifEmpty { arrayOf("Popularité") }, 0)
        class StatusFilter : AnimeFilter.Select<String>("Statut", getOptions("STATUS").map { it.first }.toTypedArray().ifEmpty { arrayOf("Tout") }, 0)
        class PaysFilter : AnimeFilter.Select<String>("Pays", getOptions("PAYS").map { it.first }.toTypedArray().ifEmpty { arrayOf("Tous") }, 0)
        class EpisodeFilter : AnimeFilter.Select<String>("Nombre d'épisodes", getOptions("EPISODES").map { it.first }.toTypedArray().ifEmpty { arrayOf("Tous") }, 0)
        class QualityFilter : AnimeFilter.Select<String>("Qualité", getOptions("QUALITY").map { it.first }.toTypedArray().ifEmpty { arrayOf("Tout") }, 0)
        class NoteMinFilter : AnimeFilter.Select<String>("Note Min", getOptions("NOTE_MIN").map { it.first }.toTypedArray().ifEmpty { arrayOf("Tous") }, 0)
        class NoteMaxFilter : AnimeFilter.Select<String>("Note Max", getOptions("NOTE_MAX").map { it.first }.toTypedArray().ifEmpty { arrayOf("10") }, 0)
        class GenresFilter : CheckBoxFilterList("Genres", getOptions("GENRES").map { CheckBoxVal(it.first, false) })
        class VfFilter : AnimeFilter.CheckBox("VF uniquement", false)
        class RandomFilter : AnimeFilter.CheckBox("Aléatoire", false)

        val FILTER_LIST get() = AnimeFilterList(
            OrderFilter(),
            StatusFilter(),
            PaysFilter(),
            EpisodeFilter(),
            QualityFilter(),
            NoteMinFilter(),
            NoteMaxFilter(),
            GenresFilter(),
            VfFilter(),
            RandomFilter(),
        )

        data class SearchFilters(
            val order: String = "3",
            val status: String = "",
            val pays: String = "",
            val episode: String = "",
            val quality: String = "",
            val noteMin: String = "",
            val noteMax: String = "10",
            val vfOnly: Boolean = false,
            val randomOnly: Boolean = false,
            val genres: List<String> = emptyList(),
        ) {
            fun isDefault() = order == "3" && status == "" && pays == "" && episode == "" &&
                quality == "" && noteMin == "" && noteMax == "10" && !vfOnly && !randomOnly && genres.isEmpty()
        }

        fun getSearchFilters(filters: AnimeFilterList): SearchFilters {
            if (filters.isEmpty()) return SearchFilters()

            return SearchFilters(
                order = getOptions("ORDER").getOrNull(filters.filterIsInstance<OrderFilter>().firstOrNull()?.state ?: 0)?.second ?: "3",
                status = getOptions("STATUS").getOrNull(filters.filterIsInstance<StatusFilter>().firstOrNull()?.state ?: 0)?.second ?: "",
                pays = getOptions("PAYS").getOrNull(filters.filterIsInstance<PaysFilter>().firstOrNull()?.state ?: 0)?.second ?: "",
                episode = getOptions("EPISODES").getOrNull(filters.filterIsInstance<EpisodeFilter>().firstOrNull()?.state ?: 0)?.second ?: "",
                quality = getOptions("QUALITY").getOrNull(filters.filterIsInstance<QualityFilter>().firstOrNull()?.state ?: 0)?.second ?: "",
                noteMin = getOptions("NOTE_MIN").getOrNull(filters.filterIsInstance<NoteMinFilter>().firstOrNull()?.state ?: 0)?.second ?: "",
                noteMax = getOptions("NOTE_MAX").getOrNull(filters.filterIsInstance<NoteMaxFilter>().firstOrNull()?.state ?: 0)?.second ?: "10",
                vfOnly = filters.filterIsInstance<VfFilter>().firstOrNull()?.state ?: false,
                randomOnly = filters.filterIsInstance<RandomFilter>().firstOrNull()?.state ?: false,
                genres = filters.parseCheckbox<GenresFilter>(getOptions("GENRES")),
            )
        }

        private inline fun <reified R> AnimeFilterList.parseCheckbox(
            options: List<Pair<String, String>>,
        ): List<String> = (this.filterIsInstance<R>().firstOrNull() as? CheckBoxFilterList)?.state
            ?.mapNotNull { checkbox ->
                if (checkbox.state) {
                    options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            } ?: emptyList()

        open class CheckBoxFilterList(name: String, values: List<CheckBoxVal>) : AnimeFilter.Group<CheckBoxVal>(name, values)
        class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)
    }

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

        anime.background_url = document.selectFirst("div.blocshadow > div.col-12 > img")?.attr("abs:src")

        // Site Metadata Extraction
        val infoRows = document.select("div.fiche-info > div.row > p")

        anime.author = infoRows.find { it.text().contains("Auteur", true) }
            ?.text()?.substringAfter(":")?.trim()

        anime.artist = infoRows.find { it.text().contains("Studio", true) }
            ?.text()?.substringAfter(":")?.trim()

        val dateText = infoRows.find {
            it.text().contains("Date", true) || it.text().contains("Sortie", true) || it.text().contains("Diffusion", true)
        }?.text()?.substringAfter(":")?.trim()

        if (!dateText.isNullOrBlank()) {
            anime.description = "Date de sortie : $dateText\n\n${anime.description ?: ""}"
        }

        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        val elements = document.select("#row-nav-episode ul li")
        if (elements.isNotEmpty()) {
            elements.forEach { el ->
                if (el.hasClass("saison")) return@forEach

                val a = el.selectFirst("a") ?: return@forEach
                val rawName = a.text().trim()
                val lang = when {
                    rawName.contains("vostfr", true) -> "VOSTFR"
                    rawName.contains("vf", true) -> "VF"
                    rawName.contains("vosta", true) || rawName.contains("en", true) -> "VOSTA"
                    rawName.contains("raw", true) -> "RAW"
                    else -> "VOSTFR"
                }

                val parts = rawName.split(Regex("\\s+")).filter { it.isNotBlank() }
                val typeStr = parts.getOrNull(0)?.uppercase() ?: ""
                val numStr = parts.getOrNull(1)?.trimStart('0')?.ifEmpty { "0" } ?: "1"

                val isOav = rawName.contains("OAV", true) || rawName.contains("OVA", true)
                val isSpecial = rawName.contains("Special", true) || rawName.contains("Spécial", true) || typeStr == "SPECIAL"
                val isMovie = rawName.contains("Film", true) || rawName.contains("Movie", true) || typeStr == "FILM"
                val isOna = typeStr == "ONA" || rawName.contains("ONA", true)

                val sType = when {
                    isOav -> "[OAV] "
                    isMovie -> "[Movie] "
                    isSpecial -> "[Special] "
                    isOna -> "[ONA] "
                    else -> ""
                }

                episodes.add(
                    SEpisode.create().apply {
                        name = "${sType}Episode $numStr"
                        episode_number = numStr.toFloatOrNull() ?: 1f
                        scanlator = lang
                        setUrlWithoutDomain(a.attr("abs:href") + "?lang=$lang")
                    },
                )
            }
        } else {
            val sEp = SEpisode.create().apply {
                name = "Episode 1"
                episode_number = 1f
                url = anime.url + "?lang=VOSTFR"
            }
            episodes.add(sEp)
        }

        val hasSpecialContent = episodes.any { it.name.startsWith("[") }

        val mergedEpisodes = episodes.groupBy { it.name }.map { entry ->
            val first = entry.value.first()
            val combinedUrl = entry.value.map { it.url }.distinct().joinToString("|")
            val combinedLangs = entry.value.map { it.scanlator ?: "VOSTFR" }.distinct().joinToString(", ")

            SEpisode.create().apply {
                name = if (hasSpecialContent && !entry.key.startsWith("[")) "[S1] ${entry.key}" else entry.key
                episode_number = first.episode_number
                url = combinedUrl
                scanlator = combinedLangs
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
        val langMatch = Regex("\\((VOSTFR|VF|VA|VOSTA|RAW)\\)").find(res)
        val lang = langMatch?.value ?: "(VOSTFR)"
        res = res.replace(lang, "").trim()
        val server = servers.firstOrNull { res.contains(it, ignoreCase = true) } ?: ""
        res = res.replace(Regex("(?i)$server"), "").trim()
        var q = res.replace(Regex("^[:\\-\\s]+"), "").trim()
        if (q.equals(server, true) || q.equals("MP4", true) || q.equals("Original", true)) q = ""

        // Harmonization: Remove server name repetition
        return when {
            q.isEmpty() -> "$lang $server".trim()
            else -> "$lang $server - $q".trim()
        }.replace(Regex("\\s+"), " ").trim()
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!

        return this.sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains("($voices)", true) }
                .thenByDescending { it.hosterName.contains(player, true) },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!

        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains("($voices)", true) }
                .thenByDescending { it.videoTitle.contains(player, true) }
                .thenByDescending {
                    Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    // ============================ Helpers =============================
    private fun parseAnimesPage(response: Response, selector: String = ".hentai-block-new > div:nth-child(2) > div.h-card, div.video-item-list, body > div.h-card"): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(selector).map { element: Element ->
            SAnime.create().apply {
                val link = element.selectFirst("a[href*=/hentai/], a[href*=/anime/]") ?: return@map this
                setUrlWithoutDomain(link.attr("abs:href"))
                title = element.selectFirst(".title")?.text()?.trim() ?: link.text().trim()
                element.selectFirst(".visual img, img")?.let { img ->
                    val dataOriginal = img.attr("abs:data-original")
                    thumbnail_url = (if (dataOriginal.isNotBlank()) dataOriginal else img.attr("abs:src")).replace("mini", "cover")
                }
                url = cleanUrl(url)
            }
        }
        val hasNextPage = document.select("div.pagination a:contains(Suivant), div.pagination a:has(button):not(.actuel), a[rel=next]").isNotEmpty()

        return AnimesPage(animes, hasNextPage)
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
            val e = String(Base64.decode(part, Base64.DEFAULT), java.nio.charset.StandardCharsets.ISO_8859_1)
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
                preferences.edit().putString(PREF_URL_KEY, newValue.toString()).apply()
                (preference as EditTextPreference).summary = newValue.toString()
                true
            }
        }.also(screen::addPreference)

        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = "Priorité de langue"
            entries = arrayOf("VOSTFR", "VF", "VOSTA", "RAW")
            entryValues = arrayOf("VOSTFR", "VF", "VOSTA", "RAW")
            setDefaultValue(PREF_VOICES_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_PLAYER_KEY
            title = "Serveur préféré"
            entries = arrayOf("Vidmoly", "Sibnet", "Sendvid", "VK", "Voe", "Doodstream")
            entryValues = arrayOf("Vidmoly", "Sibnet", "Sendvid", "VK", "Voe", "Doodstream")
            setDefaultValue(PREF_PLAYER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val PREF_URL_KEY = "preferred_baseUrl_v5"
        private const val PREF_URL_DEFAULT = "https://hentai.adkami.com"

        private const val PREF_VOICES_KEY = "preferred_voices"
        private const val PREF_VOICES_DEFAULT = "VOSTFR"

        private const val PREF_PLAYER_KEY = "preferred_player"
        private const val PREF_PLAYER_DEFAULT = "Vidmoly"
    }
}
