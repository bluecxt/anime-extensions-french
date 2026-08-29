// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parallelMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

// a retirer en juillet 2027

private const val LEGACY_LOG = "AnimeSamaLegacy"

class LegacyAnimeSama {
    companion object {
        val LANG_VALUES = listOf("vostfr", "vf", "vf1", "vf2", "va", "vcn", "vj", "vkr", "vqc")
    }
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

// Helper extension functions to handle legacy URLs (non-JSON format) without TMDB integration.
suspend fun AnimeSama.getLegacyAnimeDetails(anime: SAnime): SAnime {
    Log.d(LEGACY_LOG, "getLegacyAnimeDetails: input url = '${anime.url}'")
    val animeUrlPath = anime.url.substringBefore("#").removeSuffix("/")
    Log.d(LEGACY_LOG, "getLegacyAnimeDetails: requesting '$baseUrl$animeUrlPath'")
    val response = client.newCall(GET("$baseUrl$animeUrlPath", headers)).awaitSuccess()

    val doc = response.asJsoup()

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

suspend fun AnimeSama.getLegacySeasonList(anime: SAnime): List<SAnime> {
    Log.d(LEGACY_LOG, "getLegacySeasonList: input url = '${anime.url}'")
    val animeUrlPath = anime.url.substringBefore("#").removeSuffix("/")
    Log.d(LEGACY_LOG, "getLegacySeasonList: requesting '$baseUrl$animeUrlPath'")
    val response = client.newCall(GET("$baseUrl$animeUrlPath", headers)).awaitSuccess()
    val animeDoc = response.asJsoup()

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
        val doc = response.asJsoup()
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

private suspend fun AnimeSama.fetchLegacyPlayers(url: String): List<List<String>> {
    val cleanUrl = url.substringBefore("#")
    val docUrl = "${cleanUrl.removeSuffix("/")}/episodes.js"
    Log.d(LEGACY_LOG, "fetchLegacyPlayers: requesting '$docUrl'")
    val doc = try {
        client.newCall(GET(docUrl, headers)).await().use {
            if (!it.isSuccessful) {
                Log.d(LEGACY_LOG, "fetchLegacyPlayers: HTTP error ${it.code} for '$docUrl'")
                return emptyList()
            }
            it.body.string()
        }
    } catch (e: Exception) {
        Log.e(LEGACY_LOG, "fetchLegacyPlayers: network exception for '$docUrl': ${e.message}")
        return emptyList()
    }

    if (doc.trim().startsWith("<")) return emptyList()

    val servers = epsArrayRegex.findAll(doc).map { match ->
        val arrayContent = match.groupValues[2]
        urlInArrayRegex.findAll(arrayContent).map { it.groupValues[1].trim() }.toList()
    }.filter { it.isNotEmpty() }.toList()

    if (servers.isEmpty()) return emptyList()
    val maxEpisodes = servers.maxOfOrNull { it.size } ?: 0
    return List(maxEpisodes) { i -> servers.mapNotNull { it.getOrNull(i) }.distinct() }
}

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

fun AnimeSama.getLegacyHosterList(episode: SEpisode): List<Hoster> {
    val playerUrls = try {
        json.decodeFromString<List<List<String>>>(episode.url)
    } catch (_: Exception) {
        return emptyList()
    }

    return playerUrls.mapIndexedNotNull { i, playerUrl ->
        if (playerUrl.isEmpty()) return@mapIndexedNotNull null
        val lang = LegacyAnimeSama.LANG_VALUES.getOrElse(i) { "vostfr" }.uppercase()
        Hoster(hosterName = lang, internalData = json.encodeToString(playerUrl) + "|" + lang)
    }.coreSortHosters()
}

suspend fun AnimeSama.getLegacyVideoList(hoster: Hoster): List<Video> {
    val data = hoster.internalData.split("|")
    if (data.size < 2) return emptyList()
    val urls = try {
        json.decodeFromString<List<String>>(data[0])
    } catch (_: Exception) {
        return emptyList()
    }
    val lang = data[1]

    return urls.parallelMap { playerUrl ->
        extractVideos(playerUrl, lang, supportedServers)
    }.flatten().sortVideos()
}
