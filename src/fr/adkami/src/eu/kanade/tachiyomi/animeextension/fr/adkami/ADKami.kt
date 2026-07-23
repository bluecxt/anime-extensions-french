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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelMap
import fr.bluecxt.core.ADKAMI_LOG
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.Source
import fr.bluecxt.core.utils.defaultHeaders
import fr.bluecxt.core.utils.safeRelativePath
import fr.bluecxt.core.utils.withDefaultHeaders
import keiyoushi.utils.parallelMapNotNull
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

class ADKami :
    Source(),
    CommonPreferences {

    override val name = "ADKami"
    override val lang = "fr"
    override val supportsLatest = true

    override val defaultBaseUrl = "https://hentai.adkami.com"

    override val supportedVoices = arrayOf("VOSTFR", "VF", "RAW", "VOSTA")

    override val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)
        .add("Referer", "$baseUrl/")

// ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/video?t=4&order=3&page=$page", headers)).awaitSuccess()
        return parseAnimesPage(response)
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage = if (page == 1) {
        val response = client.newCall(GET("$baseUrl/hentai-streaming?page=$page", headers)).awaitSuccess()
        parseLatestPage(response)
    } else {
        val response = client.newCall(GET("$baseUrl/video?t=4&order=3&page=$page", headers)).awaitSuccess()
        parseAnimesPage(response)
    }

    private fun parseLatestPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.h-card").map { element: Element ->
            SAnime.create().apply {
                val link = element.selectFirst("a")
                url = link?.safeRelativePath() ?: return@map this
                title = element.selectFirst(".title")?.text()?.trim() ?: ""
                thumbnail_url = element.selectFirst("img")?.attr("abs:src") ?: ""
            }
        }
        return AnimesPage(animes, true)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val response = client.newCall(GET("$baseUrl/hentai/$id", headers)).awaitSuccess()
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
            val response = client.newCall(GET("$baseUrl/hentai-streaming", headers)).awaitSuccess()
            return parseAnimesPage(response, "div.hentai-random-block:nth-child(2) > div.h-card")
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

        val response = client.newCall(GET(url.build(), headers)).awaitSuccess()
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
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess()
        val document = response.asJsoup()

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
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess()
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
                        url = a.safeRelativePath() + "?lang=$lang"
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
            val lang = fullUrl.queryParameter("lang")?.ifBlank { "VOSTFR" } ?: "VOSTFR"

            hosters.add(Hoster(hosterName = lang, hosterUrl = fullUrl.toString()))
        }
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val lang = hoster.hosterName
        val url = hoster.hosterUrl
        Log.d(ADKAMI_LOG, "anime url = $url")

        val document = client.newCall(GET(url, defaultHeaders(referer = baseUrl))).awaitSuccess().asJsoup()

        val urls = document.select("div.video-iframe").mapNotNull { iframe ->
            val encodedUrl = iframe.attr("data-url")
            decodeAdkamiUrl(encodedUrl)
        }

        return urls.parallelMap { playerUrl ->
            extractVideos(playerUrl, lang, supportedServers)
        }.flatten()
    }

    // ============================ Helpers =============================
    private fun parseAnimesPage(response: Response, selector: String = "div.video-item-list"): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(selector).map { element: Element ->
            SAnime.create().apply {
                val link: Element = element.selectFirst("a[href*=/hentai/], a[href*=/anime/]") ?: return@map this
                url = link.safeRelativePath()
                title = element.selectFirst(".title")?.text()?.trim() ?: link.text().trim()
                thumbnail_url = maxQuality(element.selectFirst("img")?.attr("data-original") ?: "")
                url = cleanUrl(url)
            }
        }
        val hasNextPage = document.select("div.pagination a:contains(Suivant), div.pagination a:has(button):not(.actuel), a[rel=next]").isNotEmpty()

        return AnimesPage(animes, hasNextPage)
    }

    private fun maxQuality(img: String): String = buildString {
        val link = img.toHttpUrl()
        val imgName = link.pathSegments.last()
        append(
            link.newBuilder()
                .encodedPath("/cover/250/$imgName")
                .toString(),
        )
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

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
