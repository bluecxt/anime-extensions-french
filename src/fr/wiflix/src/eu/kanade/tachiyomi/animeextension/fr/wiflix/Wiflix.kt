// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.wiflix

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.Source
import fr.bluecxt.core.WIFLIX_LOG
import fr.bluecxt.core.utils.safeRelativePath
import keiyoushi.utils.get
import keiyoushi.utils.post
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class Wiflix :
    Source(),
    CommonPreferences {

    override val name = "Wiflix"

    override val defaultBaseUrl = "https://flemmix.studio"

    override val lang = "fr"

    override val supportsLatest = true

    override val baseUrlSummary = "https://ww1.wiflix-adresses.fun | https://wiflix-news.site"

    override val json: Json by injectLazy()

    override val client: okhttp3.OkHttpClient by lazy {
        super.client.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES) // todo c'est que pour la recherche pour le reste il faudrait une solution
            .build()
    }

    override val currentBaseUrl: String
        get() {
            val prefUrl = preferences.getString(CommonPreferences.PREF_URL_KEY, defaultBaseUrl) ?: defaultBaseUrl
            if (prefUrl.contains("flemmix.city") || prefUrl.contains("flemmix.cloud") || prefUrl.contains("flemmix.eu")) {
                preferences.edit().remove(CommonPreferences.PREF_URL_KEY).apply()
                return defaultBaseUrl
            }
            return prefUrl.removeSuffix("/")
        }

    // ============================== Directory Parsing ==============================

    private fun parseDirectory(response: Response): AnimesPage {
        val document = response.useAsJsoup()

        // Restrict search to the main content container to avoid picking up sidebar items
        val contentWrap = document.selectFirst("div.content div.full-wrap, div.cols div.content, div.full-wrap")
        val container = contentWrap ?: document

        val animes = container.select("div.mov").mapNotNull { element ->
            val titleElement = element.selectFirst("a.mov-t") ?: return@mapNotNull null
            val titleText = titleElement.text().trim()
            val linkUrl = titleElement.safeRelativePath() ?: return@mapNotNull null
            val thumb = element.selectFirst("img")?.attr("abs:src") ?: "https://http.cat/404.jpg"
            if (titleText.isBlank() || linkUrl.isBlank()) {
                null
            } else {
                SAnime.create().apply {
                    title = titleText
                    url = linkUrl
                    thumbnail_url = thumb
                }
            }
        }
        val hasNextPage = document.selectFirst("span.pnext > a") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/serie-en-streaming/" else "$baseUrl/serie-en-streaming/page/$page/"
        val response = client.get(url, headers)
        return parseDirectory(response)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/film-en-streaming/" else "$baseUrl/film-en-streaming/page/$page/"
        val response = client.get(url, headers)
        return parseDirectory(response)
    }

    // ============================== Search ================================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page == 1) "$baseUrl/xfsearch/$encodedQuery/" else "$baseUrl/xfsearch/$encodedQuery/page/$page/"
        val response = client.get(url, headers)
        return parseDirectory(response)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = "$baseUrl${anime.url}"
        Log.d(WIFLIX_LOG, url)
        val document = client.get(url).useAsJsoup()

        val description = document.selectFirst("[itemprop=description]")?.text()
            ?.substringAfter("Synopsis:")?.trim()

        val year = document.select("li").find { it.selectFirst(".mov-label")?.text()?.contains("Date de sortie", true) == true }?.selectFirst(".mov-desc")?.text()?.trim()

        anime.description = if (year != null) "Date de sortie : $year\n\n$description" else description
        anime.genre = document.select("span[itemprop=genre]").joinToString { it.text() }
        document.selectFirst("img#posterimg, div.mov-img img")?.attr("abs:src")?.let {
            if (it.isNotBlank()) anime.thumbnail_url = it
        }

        if (anime.url.contains("/film-en-streaming/")) {
            anime.status = SAnime.COMPLETED
        }

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val anime = SAnime.create()
        val animeUrl = response.request.url.encodedPath

        val serieEpisodes = document.select("ul.eplist li.clicbtn")
        if (serieEpisodes.isNotEmpty()) {
            return serieEpisodes.groupBy { it.text().filter { c -> c.isDigit() }.ifBlank { "1" } }
                .map { (epNum, _) ->
                    SEpisode.create().apply {
                        this.episode_number = epNum.toFloat()
                        this.name = "Épisode $epNum"
                        this.url = "$animeUrl#group-$epNum"
                        prepareNewEpisode(this, anime)
                    }
                }.asReversed()
        }

        // Movie case (Rule 1: [Movie] prefix)
        val episodes = mutableListOf<SEpisode>()
        if (document.selectFirst("div.tabs-sel.linkstab a[data-v], div.tabs-sel a, div.vostfr-links a") != null) {
            val movieTitle = document.selectFirst("h1[itemprop=name]")?.text() ?: "Film"
            episodes.add(
                SEpisode.create().apply {
                    name = "[Movie] $movieTitle"
                    episode_number = 1f
                    this.url = "$animeUrl#movie"
                    prepareNewEpisode(this, anime)
                },
            )
        }
        return episodes
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val animeUrl = episode.url.substringBefore("#")
        val fragment = episode.url.substringAfter("#")

        val response = client.get(baseUrl.removeSuffix("/") + animeUrl)
        val document = response.useAsJsoup()

        val hosters = mutableListOf<Hoster>()

        if (fragment.startsWith("group-")) {
            val epNum = fragment.substringAfter("group-")
            val elements = document.select("ul.eplist li.clicbtn").filter { it.text().filter { c -> c.isDigit() }.ifBlank { "1" } == epNum }

            if (elements.any { !it.parents().hasClass("blocvostfr") }) {
                hosters.add(Hoster(hosterName = "VF", hosterUrl = "$baseUrl$animeUrl#$fragment-vf", internalData = "VF", lazy = true))
            }
            if (elements.any { it.parents().hasClass("blocvostfr") }) {
                hosters.add(Hoster(hosterName = "VOSTFR", hosterUrl = "$baseUrl$animeUrl#$fragment-vostfr", internalData = "VOSTFR", lazy = true))
            }
        } else if (fragment == "movie") {
            val vfLinks = document.select("div.tabs-sel.linkstab > a[data-v]")
            val vostfrLinks = document.select("div.vostfr-links a[data-v]")

            if (vfLinks.isNotEmpty() || document.selectFirst("div.tabs-sel > a") != null) {
                hosters.add(Hoster(hosterName = "VF", hosterUrl = "$baseUrl$animeUrl#movie", internalData = "VF", lazy = true))
            }
            if (vostfrLinks.isNotEmpty() || document.selectFirst("div.vostfr-links a") != null) {
                hosters.add(Hoster(hosterName = "VOSTFR", hosterUrl = "$baseUrl$animeUrl#movie", internalData = "VOSTFR", lazy = true))
            }
        }
        Log.d(WIFLIX_LOG, "hosterlist = $hosters")

        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = coroutineScope {
        val url = hoster.hosterUrl
        val lang = hoster.internalData // "VF" or "VOSTFR"
        val animeUrl = url.substringBefore("#")
        val fragment = url.substringAfter("#")

        val response = client.get(animeUrl)
        val html = response.body.string()
        val document = org.jsoup.Jsoup.parse(html, animeUrl)

        val params = extractPlayerParams(html)

        val serverElements = if (fragment.startsWith("group-")) {
            val epNum = fragment.substringAfter("group-").substringBefore("-")
            val isVostfr = fragment.endsWith("-vostfr")
            val elements = document.select("ul.eplist li.clicbtn").filter {
                it.text().filter { c -> c.isDigit() }.ifBlank { "1" } == epNum &&
                    (if (isVostfr) it.parents().hasClass("blocvostfr") else !it.parents().hasClass("blocvostfr"))
            }
            elements.flatMap { el ->
                val rel = el.attr("rel")
                document.select("div.$rel a")
            }
        } else {
            val selector = if (lang == "VOSTFR") "div.vostfr-links a" else "div.tabs-sel.linkstab > a"
            document.select(selector)
        }

        serverElements.map { link ->
            async {
                val dataV = link.attr("data-v").trim().ifEmpty {
                    val onclick = link.attr("onclick")
                    onclick.substringAfter("'").substringBefore("'").trim()
                }
                if (dataV.isBlank() || dataV.startsWith("playSafe")) return@async emptyList<Video>()

                val realUrl = resolveVideoUrl(dataV, animeUrl, params)
                if (realUrl.isNullOrBlank()) return@async emptyList<Video>()
                Log.d(WIFLIX_LOG, "resolved videoUrl = $realUrl")
                extractVideos(realUrl, lang, supportedServers)
            }
        }.awaitAll().flatten()
    }

    // ============================ Player Decryption =============================

    private data class PlayerParams(
        val key: String,
        val salt: String,
        val initialHash: Int,
        val multiplier: Int,
    )

    @Suppress("TooGenericExceptionCaught", "MagicNumber")
    private fun extractPlayerParams(html: String): PlayerParams? {
        val match = SCRIPT_PARAMS_REGEX.find(html) ?: return null
        val values = match.groupValues
        if (values.size < 5) return null
        val numsStr = values[1]
        val mStr = values[2]
        val inStr = values[3]
        val muStr = values[4]
        return try {
            val m = mStr.toInt()
            val decoded = numsStr.split(",").map { (it.trim().toInt() xor m).toChar() }.joinToString("")
            val parts = decoded.split("|")
            val k = parts[0]
            val sl = parts.getOrElse(1) { "" }
            PlayerParams(
                key = k,
                salt = sl,
                initialHash = inStr.toInt(),
                multiplier = muStr.toInt(),
            )
        } catch (e: Exception) {
            Log.e(WIFLIX_LOG, "Failed to parse player params", e)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveVideoUrl(raw: String, referer: String, params: PlayerParams?): String? {
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("//")) {
            return raw
        }

        if (raw.startsWith("fx1_")) {
            if (params == null) return null
            val signature = computeSignature(raw, params)
            val formBody = FormBody.Builder()
                .add("v", raw)
                .add("k", params.key)
                .add("b", "1")
                .add("s", signature)
                .add("t", "1")
                .build()

            val playerHeaders = headers.newBuilder()
                .set("Referer", referer)
                .set("Origin", baseUrl)
                .set("Content-Type", "application/x-www-form-urlencoded")
                .build()

            return try {
                val res = client.post("$baseUrl/ajax_player.php", playerHeaders, formBody)
                val jsonStr = res.body.string()
                val jsonObject = json.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonObject
                jsonObject?.get("u")?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                }
            } catch (e: Exception) {
                Log.e(WIFLIX_LOG, "ajax_player resolution failed for $raw", e)
                null
            }
        }

        // ROT13 / Base64 fallback (decodeStream legacy)
        return try {
            val b64 = String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.ISO_8859_1)
            b64.map { c ->
                when (c) {
                    in 'a'..'z' -> (((c - 'a' + ROT13_SHIFT) % ALPHABET_SIZE) + 'a'.code).toChar()
                    in 'A'..'Z' -> (((c - 'A' + ROT13_SHIFT) % ALPHABET_SIZE) + 'A'.code).toChar()
                    else -> c
                }
            }.joinToString("")
        } catch (_: Exception) {
            null
        }
    }

    private fun computeSignature(v: String, params: PlayerParams): String {
        val s = "$v|${params.key}|${params.salt}"
        var h = params.initialHash
        for (c in s) {
            h = ((h * params.multiplier) + c.code) and SIGNATURE_MASK
        }
        return "%08x".format(h).takeLast(SIGNATURE_LENGTH)
    }

    companion object {
        private const val ROT13_SHIFT = 13
        private const val ALPHABET_SIZE = 26
        private const val SIGNATURE_MASK = 0x7fffffff
        private const val SIGNATURE_LENGTH = 8
        private val SCRIPT_PARAMS_REGEX = Regex("""var\s+_a=\[([0-9,\s]+)\],_m=(\d+),_in=(\d+),_mu=(\d+)""")
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
}
