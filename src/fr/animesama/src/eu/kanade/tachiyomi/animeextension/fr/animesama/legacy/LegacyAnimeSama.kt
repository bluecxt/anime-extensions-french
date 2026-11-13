// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.legacy

import android.util.Log
import eu.kanade.tachiyomi.animeextension.fr.animesama.AnimeSama
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.utils.runCatchingCancellable
import keiyoushi.utils.parallelMap
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl

// To be removed in July 2027, once libraries have been migrated to JSON URLs.

/** Log tag of the legacy parser, kept apart from the main source logs. */
private const val LEGACY_LOG = "AnimeSamaLegacy"

/**
 * Languages explored by the legacy parser.
 *
 * The index of a language in this list is meaningful: legacy episode URLs store a list of URL
 * lists, one list per entry of [LANG_VALUES].
 */
object LegacyAnimeSama {
    val LANG_VALUES = listOf("vostfr", "vf", "vf1", "vf2", "va", "vcn", "vj", "vkr", "vqc")
}

private val epsArrayRegex = Regex("""(?:var|let|const)?\s*eps(\w+)\s*=\s*(\[[^\]]*\])""", RegexOption.DOT_MATCHES_ALL)
private val urlInArrayRegex = Regex("""['"]([^'"]+)['"]""")
private val animeNameCleanupRegex = Regex("""(?i)\s*(?:-\s*)?(?:Saison|Season|Film|Movie|OAV|OVA|Partie|Part)\b.*""")
private val commentRegex = Regex("""//.*|/\*[\s\S]*?\*/""")
private val panneauRegex = Regex("""panneauAnime\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")
private val seasonRegex = Regex("""(?:episodes|var|let|const)\s+([\w\d_]+)\s*=\s*['"]([^'"]+)['"]""")
private val seasonCleanRegex1 = Regex("""(?i)\s*-\s*(?:Saison|Season)\s*1(?!\d)""")
private val seasonCleanRegex2 = Regex("""(?i)\s*(?:Saison|Season)\s*1(?!\d)""")
private val seasonCleanRegex3 = Regex("""(?i)\s*-\s*(?:Saison|Season)\s*(\d+)""")
private val seasonCleanRegex4 = Regex("""(?i)\s*(?:saison|season)\s*(\d+)""")
private val partCleanRegex = Regex("""(?i)Partie\s*(\d+)""")

/**
 * Fills a work whose URL was written before the JSON migration.
 *
 * Only the synopsis, the genres and the studio can be read from the HTML, and no TVDB lookup is
 * attempted: a notice invites the user to add the work again so it gets the current format.
 *
 * @param anime library entry, updated in place.
 * @return the same entry, enriched.
 */
suspend fun AnimeSama.getLegacyAnimeDetails(anime: SAnime): SAnime {
    Log.d(LEGACY_LOG, "getLegacyAnimeDetails: input url = '${anime.url}'")
    val animeUrlPath = anime.url.substringBefore("#").removeSuffix("/")
    Log.d(LEGACY_LOG, "getLegacyAnimeDetails: requesting '$baseUrl$animeUrlPath'")
    val response = client.newCall(GET("$baseUrl$animeUrlPath", headers)).awaitSuccess()

    val doc = response.useAsJsoup()

    val descriptionText = doc.selectFirst("p#synopsisText")?.text() ?: ""
    val genres = doc.select("div.genres-wrap > span").joinToString { it.text() }
    val authorText = doc.selectFirst("div.info-grid > span:contains(Studio) + .info-val")?.text() ?: ""

    return anime.apply {
        author = authorText
        genre = genres
        description = buildString {
            append("animé ajouté avant la migration, certaine functionnalité peuvent ne pas functionner correctement, veuiller re ajouter cette animé dans votre librairie pour faire la migration\n\n\n")
            append(descriptionText)
        }
    }
}

/**
 * Lists the seasons of a work written before the JSON migration.
 *
 * Seasons are rebuilt from the `panneauAnime(...)` calls and from the `episodes<X> = "..."`
 * variables of the page scripts. The produced URLs keep the historical `path#anchor|title`
 * format so that the rest of the legacy parser keeps working.
 *
 * @param anime legacy work whose seasons are wanted.
 * @return one [SAnime] per season, ready to list its episodes.
 */
suspend fun AnimeSama.getLegacySeasonList(anime: SAnime): List<SAnime> {
    Log.d(LEGACY_LOG, "getLegacySeasonList: input url = '${anime.url}'")
    val animeUrlPath = anime.url.substringBefore("#").removeSuffix("/")
    Log.d(LEGACY_LOG, "getLegacySeasonList: requesting '$baseUrl$animeUrlPath'")
    val response = client.newCall(GET("$baseUrl$animeUrlPath", headers)).awaitSuccess()
    val animeDoc = response.useAsJsoup()

    val animeName = (animeDoc.selectFirst("div.my-2 > h1")?.text() ?: "").trim()
        .replace(animeNameCleanupRegex, "")
        .trim()

    val scripts = animeDoc.select("script").toString()
    val uncommented = commentRegex.replace(scripts, "")

    val panneauMatches = panneauRegex.findAll(uncommented).map {
        val name = it.groupValues[1].trim()
        var stem = it.groupValues[2].trim().removeSuffix("/")
        for (lang in LegacyAnimeSama.LANG_VALUES) {
            if (stem.endsWith("/$lang", ignoreCase = true)) {
                stem = stem.substringBeforeLast("/")
                break
            }
        }
        name to stem
    }.toList()

    val seasonMatches = seasonRegex.findAll(uncommented).mapNotNull {
        val name = it.groupValues[1].trim()
        val stem = it.groupValues[2].trim().removeSuffix("/")
        val isLangOnly = LegacyAnimeSama.LANG_VALUES.any { lang -> stem.equals(lang, ignoreCase = true) }
        if (stem.contains("/") && !isLangOnly) {
            name to stem
        } else {
            null
        }
    }.toList()

    val distinctSeasons = (panneauMatches + seasonMatches)
        .filter { it.second.isNotBlank() }
        .distinctBy { it.second }

    Log.d(LEGACY_LOG, "getLegacySeasonList: found ${distinctSeasons.size} seasons")

    return distinctSeasons.map { (seasonName, seasonStem) ->
        val cleanSeasonName = seasonName
            .replace(seasonCleanRegex1, "")
            .replace(seasonCleanRegex2, "")
            .replace(seasonCleanRegex3, " $1")
            .replace(seasonCleanRegex4, " $1")
            .replace(partCleanRegex, "Part $1")
            .trim()

        val fullTitle = if (cleanSeasonName.contains(animeName, true)) {
            cleanSeasonName
        } else {
            "$animeName $cleanSeasonName"
        }

        SAnime.create().apply {
            title = fullTitle
            url = "${animeUrlPath.removeSuffix("/")}/$seasonStem#s-2.0|${fullTitle.replace("|", "")}"
            thumbnail_url = animeDoc.getElementById("coverOeuvre")?.attr("src")
            initialized = true
            fetch_type = FetchType.Episodes
        }
    }
}

/**
 * Builds the episodes of a legacy season.
 *
 * The `episodes.js` file of every language is read in parallel and merged by index. A page whose
 * path is still a work page uses its first season, and an anchor that does not start with `s`
 * designates a movie, in which case a single episode is returned.
 *
 * @param anime legacy season whose episodes are wanted.
 * @return the episodes, newest first.
 */
suspend fun AnimeSama.getLegacyEpisodeList(anime: SAnime): List<SEpisode> {
    Log.d(LEGACY_LOG, "getLegacyEpisodeList: input url = '${anime.url}'")
    val animeUrlPath = anime.url.substringBefore("#").removeSuffix("/")
    val movieIndex = anime.url.substringAfter("#", "").takeIf { it.isNotBlank() && !it.startsWith("s") }?.toIntOrNull()

    var currentUrlPath = animeUrlPath
    val rawUrl = "$baseUrl$animeUrlPath".toHttpUrl()
    val isHub = rawUrl.pathSegments.size <= 2

    if (isHub) {
        val hubUrl = "$baseUrl$animeUrlPath/"
        Log.d(LEGACY_LOG, "getLegacyEpisodeList: isHub=true, requesting '$hubUrl'")
        val response = client.newCall(GET(hubUrl, headers)).await()
        if (!response.isSuccessful) return emptyList()
        val doc = response.useAsJsoup()
        val scripts = doc.select("script").toString()
        val uncommented = commentRegex.replace(scripts, "")

        val panneaux = panneauRegex.findAll(uncommented).map { it.groupValues[2].trim().removeSuffix("/") }.toList()
        val legacySeasons = seasonRegex.findAll(uncommented).map { it.groupValues[2].trim().removeSuffix("/") }.toList()
        val allSeasons = (panneaux + legacySeasons).filter { it.isNotBlank() }

        if (allSeasons.isNotEmpty()) {
            currentUrlPath = "$animeUrlPath/${allSeasons.first()}"
        }
    }

    var seasonRootPath = currentUrlPath
    for (suffix in LegacyAnimeSama.LANG_VALUES) {
        if (seasonRootPath.endsWith("/" + suffix, ignoreCase = true)) {
            seasonRootPath = seasonRootPath.substringBeforeLast("/")
            break
        }
    }

    Log.d(LEGACY_LOG, "getLegacyEpisodeList: resolved seasonRootPath = '$seasonRootPath'")

    val players = coroutineScope {
        LegacyAnimeSama.LANG_VALUES.map { lang ->
            async { fetchLegacyPlayers("$baseUrl$seasonRootPath/$lang") }
        }.awaitAll()
    }

    val episodes = legacyPlayersToEpisodes(players)
    Log.d(LEGACY_LOG, "getLegacyEpisodeList: total extracted episodes = ${episodes.size}")
    return if (movieIndex == null) episodes.reversed() else listOf(episodes[movieIndex])
}

/**
 * Reads the `episodes.js` file of one language and aligns the URL arrays by episode index.
 *
 * An HTML answer (an error page served by the site) is ignored, as is a missing file: those
 * cases are not reported as errors, so that the other languages still produce a list.
 *
 * @param url absolute URL of the language folder, for example `.../saison1/vostfr`.
 * @return one list of URLs per episode, empty when nothing could be read.
 */
private suspend fun AnimeSama.fetchLegacyPlayers(url: String): List<List<String>> {
    val cleanUrl = url.substringBefore("#")
    val docUrl = "${cleanUrl.removeSuffix("/")}/episodes.js"
    Log.d(LEGACY_LOG, "fetchLegacyPlayers: requesting '$docUrl'")
    val doc = runCatchingCancellable {
        client.newCall(GET(docUrl, headers)).await().use {
            if (!it.isSuccessful) {
                Log.d(LEGACY_LOG, "fetchLegacyPlayers: HTTP error ${it.code} for '$docUrl'")
                return emptyList()
            }
            it.body.string()
        }
    }.onFailure { e ->
        Log.e(LEGACY_LOG, "fetchLegacyPlayers: network exception for '$docUrl': ${e.message}")
        return emptyList()
    }.getOrNull() ?: return emptyList()

    if (doc.trim().startsWith("<")) return emptyList()

    val servers = epsArrayRegex.findAll(doc).map { match ->
        val arrayContent = match.groupValues[2]
        urlInArrayRegex.findAll(arrayContent).map { it.groupValues[1].trim() }.toList()
    }.filter { it.isNotEmpty() }.toList()

    if (servers.isEmpty()) return emptyList()
    val maxEpisodes = servers.maxOfOrNull { it.size } ?: 0
    return List(maxEpisodes) { i -> servers.mapNotNull { it.getOrNull(i) }.distinct() }
}

/**
 * Converts the aggregated URL lists into [SEpisode] entries.
 *
 * The language index is kept implicitly by the order of the JSON stored in the library, which
 * is what allows [getLegacyHosterList] to rebuild the hoster labels later.
 *
 * @param list URLs grouped by language, then by episode.
 * @return the numbered episodes, labelled with the languages they are available in.
 */
private fun AnimeSama.legacyPlayersToEpisodes(
    list: List<List<List<String>>>,
): List<SEpisode> {
    val maxEpisodes = list.maxOfOrNull { it.size } ?: 0

    return List(maxEpisodes) { i ->
        val epUrls = List(list.size) { langIndex ->
            list[langIndex].getOrNull(i) ?: emptyList()
        }

        SEpisode.create().apply {
            episode_number = (i + 1).toFloat()
            name = "Épisode ${i + 1}"
            url = json.encodeToString(epUrls)
            scanlator = epUrls.mapIndexedNotNull { index, urls ->
                if (urls.isNotEmpty()) LegacyAnimeSama.LANG_VALUES.getOrNull(index) else null
            }.joinToString().uppercase()
        }
    }
}

/**
 * Rebuilds the hosters of a legacy episode from the URL lists stored in the library.
 *
 * Each entry embeds its URLs and its language label in `internalData`, in the `json|language`
 * form expected by [getLegacyVideoList].
 *
 * @param episode legacy episode whose URL holds the URL lists.
 * @return one [Hoster] per language having at least one player.
 */
fun AnimeSama.getLegacyHosterList(episode: SEpisode): List<Hoster> {
    val playerUrls = try {
        json.decodeFromString<List<List<String>>>(episode.url)
    } catch (_: Exception) {
        return emptyList()
    }

    return playerUrls.mapIndexedNotNull { i, playerUrl ->
        if (playerUrl.isEmpty()) return@mapIndexedNotNull null
        val lang = LegacyAnimeSama.LANG_VALUES.getOrElse(i) { "vostfr" }.uppercase()
        Hoster(
            hosterName = lang,
            internalData = json.encodeToString(playerUrl) + "|" + lang,
        )
    }
}

/**
 * Resolves the videos of a legacy hoster.
 *
 * @param hoster entry produced by [getLegacyHosterList], holding `json|language`.
 * @return the extracted videos, every quality included.
 */
suspend fun AnimeSama.getLegacyVideoList(hoster: Hoster): List<Video> {
    val raw = hoster.internalData.takeIf { it.isNotBlank() } ?: return emptyList()
    val data = raw.split("|")
    if (data.size < 2) return emptyList()
    val urls = try {
        json.decodeFromString<List<String>>(data[0])
    } catch (_: Exception) {
        return emptyList()
    }
    val lang = data[1]

    return urls.parallelMap { playerUrl ->
        extractVideos(playerUrl, lang, supportedServers)
    }.flatten()
}
