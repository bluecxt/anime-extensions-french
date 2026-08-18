// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama

import app.cash.quickjs.QuickJs
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

class LegacyAnimeSama {
    companion object {
        val LANG_VALUES = listOf("VOSTFR", "VF", "VA", "VCN", "VJ", "VKR", "VQC")

        private val quickJsThreadLocal = ThreadLocal.withInitial { QuickJs.create() }

        fun quickJs(): QuickJs = quickJsThreadLocal.get()!!

        fun closeCurrentQuickJs() {
            quickJs().close()
            quickJsThreadLocal.remove()
        }
    }
}

// Helper extension functions to handle legacy URLs (non-JSON format) without TMDB integration.
suspend fun AnimeSama.getLegacyAnimeDetails(anime: SAnime): SAnime {
    val animeUrlPath = anime.url.substringBefore("#")
    val response = client.newCall(GET("$baseUrl$animeUrlPath")).awaitSuccess()
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
    val animeUrlPath = anime.url.substringBefore("#")
    val response = client.newCall(GET("$baseUrl$animeUrlPath")).awaitSuccess()
    val animeDoc = response.asJsoup()

    val animeName = (animeDoc.selectFirst("div.my-2 > h1")?.text() ?: "").trim()
        .replace(Regex("""(?i)\s*(?:-\s*)?(?:Saison|Season|Film|Movie|OAV|OVA|Partie|Part)\b.*"""), "")
        .trim()

    val scripts = animeDoc.select("script").toString()
    val commentRegex = Regex("""//.*|/\*[\s\S]*?\*/""")
    val uncommented = commentRegex.replace(scripts, "")
    val seasonRegex = Regex("""(?:episodes|var|let|const)\s+([\w\d_]+)\s*=\s*['"]([^'"]+)['"]""")

    val distinctSeasons = seasonRegex.findAll(uncommented).toList()
        .filter {
            val stem = it.groupValues[2].trim().removeSuffix("/")
            val isLangOnly = stem.equals("vostfr", true) || stem.equals("vf", true) ||
                stem.equals("vf1", true) || stem.equals("vf2", true) ||
                stem.equals("va", true) || stem.equals("vcn", true) ||
                stem.equals("vj", true) || stem.equals("vkr", true) ||
                stem.equals("vqc", true)
            stem.contains("/") && !isLangOnly
        }
        .distinctBy {
            it.groupValues[2].trim().removeSuffix("/")
                .substringBeforeLast("/", it.groupValues[2].trim().removeSuffix("/"))
        }

    return distinctSeasons.map { match ->
        val (seasonName, seasonStem) = match.destructured
        val cleanSeasonName = seasonName
            .replace(Regex("""(?i)\s*-\s*(?:Saison|Season)\s*1(?!\d)"""), "")
            .replace(Regex("""(?i)\s*(?:Saison|Season)\s*1(?!\d)"""), "")
            .replace(Regex("""(?i)\s*-\s*(?:Saison|Season)\s*(\d+)"""), " $1")
            .replace(Regex("""(?i)\s*(?:saison|season)\s*(\d+)"""), " $1")
            .replace(Regex("""(?i)Partie\s*(\d+)"""), "Part $1")
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
    val animeUrlPath = anime.url.substringBefore("#").removeSuffix("/")
    val movieIndex = anime.url.substringAfter("#", "").takeIf { it.isNotBlank() && !it.startsWith("s") }?.toIntOrNull()

    var currentUrlPath = animeUrlPath
    val rawUrl = "$baseUrl$animeUrlPath".toHttpUrl()
    val isHub = rawUrl.pathSegments.size <= 2

    if (isHub) {
        val response = client.newCall(GET("$baseUrl$animeUrlPath/")).await()
        if (!response.isSuccessful) return emptyList()
        val doc = response.asJsoup()
        val scripts = doc.select("script").toString()
        val commentRegex = Regex("""//.*|/\*[\s\S]*?\*/""")
        val uncommented = commentRegex.replace(scripts, "")
        val panneauRegex = Regex("""panneauAnime\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")
        val seasonRegex = Regex("""(?:episodes|var|let|const)\s+([\w\d_]+)\s*=\s*['"]([^'"]+)['"]""")

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

    val players = coroutineScope {
        LegacyAnimeSama.LANG_VALUES.map { lang ->
            async { fetchLegacyPlayers("$baseUrl$seasonRootPath/$lang") }
        }.awaitAll()
    }

    val episodes = legacyPlayersToEpisodes(players)
    return if (movieIndex == null) episodes.reversed() else listOf(episodes[movieIndex])
}

private suspend fun AnimeSama.fetchLegacyPlayers(url: String): List<List<String>> {
    val cleanUrl = url.substringBefore("#")
    val docUrl = "${cleanUrl.removeSuffix("/")}/episodes.js"
    val doc = try {
        client.newCall(GET(docUrl)).await().use {
            if (!it.isSuccessful) return emptyList()
            it.body.string()
        }
    } catch (_: Exception) {
        return emptyList()
    }

    if (doc.trim().startsWith("<")) return emptyList()

    val urls = try {
        val qjs = LegacyAnimeSama.quickJs()
        qjs.evaluate(doc)
        val json = qjs.evaluate(
            """
            JSON.stringify(
                Array.from({length: 40}, (e, i) => this['eps' + (i + 1)])
                    .filter(e => e !== undefined && e !== null)
            )
            """,
        ) as String
        Json.decodeFromString<List<List<String>>>(json)
    } catch (e: Exception) {
        emptyList()
    }

    if (urls.isEmpty() || urls[0].isEmpty()) return emptyList()
    val maxEpisodes = urls.maxOfOrNull { it.size } ?: 0
    return List(maxEpisodes) { i -> urls.mapNotNull { it.getOrNull(i) }.distinct() }
}

private fun AnimeSama.legacyPlayersToEpisodes(
    list: List<List<List<String>>>,
): List<SEpisode> {
    val maxEpisodes = list.fold(0) { acc, sublist -> maxOf(acc, sublist.size) }
    val episodes = mutableListOf<SEpisode>()

    for (i in 0 until maxEpisodes) {
        val epUrls = mutableListOf<List<String>>()
        for (langIndex in list.indices) {
            val epList = list[langIndex]
            val urls = epList.getOrNull(i) ?: emptyList()
            epUrls.add(urls)
        }

        episodes.add(
            SEpisode.create().apply {
                episode_number = (i + 1).toFloat()
                name = "Épisode ${i + 1}"
                url = json.encodeToString(epUrls)
            },
        )
    }
    return episodes
}

fun AnimeSama.getLegacyHosterList(episode: SEpisode): List<Hoster> {
    val playerUrls = try {
        json.decodeFromString<List<List<String>>>(episode.url)
    } catch (_: Exception) {
        return emptyList()
    }
    val hosters = mutableListOf<Hoster>()

    playerUrls.forEachIndexed { i, playerUrl ->
        if (playerUrl.isEmpty()) return@forEachIndexed
        val lang = LegacyAnimeSama.LANG_VALUES.getOrElse(i) { "VOSTFR" }
        hosters.add(Hoster(hosterName = lang, internalData = json.encodeToString(playerUrl) + "|" + lang))
    }
    return hosters.coreSortHosters()
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
