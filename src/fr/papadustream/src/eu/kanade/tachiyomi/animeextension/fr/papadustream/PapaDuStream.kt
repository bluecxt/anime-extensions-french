// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.papadustream

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.PAPADUSTREAM_LOG
import fr.bluecxt.core.Source
import fr.bluecxt.core.filters.FilterSpec
import fr.bluecxt.core.tmdb.utils.extractSeasonNumber
import fr.bluecxt.core.tvdb.TvdbMetadata
import fr.bluecxt.core.tvdb.fetchTvdbMetadata
import fr.bluecxt.core.utils.JsoupExtensions
import fr.bluecxt.core.utils.safeRelativePath
import keiyoushi.core.R
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class PapaPlayer(
    val id: String,
    val xfield: String,
    val type: String,
    val pageToken: String,
    val userHash: String,
    val serverName: String,
    val lang: String,
    val epUrl: String,
)

private const val YEAR_DIGIT_COUNT = 4
private const val WEBVIEW_TIMEOUT_SECONDS = 30L
private const val WEBVIEW_DESTROY_TIMEOUT_SECONDS = 2L

private val REGEX_GETXFIELD = Regex("""getxfield\s*\(\s*this\s*,\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]""")

class PapaDuStream :
    Source(),
    CommonPreferences,
    JsoupExtensions {

    override val name = "PapaDuStream"
    override val defaultBaseUrl = "https://papadustreami.autos"

    override val supportedServers = listOf(
        "Voe",
        "Filemoon",
        "Dood",
        "Okru",
        "Uqload",
        "Vidoza",
        "Streamtape",
        "Upstream",
        "StreamHide",
        "StreamVid",
        "StreamHub",
        "StreamDav",
        "Vidzy",
        "Sibnet",
        "Sendvid",
        "Vidmoly",
        "Minochinos",
        "Embed4me",
        "Abyss",
        "Streamix",
    )
    override val supportedVoices: Array<String> = arrayOf("VF", "VOSTFR")
    override val lang = "fr"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)
        .add("Referer", "$baseUrl/")

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${anime.cleanUrl}"

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$baseUrl/cat-series/page/$page/"
        Log.d(PAPADUSTREAM_LOG, "getPopularAnime page=$page -> $url")
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val document = response.asJsoup()
        return parseAnimeGrid(document)
    }

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        Log.d(PAPADUSTREAM_LOG, "getLatestUpdates page=$page -> $url")
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val document = response.asJsoup()
        return parseAnimeGrid(document)
    }

    // ============================== Search ===============================
    override val customFilters: List<FilterSpec> = listOf(
        FilterSpec.Select(
            name = "Catégorie",
            param = "category",
            options = arrayOf(
                "Tous" to "",
                "Animation" to "animation-s",
                "Action" to "action-s",
                "Aventure" to "aventure-s",
                "Biopic" to "biopic-s",
                "Comédie" to "comedie-s",
                "Drame" to "drame-s",
                "Documentaire" to "documentaire-s",
                "Epouvante-horreur" to "horreur-s",
                "Espionnage" to "espionnage-s",
                "Famille" to "famille-s",
                "Fantastique" to "fantastique-s",
                "Guerre" to "guerre-s",
                "Historique" to "historique-s",
                "Judiciaire" to "judiciaire-s",
                "Musical" to "musical-s",
                "Policier" to "policier-s",
                "Romance" to "romance-s",
                "Science-Fiction" to "science-fiction-s",
                "Thriller" to "thriller-s",
                "Western" to "western-s",
                "Séries VF" to "series-vf",
                "Séries VOSTFR" to "series-vostfr",
            ),
        ),
    )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val trimmed = query.trim()
        Log.d(PAPADUSTREAM_LOG, "getSearchAnime page=$page, query='$trimmed'")
        if (trimmed.isNotBlank()) {
            if (trimmed.startsWith("id:") || trimmed.startsWith("/cat-series/")) {
                return searchDirectUrl(trimmed)
            }
            return searchAjax(trimmed)
        }

        val category = filters.getParam("category")?.takeIf { it.isNotBlank() }
        val url = if (category != null) {
            "$baseUrl/cat-series/$category/page/$page/"
        } else {
            "$baseUrl/cat-series/page/$page/"
        }

        Log.d(PAPADUSTREAM_LOG, "Browse filtered url: $url")
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val document = response.asJsoup()
        return parseAnimeGrid(document)
    }

    private suspend fun searchDirectUrl(query: String): AnimesPage {
        val path = if (query.startsWith("id:")) query.removePrefix("id:").trim() else query
        val fullUrl = if (path.startsWith("http")) path else "$baseUrl$path"
        val response = client.newCall(GET(fullUrl, headers)).awaitSuccess()
        val document = response.asJsoup()
        val rawH1 = document.selectFirst("h1")?.text() ?: "Titre"
        val title = rawH1.removePrefixes("Série ", "Serie ").substringBeforeAny(" en streaming").trim()
        val thumbnail = document.selectFirst(".full_content-poster")?.extractAbsoluteImgUrl()
        val anime = SAnime.create().apply {
            this.title = title
            this.url = path.safeRelativePath(baseUrl) ?: path
            this.thumbnail_url = thumbnail
        }
        return AnimesPage(listOf(anime), false)
    }

    private suspend fun searchAjax(trimmed: String): AnimesPage {
        val homeDoc = client.newCall(GET(baseUrl, headers)).awaitSuccess().asJsoup()
        val userHash = homeDoc.select("script").joinToString("\n") { it.data() }.extractJsVar("dle_login_hash")

        val formBody = FormBody.Builder()
            .add("query", trimmed)
            .add("user_hash", userHash)
            .build()

        val searchHeaders = headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", "$baseUrl/")
            .build()

        val searchResponse = client.newCall(
            POST("$baseUrl/engine/ajax/controller.php?mod=search", searchHeaders, formBody),
        ).awaitSuccess()

        val searchDoc = searchResponse.asJsoup()
        val animes = searchDoc.select("a").mapNotNull { a ->
            val title = a.selectFirst(".searchheading")?.text()?.trim() ?: return@mapNotNull null
            val path = a.safeRelativePath() ?: a.attr("href").safeRelativePath(baseUrl) ?: return@mapNotNull null
            SAnime.create().apply {
                this.title = title
                this.url = path
            }
        }
        return AnimesPage(animes, false)
    }

    private fun parseAnimeGrid(document: Document): AnimesPage {
        val animes = document.select("div.short").mapNotNull { element ->
            val link = element.selectFirst("div.short_title a") ?: element.selectFirst("a.short_img") ?: return@mapNotNull null
            val path = link.safeRelativePath() ?: return@mapNotNull null
            val rawTitle = link.text().ifBlank { element.selectFirst("a.short_img")?.attr("title") ?: "" }.trim()
            val title = rawTitle.removePrefixes("regarder ")
            val thumbnail = element.selectFirst("a.short_img")?.extractAbsoluteImgUrl()

            SAnime.create().apply {
                this.title = title
                this.url = path
                this.thumbnail_url = thumbnail
            }
        }
        val hasNextPage = document.selectFirst(".pnext a") != null ||
            document.selectFirst(".navigation a:contains(Suivant)") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Anime Details ===============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = coroutineScope {
        val cleanUrl = anime.cleanUrl
        Log.d(PAPADUSTREAM_LOG, "getAnimeDetails: '${anime.title}', url='$cleanUrl'")
        val documentDeferred = async {
            client.newCall(GET("$baseUrl$cleanUrl", headers)).awaitSuccess().asJsoup()
        }
        val sNumFromPage = extractPapaSeasonNumber(cleanUrl, anime.title)
        val cleanTitle = cleanAnimeTitle(anime.title)
        val tvdbMetadataDeferred = async {
            fetchTvdbMetadata(cleanTitle, sNumFromPage)
        }

        val document = documentDeferred.await()
        val tvdbMetadata = tvdbMetadataDeferred.await()

        val rawTitle = document.selectFirst("h1")?.text() ?: anime.title
        val cleanedH1 = rawTitle.removePrefixes("Série ", "Serie ")
            .substringBeforeAny(" en streaming", " Complete")
            .trim()
        val sNumFromH1 = extractPapaSeasonNumber(cleanUrl, cleanedH1)
        val sNum = if (sNumFromPage > 1) sNumFromPage else sNumFromH1
        val baseFromPage = cleanAnimeTitle(cleanedH1)
        val title = if (cleanUrl.contains("-saison.html")) {
            if (sNum == 1) baseFromPage else "$baseFromPage $sNum"
        } else {
            baseFromPage.ifBlank { cleanedH1.ifBlank { anime.title } }
        }

        val (finalDesc, thumbnail) = parseDescriptionAndThumbnail(document, tvdbMetadata, anime.thumbnail_url)
        val genres = tvdbMetadata?.genre ?: document.select("ul#full_info li:contains(Genre) a")
            .asSequence()
            .map { it.text().trim() }
            .filterNot { it.contains("Séries", ignoreCase = true) }
            .joinToString(", ")

        anime.title = title
        anime.description = finalDesc
        anime.thumbnail_url = thumbnail
        anime.genre = genres
        anime.author = tvdbMetadata?.author ?: document.selectFirst("ul#full_info li:contains(Casting)")?.ownText()?.trim()
        anime.artist = tvdbMetadata?.artist
        anime.status = tvdbMetadata?.status?.takeIf { it != SAnime.UNKNOWN } ?: SAnime.UNKNOWN

        val seasonElements = document.select("a.th-hover")
        if (seasonElements.size > 1) {
            anime.coreSetFetchType(FetchType.Seasons)
            anime.coreSetSeasonNumber(HUB_SEASON_NUMBER)
        } else {
            anime.coreSetFetchType(FetchType.Episodes)
        }

        anime.initialized = true
        Log.d(PAPADUSTREAM_LOG, "[Anime Details] SAnime: '${anime.title}' (${anime.url}) -> thumbnail_url: '${anime.thumbnail_url}', description: '${anime.description}'")
        anime
    }

    private fun parseDescriptionAndThumbnail(document: Document, tvdbMetadata: TvdbMetadata?, fallbackThumbnail: String?): Pair<String, String?> {
        val desc = parseDescription(document, tvdbMetadata)
        val thumbnail = parseThumbnail(document, tvdbMetadata, fallbackThumbnail)
        return Pair(desc, thumbnail)
    }

    private fun parseDescription(document: Document, tvdbMetadata: TvdbMetadata?): String {
        val rawDesc = document.selectFirst(".full_content-desc")?.text()
            ?.substringAfter("Synopsis")
            ?.removePrefix(" de ")
            ?.substringAfter(":")
            ?.trim()
            ?: document.selectFirst(".full_content-desc")?.text()?.trim()
            ?: ""

        val year = document.selectFirst("ul#full_info li:contains(Année)")?.ownText()?.trim()
        val releaseDatePrefix = try {
            getString(R.string.metadata_release_date_prefix)
        } catch (_: Exception) {
            "Date de sortie : "
        }

        return buildString {
            val releaseYear = tvdbMetadata?.releaseDate ?: year
            if (!releaseYear.isNullOrBlank()) {
                append(releaseDatePrefix)
                append(releaseYear)
                append("\n\n")
            }
            val summary = tvdbMetadata?.summary?.takeIf { it.isNotBlank() } ?: rawDesc
            if (summary.isNotBlank()) {
                append(summary)
            }
        }
    }

    private fun parseThumbnail(document: Document, tvdbMetadata: TvdbMetadata?, fallbackThumbnail: String?): String? {
        val posterAbs = document.selectFirst(".fposter, .full_content-poster, .poster, .img-box-full")?.extractAbsoluteImgUrl()
        return tvdbMetadata?.seasonPosterUrl ?: tvdbMetadata?.mainPosterUrl ?: posterAbs ?: fallbackThumbnail
    }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = emptyList()

    // ============================== Season ===============================
    private data class PapaSeason(val title: String, val url: String, val num: Int, val poster: String?)

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val cleanUrl = anime.cleanUrl
        Log.d(PAPADUSTREAM_LOG, "getSeasonList for '${anime.title}', url='$cleanUrl'")
        val document = client.newCall(GET("$baseUrl$cleanUrl", headers)).awaitSuccess().asJsoup()

        val baseTitle = cleanAnimeTitle(anime.title)
        val siteSeasons = parseSeasonElements(document, baseTitle)
        if (siteSeasons.isEmpty()) return emptyList()

        Log.d(PAPADUSTREAM_LOG, "Found ${siteSeasons.size} seasons for '$baseTitle'")
        return siteSeasons.mapIndexed { index, season ->
            createSeasonAnime(season, index, siteSeasons.size, baseTitle, anime.status)
        }
    }

    private fun parseSeasonElements(document: Document, baseTitle: String): List<PapaSeason> {
        val seasonElements = document.select("a.th-hover")
        if (seasonElements.isEmpty()) return emptyList()

        return seasonElements.mapNotNull { el ->
            val sHref = el.safeRelativePath() ?: return@mapNotNull null
            val rawSTitle = el.selectFirst(".th-title1")?.text()?.trim() ?: el.attr("title")
            val sNum = extractSeasonNumber(rawSTitle)?.takeIf { it > 0 } ?: extractSeasonNumber(sHref)?.takeIf { it > 0 } ?: 1
            val fullTitle = if (sNum == 1) baseTitle else "$baseTitle $sNum"
            val seasonPosterAbs = el.extractAbsoluteImgUrl()
            PapaSeason(fullTitle, sHref, sNum, seasonPosterAbs)
        }.reversed()
    }

    private suspend fun createSeasonAnime(
        season: PapaSeason,
        index: Int,
        totalSeasons: Int,
        baseTitle: String,
        fallbackStatus: Int,
    ): SAnime {
        val tvdbMeta = fetchTvdbMetadata(baseTitle, season.num)

        return SAnime.create().apply {
            this.title = season.title
            this.url = season.url
            thumbnail_url = tvdbMeta?.seasonPosterUrl ?: tvdbMeta?.mainPosterUrl ?: season.poster
            description = tvdbMeta?.summary
            genre = tvdbMeta?.genre
            author = tvdbMeta?.author
            artist = tvdbMeta?.artist
            status = if (index < totalSeasons - 1) SAnime.COMPLETED else (tvdbMeta?.status ?: fallbackStatus)

            coreSetFetchType(FetchType.Episodes)
            coreSetSeasonNumber(season.num.toDouble())
            initialized = true
            Log.d(PAPADUSTREAM_LOG, "[Season SAnime] SAnime: '${season.title}' (${season.url}) -> thumbnail_url: '$thumbnail_url', description: '$description'")
        }
    }

    // ============================== Episodes ===============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = coroutineScope {
        val cleanUrl = anime.cleanUrl
        Log.d(PAPADUSTREAM_LOG, "getEpisodeList for '${anime.title}', url='$cleanUrl'")
        var document = client.newCall(GET("$baseUrl$cleanUrl", headers)).awaitSuccess().asJsoup()

        val seasonElements = document.select("a.th-hover")
        val hasDirectEpisodes = document.selectFirst("a:has(div.fsa-ep)") != null ||
            document.selectFirst("div.floats a[href*=-episode.html]") != null ||
            document.selectFirst("select.nav-episode-select.open") != null ||
            document.selectFirst("select[name=episode]") != null

        if (!hasDirectEpisodes && seasonElements.isNotEmpty()) {
            val targetSeasonUrl = seasonElements.lastOrNull()?.safeRelativePath() ?: seasonElements.firstNotNullOfOrNull { it.safeRelativePath() }
            if (targetSeasonUrl != null) {
                document = client.newCall(GET("$baseUrl$targetSeasonUrl", headers)).awaitSuccess().asJsoup()
            }
        }

        val sNum = extractPapaSeasonNumber(anime.url, anime.title)
        val baseTitle = cleanAnimeTitle(anime.title)
        val tvdbMetadataDeferred = async { fetchTvdbMetadata(baseTitle, sNum) }

        val episodes = parseEpisodes(document)
        val tvdbMetadata = tvdbMetadataDeferred.await()
        val sPrefix = if (sNum > 1) "[S$sNum] " else ""

        Log.d(PAPADUSTREAM_LOG, "Parsed ${episodes.size} episodes for '$baseTitle' season $sNum")
        episodes.map { ep ->
            val epNumInt = ep.episode_number.toInt()
            val epMeta = tvdbMetadata?.episodeSummaries?.get(epNumInt)
            val tvdbName = epMeta?.first
            val formattedName = if (tvdbName != null) "Épisode $epNumInt - $tvdbName" else "Épisode $epNumInt"

            ep.apply {
                this.name = "$sPrefix$formattedName"
                this.preview_url = epMeta?.second
                this.summary = epMeta?.third
            }
            Log.d(PAPADUSTREAM_LOG, "[Episode Preview] SAnime: '${anime.title}' (${anime.url}) | Episode #${ep.episode_number.toInt()} ('${ep.name}') -> preview_url: '${ep.preview_url}', summary: '${ep.summary}'")
            ep
        }.sortedByDescending { it.episode_number }
    }

    private fun parseEpisodes(document: Document): List<SEpisode> {
        val episodeElements = document.select("a:has(div.fsa-ep)").ifEmpty {
            document.select("div.floats a[href*=-episode.html]")
        }

        if (episodeElements.isNotEmpty()) {
            return episodeElements.mapNotNull { el ->
                val href = el.safeRelativePath() ?: return@mapNotNull null
                val epName = el.selectFirst("div.fsa-ep .name")?.text()?.trim() ?: ""
                createEpisode(href, epName)
            }
        }

        val sel = document.selectFirst("select.nav-episode-select.open")
            ?: document.selectFirst("select[name=episode]")
        return sel?.select("option")?.mapNotNull { opt ->
            val href = opt.attr("value").takeIf { it.isNotBlank() }?.safeRelativePath(baseUrl) ?: return@mapNotNull null
            createEpisode(href, opt.text().trim())
        } ?: emptyList()
    }

    // ============================== Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val cleanUrl = episode.cleanUrl
        Log.d(PAPADUSTREAM_LOG, "getHosterList for '${episode.name}', url='$cleanUrl'")
        val response = client.newCall(GET("$baseUrl$cleanUrl", headers)).awaitSuccess()
        val document = response.asJsoup()

        val scriptData = document.select("script").joinToString("\n") { it.data() }
        val pageToken = scriptData.extractJsVar("xfPageToken")
        val userHash = scriptData.extractJsVar("dle_login_hash")

        val playerDivs = document.select("ul.player-list li div.lien")
        val players = playerDivs.mapNotNull { div ->
            val onclick = div.attr("onclick")
            val match = REGEX_GETXFIELD.find(onclick) ?: return@mapNotNull null
            val (id, xfield, type) = match.destructured

            val server = div.selectFirst("span.serv")?.text()?.trim() ?: xfield.substringBefore("_")
            val isVf = div.selectFirst("img[src*='VF']") != null || xfield.endsWith("_vf", ignoreCase = true)
            val lang = if (isVf) "VF" else "VOSTFR"

            PapaPlayer(
                id = id,
                xfield = xfield,
                type = type,
                pageToken = pageToken,
                userHash = userHash,
                serverName = server,
                lang = lang,
                epUrl = cleanUrl,
            )
        }

        if (players.isEmpty()) return emptyList()

        val hosters = players.groupBy { it.lang }.map { (lang, langPlayers) ->
            Hoster(
                hosterName = lang.uppercase(),
                hosterUrl = langPlayers.toJsonString(),
            )
        }.sortHosters()

        Log.d(PAPADUSTREAM_LOG, "Found ${players.size} players grouped into ${hosters.size} language hosters")
        return hosters
    }

    // ============================== Videos ===============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val players = try {
            hoster.hosterUrl.parseAs<List<PapaPlayer>>()
        } catch (_: Exception) {
            try {
                listOf(hoster.hosterUrl.parseAs<PapaPlayer>())
            } catch (_: Exception) {
                emptyList()
            }
        }

        if (players.isEmpty()) return emptyList()

        Log.d(PAPADUSTREAM_LOG, "getVideoList resolving ${players.size} players for language '${hoster.hosterName}'")

        val videos = players.parallelCatchingFlatMap { player ->
            val embedUrl = resolveEmbedUrlWithWebView(player) ?: return@parallelCatchingFlatMap emptyList()
            Log.d(PAPADUSTREAM_LOG, "Extracted embedUrl for ${player.serverName} (${player.lang}): $embedUrl")
            extractVideos(embedUrl, player.lang, supportedServers)
        }.sortVideos()

        return videos
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveEmbedUrlWithWebView(player: PapaPlayer): String? = withContext(Dispatchers.IO) {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<String?>(null)
        val webViewRef = AtomicReference<WebView?>(null)
        val destroyLatch = CountDownLatch(1)

        val playerJson = player.toJsonString()
        val targetUrl = "$baseUrl${player.epUrl}"

        class PapaJSInterface {
            @JavascriptInterface
            fun onSuccess(data: String) {
                Log.d(PAPADUSTREAM_LOG, "WebView Turnstile success for ${player.serverName}: $data")
                val embedUrl = extractEmbedUrl(data)
                resultRef.set(embedUrl)
                latch.countDown()
            }

            @JavascriptInterface
            fun onError(error: String) {
                Log.w(PAPADUSTREAM_LOG, "WebView Turnstile error for ${player.serverName}: $error")
                latch.countDown()
            }
        }

        handler.post {
            try {
                val webView = WebView(context)
                webViewRef.set(webView)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = false
                    userAgentString = DEFAULT_USER_AGENT
                }

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                val jsInterface = PapaJSInterface()
                webView.addJavascriptInterface(jsInterface, "PapaJSI")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val script = """
                            (function() {
                                var player = $playerJson;
                                var attempts = 0;
                                var maxAttempts = 60;

                                function pollTurnstile() {
                                    attempts++;
                                    if (typeof turnstile === 'undefined' || typeof xfPageToken === 'undefined' || typeof $ === 'undefined') {
                                        if (attempts < maxAttempts) {
                                            setTimeout(pollTurnstile, 500);
                                        } else {
                                            PapaJSI.onError("Timeout waiting for Turnstile/xfPageToken");
                                        }
                                        return;
                                    }

                                    try {
                                        var container = document.getElementById('xf_lock') || document.body;
                                        turnstile.render(container, {
                                            sitekey: '0x4AAAAAACiFcvM1aDcvN7uK',
                                            action: 'getxfield',
                                            cData: typeof xfPageCData !== 'undefined' ? xfPageCData : '',
                                            callback: function(token) {
                                                $.ajax({
                                                    type: 'POST',
                                                    url: '/engine/ajax/controller.php?mod=getxfield',
                                                    headers: {'X-Requested-With': 'XMLHttpRequest'},
                                                    data: {
                                                        id: player.id,
                                                        xfield: player.xfield,
                                                        type: player.type,
                                                        page_token: xfPageToken,
                                                        g_recaptcha_response: token,
                                                        user_hash: typeof dle_login_hash !== 'undefined' ? dle_login_hash : ''
                                                    },
                                                    success: function(data) {
                                                        PapaJSI.onSuccess(data);
                                                    },
                                                    error: function(xhr, status, error) {
                                                        PapaJSI.onError("AJAX error: " + status + " " + error);
                                                    }
                                                });
                                            },
                                            'error-callback': function() {
                                                PapaJSI.onError("Turnstile error callback");
                                            }
                                        });
                                    } catch(e) {
                                        PapaJSI.onError("Exception during turnstile.render: " + e.message);
                                    }
                                }

                                pollTurnstile();
                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(script) {}
                    }
                }

                webView.loadUrl(targetUrl, mapOf("User-Agent" to DEFAULT_USER_AGENT, "Referer" to baseUrl))
            } catch (e: Exception) {
                Log.e(PAPADUSTREAM_LOG, "Error creating WebView", e)
                latch.countDown()
            }
        }

        try {
            latch.await(WEBVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        handler.post {
            try {
                webViewRef.get()?.stopLoading()
                webViewRef.get()?.destroy()
            } catch (e: Exception) {
                Log.w(PAPADUSTREAM_LOG, "Error destroying WebView", e)
            } finally {
                webViewRef.set(null)
                destroyLatch.countDown()
            }
        }

        try {
            destroyLatch.await(WEBVIEW_DESTROY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        resultRef.get()
    }

    // ============================== Utils ===============================
    private fun extractEmbedUrl(data: String): String? {
        val trimmed = data.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains("src=\"") -> trimmed.substringAfter("src=\"").substringBefore("\"")
            trimmed.contains("src='") -> trimmed.substringAfter("src='").substringBefore("'")
            trimmed.contains("href=\"") -> trimmed.substringAfter("href=\"").substringBefore("\"")
            trimmed.contains("href='") -> trimmed.substringAfter("href='").substringBefore("'")
            else -> null
        }?.takeIf { it.startsWith("http") }
    }

    private fun extractPapaSeasonNumber(url: String, title: String): Int {
        val sFromUrl = when {
            url.contains("-saison", ignoreCase = true) ->
                url.substringBefore("-saison", "").substringAfterLast("/").substringAfterLast("_").toIntOrNull()

            url.contains("saison", ignoreCase = true) ->
                url.substringAfter("saison", "").takeWhile { it.isDigit() || it == '-' || it == '/' }.filter { it.isDigit() }.toIntOrNull()

            else -> null
        }
        if (sFromUrl != null && sFromUrl > 0) return sFromUrl

        val sFromTitle = when {
            title.contains("saison", ignoreCase = true) ->
                title.substringAfterLast("saison", "").trim().takeWhile { it.isDigit() }.toIntOrNull()

            else ->
                title.trim().takeLastWhile { it.isDigit() }.toIntOrNull()
        }
        if (sFromTitle != null && sFromTitle > 0) return sFromTitle

        return 1
    }

    private fun cleanAnimeTitle(title: String): String {
        var cleaned = title.removePrefixes("Série ", "Serie ")
            .substringBeforeAny(" en streaming", " Complete", " - Saison", " Saison")
            .replace("(VF)", "", ignoreCase = true)
            .replace("(VOSTFR)", "", ignoreCase = true)
            .replace("(VA)", "", ignoreCase = true)

        val yearIndex = cleaned.lastIndexOf('(')
        if (yearIndex != -1 && cleaned.endsWith(')')) {
            val inside = cleaned.substring(yearIndex + 1, cleaned.length - 1)
            if (inside.length == YEAR_DIGIT_COUNT && inside.all { it.isDigit() }) {
                cleaned = cleaned.substring(0, yearIndex).trim()
            }
        }

        while (cleaned.isNotEmpty() && cleaned.last().isDigit()) {
            cleaned = cleaned.dropLast(1)
        }

        return cleaned.trim()
    }

    // ============================== Extensions ===============================
    private val SAnime.cleanUrl get() = url.substringBefore("#")
    private val SEpisode.cleanUrl get() = url.substringBefore("#")

    private fun Element?.extractAbsoluteImgUrl(): String? {
        val el = this?.selectFirst("img") ?: this ?: return null
        val src = el.attr("data-src").takeIf { it.isNotBlank() }
            ?: el.attr("src").takeIf { it.isNotBlank() && !it.contains("loading.gif") }
            ?: el.attr("abs:src").takeIf { it.isNotBlank() }
            ?: return null
        return if (src.startsWith("http")) src else "$baseUrl$src"
    }

    private fun createEpisode(href: String, rawName: String): SEpisode {
        val epNum = href.substringAfterLast("/").substringBefore("-episode").toIntOrNull() ?: 1
        return SEpisode.create().apply {
            this.url = href
            this.name = rawName.ifBlank { "Épisode $epNum" }
            this.episode_number = epNum.toFloat()
        }
    }

    private fun String.extractJsVar(varName: String): String = substringAfter("$varName = '", "").substringBefore("'", "").ifBlank {
        substringAfter("$varName = \"", "").substringBefore("\"", "")
    }

    private fun String.removePrefixes(vararg prefixes: String): String {
        for (prefix in prefixes) {
            if (startsWith(prefix, ignoreCase = true)) return substring(prefix.length).trim()
        }
        return this
    }

    private fun String.substringBeforeAny(vararg delimiters: String): String {
        var result = this
        for (delim in delimiters) {
            val idx = result.indexOf(delim, ignoreCase = true)
            if (idx != -1) result = result.substring(0, idx)
        }
        return result
    }
}
