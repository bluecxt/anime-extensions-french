// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelMap
import fr.bluecxt.core.ADKAMI_LOG
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.Source
import fr.bluecxt.core.filters.FilterSpec
import fr.bluecxt.core.model.VoiceLanguage.RAW
import fr.bluecxt.core.model.VoiceLanguage.VF
import fr.bluecxt.core.model.VoiceLanguage.VOSTFR
import fr.bluecxt.core.utils.defaultHeaders
import fr.bluecxt.core.utils.safeRelativePath
import fr.bluecxt.core.utils.withDefaultHeaders
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.useAsJsoup
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

    override val supportedVoices = setOf(VOSTFR, VF, RAW)

    override val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
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
        val document = response.useAsJsoup()
        val animes = document.select("div.h-card").mapNotNull { element: Element ->
            SAnime.create().apply {
                url = element.selectFirst("a")?.safeRelativePath() ?: return@mapNotNull null
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
            val document = response.useAsJsoup()
            val anime = SAnime.create().apply {
                title = document.selectFirst(".fiche-info h1")?.text() ?: ""
                setUrlWithoutDomain(document.location())
                thumbnail_url = document.selectFirst(".fiche-info img")?.attr("abs:src")
            }
            return AnimesPage(listOf(anime), false)
        }

        // Random mode
        if (filters.getParam("random") == "1") {
            val response = client.newCall(GET("$baseUrl/hentai-streaming", headers)).awaitSuccess()
            return parseAnimesPage(response, "div.hentai-random-block:nth-child(2) > div.h-card")
        }

        // Advanced search uses the browse page
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("video")
            .addQueryParameter("t", "4")
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .applyFilters(filters)
            .build()

        val response = client.newCall(GET(url, headers)).awaitSuccess()
        return parseAnimesPage(response)
    }

    override val customFilters: List<FilterSpec>
        get() = listOf(
            select("Trier par", "order", ORDER_OPTIONS),
            select("Statut", "s", STATUS_OPTIONS),
            select("Pays", "p", PAYS_OPTIONS),
            select("Nombre d'épisodes", "e", EPISODES_OPTIONS),
            select("Qualité", "q", QUALITY_OPTIONS),
            select("Note Min", "n", NOTE_MIN_OPTIONS),
            select("Note Max", "n2", NOTE_MAX_OPTIONS),
            group("Genres", "genres[]", GENRES_OPTIONS),
            checkBox("VF uniquement", "v", "1"),
            checkBox("Aléatoire", "random", "1"),
        )

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).awaitSuccess()
        val document = response.useAsJsoup()

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
        val document = response.useAsJsoup()
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

        val document = client.newCall(GET(url, defaultHeaders(referer = baseUrl))).awaitSuccess().useAsJsoup()

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
        val document = response.useAsJsoup()
        val animes = document.select(selector).mapNotNull { element: Element ->
            SAnime.create().apply {
                val link = element.selectFirst("a[href*=/hentai/], a[href*=/anime/]")
                url = link?.safeRelativePath() ?: return@mapNotNull null
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

        private val ORDER_OPTIONS = arrayOf(
            "Popularité" to "3",
            "Note" to "1",
            "Nombre de votants" to "2",
            "Alphabétique" to "0",
        )

        private val STATUS_OPTIONS = arrayOf(
            "Tout" to "",
            "En cours" to "1",
            "Terminée" to "2",
            "Abandonnée" to "3",
        )

        private val PAYS_OPTIONS = arrayOf(
            "Tous" to "",
            "Inconnue" to "0",
            "Japon" to "1",
            "Chine" to "2",
            "Corée" to "3",
            "France" to "4",
            "Etats-unis" to "5",
            "Taïwan" to "6",
            "Thaïlande" to "7",
        )

        private val EPISODES_OPTIONS = arrayOf(
            "Tous" to "",
            "Peu [1, 13]" to "1",
            "Normal [14, 26]" to "2",
            "Beaucoup [27, +∞[" to "3",
        )

        private val QUALITY_OPTIONS = arrayOf(
            "Tout" to "",
            "Non censuré (NC)" to "1",
            "Blu-ray (BD)" to "2",
        )

        private val NOTE_MIN_OPTIONS = arrayOf(
            "Tous" to "",
            "10" to "10",
            "9" to "9",
            "8" to "8",
            "7" to "7",
            "6" to "6",
            "5" to "5",
            "4" to "4",
            "3" to "3",
            "2" to "2",
            "1" to "1",
        )

        private val NOTE_MAX_OPTIONS = arrayOf(
            "10" to "10",
            "9" to "9",
            "8" to "8",
            "7" to "7",
            "6" to "6",
            "5" to "5",
            "4" to "4",
            "3" to "3",
            "2" to "2",
            "1" to "1",
        )

        private val GENRES_OPTIONS = listOf(
            "Action" to "1", "Amitié" to "3", "Aventure" to "2", "Combat" to "4", "Comédie" to "5",
            "Contes & Récits" to "6", "Cyber & Mecha" to "7", "Dark Fantasy" to "8", "Drame" to "9",
            "Ecchi" to "10", "Educatif" to "11", "Énigme & Policier" to "12", "Épique & Héroique" to "13",
            "Espace & Sci-Fiction" to "14", "Familial & Jeunesse" to "15", "Fantastique & Mythe" to "16",
            "Fantasy" to "30", "Gastronomie" to "39", "Gender Bender" to "61", "Harem" to "32",
            "Historique" to "18", "Horreur" to "19", "Idols" to "38", "Inceste" to "36",
            "Magical Girl" to "20", "Mature" to "26", "Moe" to "25", "Monster Girl" to "71",
            "Musical" to "21", "Mystère" to "31", "Psychologique" to "22", "Romance" to "34",
            "School Life" to "29", "Sport" to "23", "Surnaturel" to "33", "Survival Game" to "40",
            "Thriller" to "35", "Tokusatsu" to "41", "Tranche de vie" to "24", "Triangle Amoureux" to "37",
            "Yaoi" to "27", "Yuri" to "28", "Hentai" to "17", "Gyaru" to "70", "Isekai" to "42",
            "Magie" to "43", "Ahegao" to "45", "Anal" to "46", "BDSM" to "44", "Blow Job" to "63",
            "Creampie" to "68", "Foot Job" to "47", "Futanari" to "48", "Gang Bang" to "58",
            "Giga seins" to "59", "Gros seins" to "53", "Hand Job" to "66", "Infirmière / Nurse" to "51",
            "Loli" to "62", "Maid" to "49", "Masturbation" to "50", "Milf" to "69", "NTR" to "55",
            "Paizuri" to "64", "Petits seins" to "54", "Public Sex" to "67", "Rape" to "52",
            "Shota" to "72", "Tentacle" to "60", "Uncensored" to "56", "Vanilla" to "57", "Virgin" to "65",
        )
    }
}
