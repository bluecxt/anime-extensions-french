// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.multisrc.madara

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.Source
import fr.bluecxt.core.tvdb.fetchTvdbMetadata
import fr.bluecxt.core.utils.JsoupExtensions
import fr.bluecxt.core.utils.parseStatus
import fr.bluecxt.core.utils.safeRelativePath
import keiyoushi.core.R
import keiyoushi.utils.get
import keiyoushi.utils.head
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

abstract class Madara(
    override val name: String,
    override val defaultBaseUrl: String,
    override val lang: String,
) : Source(),
    CommonPreferences,
    JsoupExtensions {

    override val supportsLatest = true

    open val animeSubString: String = "drama"
    open val animeSlugRegex: Regex by lazy { Regex("""(/$animeSubString/[^/]+)""") }
    open val enableTvdb: Boolean = true

    open fun cleanTitle(rawTitle: String): Pair<String, Int> {
        val withoutLang = rawTitle.replace(TITLE_CLEAN_REGEX, "").trim()
        val clean = withoutLang.dropLastWhile { it.isDigit() }.trim()
        val season = withoutLang.takeLastWhile { it.isDigit() }.toIntOrNull() ?: 1
        return Pair(clean.ifBlank { withoutLang }, season)
    }

    open fun resolveAnimeUrl(rawUrl: String): String = rawUrl

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage = parseAnime(
        client.get(if (page > 1) "$baseUrl/page/$page" else baseUrl, headers).useAsJsoup(),
        popularAnimeSelector(),
        popularAnimeNameSelector(),
    )

    open fun popularAnimeSelector(): String = ".video"
    open fun popularAnimeNameSelector(): String = "h3.h5"

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage = parseAnime(
        client.get(if (page > 1) "$baseUrl/nouveaux-ajouts/page/$page/" else "$baseUrl/nouveaux-ajouts", headers).useAsJsoup(),
        latestAnimeSelector(),
        latestAnimeNameSelector(),
    )

    open fun latestAnimeSelector(): String = ".video"
    open fun latestAnimeNameSelector(): String = "h3.h5"

    // ============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = MadaraFilters.buildFilterList(customFilters)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val trimmedQuery = query.trim()

        if (trimmedQuery.startsWith(PREFIX_SEARCH)) {
            val slug = trimmedQuery.removePrefix(PREFIX_SEARCH).trim().removePrefix("/").removeSuffix("/")
            val directAnime = SAnime.create().apply {
                url = if (slug.startsWith("$animeSubString/")) "/$slug/" else "/$animeSubString/$slug/"
            }
            return try {
                val details = getAnimeDetails(directAnime)
                AnimesPage(listOf(details), false)
            } catch (_: Exception) {
                AnimesPage(emptyList(), false)
            }
        }

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page")

            addQueryParameter("s", trimmedQuery)
            addQueryParameter("post_type", "wp-manga")
            applyFilters(filters)
        }.build()

        val document = client.get(url, headers).useAsJsoup()

        return parseAnime(document, searchAnimeSelector(), searchAnimeNameSelector())
    }

    open fun searchAnimeSelector(): String = ".c-tabs-item > div"
    open fun searchAnimeNameSelector(): String = "h3.h4"

    // ============================== Parsing ===============================
    open fun parseAnime(
        document: Document,
        animesSelector: String,
        nameSelector: String,
    ): AnimesPage = with(document) {
        val animes = select(animesSelector).mapNotNull { element ->
            val name = element.selectFirst(nameSelector)?.text() ?: ""
            val link = element.selectFirst("$nameSelector a")
                ?.safeRelativePath()
                ?: return@mapNotNull null
            val genres = element.select(".mg_genres a").joinToString { it.text() }

            SAnime.create().apply {
                url = link
                thumbnail_url = element.selectFirst("img")?.attr("src")?.replace("110x150", "193x278") ?: ""
                title = name
                genre = genres
                status = element.selectFirst(".mg_status .summary-content")?.text()?.parseStatus() ?: SAnime.UNKNOWN
            }
        }.map { it.removeFrench() }.distinctBy { it.url }

        val hasNextPage = selectFirst(".nextpostslink") != null

        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Anime Details ===============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime.apply {
        val originalUrl = url
        val resolvedUrl = resolveAnimeUrl(url)
        val document = client.get("$baseUrl$resolvedUrl", headers).useAsJsoup()

        if (description.isNullOrBlank()) {
            val date = document.selectFirst("div:contains(Start date) > div.summary-content")?.text()
            val synopsis = document.selectFirst(".summary__content p")?.text()

            if (!date.isNullOrBlank() || !synopsis.isNullOrBlank()) {
                description = buildString {
                    if (!date.isNullOrBlank()) appendLine("${getString(R.string.metadata_release_date_prefix)}$date")
                    if (!synopsis.isNullOrBlank()) append(synopsis)
                }
            }
        }
        if (genre.isNullOrBlank()) genre = document.select(".genres-content > a").joinToString { it.text() }
        if (status == SAnime.UNKNOWN) status = document.selectFirst("div:contains(Status) > div.summary-content")?.text()?.parseStatus() ?: SAnime.UNKNOWN
        if (author.isNullOrBlank()) author = document.selectFirst("div:contains(Studios) > div.summary-content")?.text()

        // TVDB Enrichment
        if (enableTvdb) {
            val (cleanTitle, seasonNumber) = cleanTitle(title)
            val metadata = fetchTvdbMetadata(cleanTitle, seasonNumber)
            if (metadata != null) {
                if (!metadata.summary.isNullOrBlank()) {
                    description = buildString {
                        metadata.releaseDate?.takeIf { it.isNotBlank() }?.let { date ->
                            append(getString(R.string.metadata_release_date_prefix))
                            appendLine(date)
                            appendLine()
                        }
                        append(metadata.summary)
                    }
                }
                if (thumbnail_url.isNullOrBlank()) {
                    thumbnail_url = metadata.seasonPosterUrl ?: metadata.mainPosterUrl
                }
                if (genre.isNullOrBlank()) genre = metadata.genre
                if (author.isNullOrBlank()) author = metadata.author
                if (artist.isNullOrBlank()) artist = metadata.artist
                if (status == SAnime.UNKNOWN && metadata.status != 0) status = metadata.status
            }
        }

        url = originalUrl
    }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Episodes ===============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = with(anime) {
        val resolvedUrl = resolveAnimeUrl(url)
        val document = client.get("$baseUrl$resolvedUrl", headers).useAsJsoup()
        val hasVf = itHasVf(resolvedUrl)

        val episodes = parseEpisodes(document, hasVf)

        if (!enableTvdb) return@with episodes

        val (cleanTitle, seasonNumber) = cleanTitle(title)
        val metadata = fetchTvdbMetadata(cleanTitle, seasonNumber) ?: return@with episodes

        episodes.map { episode ->
            val num = episode.episode_number.toInt()
            val epMeta = metadata.episodeSummaries[num]
            if (epMeta != null) {
                val (tvdbName, previewUrl, summary) = epMeta
                if (!tvdbName.isNullOrBlank()) {
                    episode.name = "${episode.name} - $tvdbName"
                }
                episode.preview_url = previewUrl
                episode.summary = summary
            }
            episode
        }
    }

    open fun episodeSelector(): String = "li.wp-manga-chapter"

    open fun parseEpisodes(document: Document, hasVf: Boolean): List<SEpisode> = document.select(episodeSelector()).mapNotNull { element ->
        val link = element.selectFirst("> a")?.safeRelativePath() ?: return@mapNotNull null
        val episodeNumber = element.selectFirst("> a")?.text()?.substringAfterLast("-")?.trim() ?: ""
        val lang = if (hasVf) "VOSTFR, VF" else "VOSTFR"
        SEpisode.create().apply {
            url = link
            name = if (episodeNumber.startsWith(episodePrefix, ignoreCase = true)) {
                episodeNumber
            } else {
                "$episodePrefix$episodeNumber"
            }
            episode_number = episodeNumber.toFloatOrNull() ?: 0f
            scanlator = lang
            date_upload = element.selectFirst(".chapter-release-date i")?.text()
                ?.let { dateFormat.tryParse(it) } ?: 0L
        }
    }

    open val episodePrefix: String = "Épisode "
    open val dateFormat: SimpleDateFormat by lazy { SimpleDateFormat("MMMM dd, yyyy", Locale.US) }

    // ============================== Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = with(episode) {
        return scanlator?.split(",")?.mapNotNull { lang ->
            Hoster(
                hosterUrl = when (lang.trim()) {
                    "VOSTFR" -> url
                    "VF" -> url.replace("-vostfr", "-vf").replaceFirst(animeSlugRegex, "$1-vf")
                    else -> return@mapNotNull null
                },
                hosterName = lang.trim(),
            )
        } ?: emptyList()
    }

    // ============================== Videos ===============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> = with(hoster) {
        val html = client.get("$baseUrl$hosterUrl", headers).body.string()

        sourcesRegex.find(html)?.let { match ->
            iframeSrcRegex.findAll(match.groupValues[1])
                .toList()
                .parallelCatchingFlatMap {
                    val capturedUrl = it.groupValues[1].replace("\\/", "/")
                    extractVideos(capturedUrl, hosterName, supportedServers)
                }
        } ?: emptyList()
    }

    // ============================== Utils ===============================
    protected open fun SharedPreferences.getBooleanOrNull(key: String): Boolean? = if (contains(key)) getBoolean(key, false) else null

    protected open suspend fun itHasVf(url: String, vararg bonusString: String): Boolean = preferences.getBooleanOrNull("$HAS_VF_KEY$url")
        ?: true.takeIf { url.contains("-vf", ignoreCase = true) }
        ?: true.takeIf { bonusString.any { it.contains("(VF)", ignoreCase = true) } }
        ?: runCatching {
            client.head("$baseUrl${url.removeSuffix("/")}-vf", headers).use { it.isSuccessful }
        }.getOrDefault(false).also { it.setVf(url) }

    protected open fun Boolean.setVf(url: String): Unit = preferences.edit().putBoolean("$HAS_VF_KEY$url", this).apply()

    protected open fun SAnime.removeFrench(): SAnime = apply {
        if (url.contains("-vf", ignoreCase = true) || title.contains("(VF)", ignoreCase = true)) {
            url = url.replace("-vf", "", ignoreCase = true)
            title = title.replace("(VF)", "", ignoreCase = true).trim()
            true.setVf(url)
        }
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        const val HAS_VF_KEY = "meta_has_vf_"

        val TITLE_CLEAN_REGEX = Regex("""\s*\((?:VF|VOSTFR)\)""", RegexOption.IGNORE_CASE)
        val sourcesRegex = Regex("""var\s+thisChapterSources\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        val iframeSrcRegex = Regex("""src=\\?["'](.+?)\\?["']""")
    }
}
