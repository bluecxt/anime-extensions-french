package eu.kanade.tachiyomi.animeextension.fr.franime

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Anime
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.CommonPreferences.Companion.PREF_VOICES_KEY
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.Source
import fr.bluecxt.core.fetchTmdbMetadata
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class FrAnime :
    Source(),
    CommonPreferences {

    override val name = "FrAnime"

    override val defaultBaseUrl = "https://franime.fr"
    override val supportedServers = listOf("Sibnet", "Sendvid", "Vidmoly", "Filemoon", "Vk", "Embed4me", "Minochinos", "Okru")

    override val defaultServer = "Vidmoly"

    override val baseUrl: String get() = currentBaseUrl

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super<CommonPreferences>.setupPreferenceScreen(screen)
    }

    override val client = super.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (url.contains("nautiljon.com")) {
                val newRequest = request.newBuilder()
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Dest", "image")
                    .build()
                chain.proceed(newRequest)
            } else {
                chain.proceed(request)
            }
        }
        .build()

    private val domain: String
        get() = baseUrl.toHttpUrl().host

    private val baseApiUrl: String
        get() = "https://api.$domain/api"

    private val baseApiAnimeUrl: String
        get() = "$baseApiUrl/anime"

    override val lang = "fr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("User-Agent", DEFAULT_USER_AGENT)

    override val json: Json by injectLazy()

    companion object {
        const val PREFIX_SEARCH = "id:"
    }

    private val database by lazy {
        client.newCall(GET("$baseApiUrl/animes/", headers)).execute()
            .body.string()
            .let { json.decodeFromString<List<Anime>>(it) }
            .distinctBy { it.sourceUrl.ifEmpty { it.id.toString() } }
    }

    override suspend fun getPopularAnime(page: Int) = pagesToAnimesPage(database.sortedByDescending { it.note }, page)
    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int) = pagesToAnimesPage(database.reversed(), page)
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return AnimesPage(emptyList(), false)

        if (trimmedQuery.lowercase().startsWith(PREFIX_SEARCH)) {
            val id = trimmedQuery.substring(PREFIX_SEARCH.length).trim()
            val result = database.filter { it.id.toString() == id || titleToUrl(it.originalTitle) == id }
            return pagesToAnimesPage(result, page)
        }

        val results = database.map { anime ->
            val titles = listOfNotNull(
                anime.title,
                anime.originalTitle,
                anime.titlesAlt.en,
                anime.titlesAlt.enJp,
                anime.titlesAlt.jaJp,
                titleToUrl(anime.originalTitle).replace("-", " "),
            )
            val maxScore = titles.maxOfOrNull { similarityScore(trimmedQuery, it) } ?: 0
            anime to maxScore
        }.filter { it.second > 30 }
            .sortedByDescending { it.second }
            .map { it.first }

        return pagesToAnimesPage(results, page)
    }
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val cleanUrl = anime.url.substringBefore("#").substringBefore("?")
        val pathSegment = (baseUrl + cleanUrl).toHttpUrl().encodedPathSegments.last()
        val animeData = database.firstOrNull { it.id.toString() == pathSegment }
            ?: database.firstOrNull { titleToUrl(it.originalTitle) == pathSegment }

        val searchTitle = animeData?.title?.takeIf { it.isNotBlank() } ?: animeData?.originalTitle ?: anime.title
        val tmdbSearchTitle = searchTitle.replace(Regex("(?i)\\s*Kai\\s*"), " ").trim()

        val sNumFromUrl = anime.url.substringAfter("#s", "").substringBefore("|").toIntOrNull()
        val searchQueries = mutableListOf(tmdbSearchTitle)
        animeData?.titlesAlt?.en?.let { if (it.isNotBlank()) searchQueries.add(it) }
        animeData?.titlesAlt?.enJp?.let { if (it.isNotBlank()) searchQueries.add(it) }
        animeData?.titlesAlt?.jaJp?.let { if (it.isNotBlank()) searchQueries.add(it) }

        var tmdbMetadata: fr.bluecxt.core.TmdbMetadata? = null
        for (query in searchQueries.distinct()) {
            tmdbMetadata = if (sNumFromUrl != null) fetchTmdbMetadata(query, sNumFromUrl) else fetchTmdbMetadata(query)
            if (tmdbMetadata != null) break
        }

        anime.description = buildString {
            animeData?.let {
                if (!tmdbMetadata?.releaseDate.isNullOrBlank()) {
                    append("Date de sortie : ${tmdbMetadata.releaseDate}\n")
                } else if (!it.startDate.isNullOrBlank()) {
                    append("Date de sortie : ${it.startDate}\n")
                }
                append("Note : ${it.note} / 10\n\n")
            }

            val synopsis = tmdbMetadata?.summary ?: animeData?.description?.replace("\\n", "\n")
            append(synopsis ?: "")
        }.trim()

        tmdbMetadata?.posterUrl?.let { anime.thumbnail_url = it }
        tmdbMetadata?.author?.let { anime.author = it }
        tmdbMetadata?.artist?.let { anime.artist = it }

        if (sNumFromUrl == null && animeData != null && animeData.seasons.size > 1) {
            anime.coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Seasons)
        } else {
            anime.coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
            if (sNumFromUrl != null) anime.coreSetSeasonNumber(-2.0)
        }

        anime.initialized = true
        return anime
    }
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val cleanUrl = anime.url.substringBefore("#").substringBefore("?")
        val url = (baseUrl + cleanUrl).toHttpUrl()
        val pathSegment = url.encodedPathSegments.last()
        val animeData = database.firstOrNull { it.id.toString() == pathSegment } ?: database.first { titleToUrl(it.originalTitle) == pathSegment }

        val searchTitle = animeData.title.takeIf { it.isNotBlank() } ?: animeData.originalTitle
        val tmdbSearchTitle = searchTitle.replace(Regex("(?i)\\s*Kai\\s*"), " ").trim()

        val siteSeasons = animeData.seasons.mapIndexed { index, season ->
            val seasonNum = index + 1
            val tmdbSNum = season.title.replace("Saison ", "").toDoubleOrNull()?.toInt() ?: seasonNum
            val sTitle = if (season.title.contains(searchTitle, ignoreCase = true)) season.title else "$searchTitle ${season.title}"
            val sUrl = "$cleanUrl#s$seasonNum"
            Triple(sTitle, sUrl, tmdbSNum)
        }

        return coreBuildSeasonList(tmdbSearchTitle, siteSeasons, parseStatus(animeData.status, animeData.seasons.size, animeData.seasons.size))
            .onEach { it.coreSetSeasonNumber(-2.0) }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val cleanUrl = anime.url.substringBefore("#").substringBefore("?")
        val url = (baseUrl + cleanUrl).toHttpUrl()
        val pathSegment = url.encodedPathSegments.last()
        val animeData = database.firstOrNull { it.id.toString() == pathSegment } ?: database.first { titleToUrl(it.originalTitle) == pathSegment }

        val searchTitle = animeData.title.takeIf { it.isNotBlank() } ?: animeData.originalTitle
        val isKai = searchTitle.contains("Kai", ignoreCase = true) || animeData.format.equals("KAI", ignoreCase = true)
        val tmdbSearchTitle = searchTitle.replace(Regex("(?i)\\s*Kai\\s*"), " ").trim()

        val sNumFromUrl = anime.url.substringAfter("#s", "").substringBefore("|").toIntOrNull()
        val seasonsToProcess = if (sNumFromUrl != null) {
            listOf(animeData.seasons[sNumFromUrl - 1] to sNumFromUrl)
        } else {
            animeData.seasons.mapIndexed { index, season -> season to (index + 1) }
        }

        var globalEpisodeNumber = 1f
        val allEpisodes = mutableListOf<SEpisode>()

        val format = animeData.format.uppercase()
        val isMovie = format == "FILM" || format == "OVA" || format == "SPECIAL"
        val isSingleEpisode = seasonsToProcess.size == 1 && seasonsToProcess[0].first.episodes.size == 1

        for ((season, seasonNumber) in seasonsToProcess) {
            val tmdbSNum = season.title.replace("Saison ", "").toDoubleOrNull()?.toInt() ?: seasonNumber
            val offsets = if (sNumFromUrl != null) {
                var siteOffset = 0
                for (i in 0 until (seasonNumber - 1)) {
                    val prevSeason = animeData.seasons[i]
                    val prevTmdbS = prevSeason.title.replace("Saison ", "").toDoubleOrNull()?.toInt() ?: (i + 1)
                    if (prevTmdbS == tmdbSNum) siteOffset += prevSeason.episodes.size
                }
                siteOffset to 0
            } else {
                Pair(0, 0)
            }

            val tmdbMetadata = if (isKai) null else fetchTmdbMetadata(tmdbSearchTitle, tmdbSNum)

            val rawEpisodes = season.episodes.mapIndexedNotNull { eIndex, episode ->
                val availableLangs = mutableListOf<String>()
                if (episode.languages.vo.players.isNotEmpty()) availableLangs.add("VOSTFR")
                if (episode.languages.vf.players.isNotEmpty()) availableLangs.add("VF")

                if (availableLangs.isEmpty()) return@mapIndexedNotNull null

                SEpisode.create().apply {
                    val epNumber = eIndex + 1
                    setUrlWithoutDomain("$cleanUrl?s=$seasonNumber&ep=$epNumber")
                    var epTitle = episode.title?.trim() ?: ""
                    epTitle = epTitle.replace("Épisode", "Episode", true)
                    name = if (isSingleEpisode) searchTitle else epTitle
                    episode_number = epNumber.toFloat()
                    scanlator = availableLangs.joinToString(", ")
                }
            }

            val mappedEpisodes = coreMapEpisodes(
                rawEpisodes = rawEpisodes,
                tmdbMetadata = tmdbMetadata,
                tmdbS0Metadata = null,
                offsets = offsets,
                sNum = tmdbSNum,
                isMovie = isMovie,
                isOav = season.title.contains("OAV", true) || season.title.contains("Special", true),
            )

            mappedEpisodes.forEach {
                if (isKai) {
                    it.preview_url = null
                    it.summary = null
                }
                if (isSingleEpisode) {
                    val prefix = when {
                        format == "FILM" -> "[Movie] "
                        format == "OVA" || format == "SPECIAL" -> "[Special] "
                        else -> ""
                    }
                    it.name = "$prefix$searchTitle".trim()
                }
                if (sNumFromUrl == null && seasonsToProcess.size > 1) it.episode_number = globalEpisodeNumber++
            }
            allEpisodes.addAll(mappedEpisodes)
        }
        return allEpisodes.reversed()
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val url = (baseUrl + episode.url).toHttpUrl()
        val seasonNumber = url.queryParameter("s")?.toIntOrNull() ?: 1
        val episodeNumber = url.queryParameter("ep")?.toIntOrNull() ?: 1
        val pathSegment = url.encodedPathSegments.last { it.isNotBlank() }
        val animeData = database.firstOrNull { it.id.toString() == pathSegment } ?: database.first { titleToUrl(it.originalTitle) == pathSegment }
        val episodeData = animeData.seasons[seasonNumber - 1].episodes[episodeNumber - 1]

        val hosters = mutableListOf<Hoster>()
        if (episodeData.languages.vo.players.isNotEmpty()) {
            hosters.add(Hoster(hosterName = "VOSTFR", internalData = "${animeData.id}|${seasonNumber - 1}|${episodeNumber - 1}|vo"))
        }
        if (episodeData.languages.vf.players.isNotEmpty()) {
            hosters.add(Hoster(hosterName = "VF", internalData = "${animeData.id}|${seasonNumber - 1}|${episodeNumber - 1}|vf"))
        }
        return hosters
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val voices = preferences.getString(PREF_VOICES_KEY, "VOSTFR")!!
        return this.sortedByDescending { it.hosterName.contains(voices, true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split("|")
        val animeId = data[0]
        val seasonIdx = data[1].toInt()
        val episodeIdx = data[2].toInt()
        val lang = data[3]

        val animeData = database.first { it.id.toString() == animeId }
        val episodeData = animeData.seasons[seasonIdx].episodes[episodeIdx]
        val players = if (lang == "vo") episodeData.languages.vo.players else episodeData.languages.vf.players

        val langLabel = if (lang == "vo") "VOSTFR" else "VF"

        return players.withIndex().filter { !it.value.equals("TELECHARGEMENT UNIQUE", true) }.flatMap { (playerIdx, _) ->
            try {
                val responseBody = client.newCall(GET("$baseApiAnimeUrl/$animeId/$seasonIdx/$episodeIdx/$lang/$playerIdx", headers)).execute().body.string()

                var playerUrl = responseBody
                if (responseBody.contains("watch2")) {
                    val uri = responseBody.toHttpUrl()
                    playerUrl = decryptFrAnime(uri.queryParameter("a") ?: "")
                        ?: decryptFrAnime(uri.queryParameter("b") ?: "")
                        ?: decryptFrAnime(uri.queryParameter("c") ?: "") ?: responseBody
                } else if (!responseBody.startsWith("http")) {
                    playerUrl = decryptFrAnime(responseBody) ?: responseBody
                }

                // Utilise le core pour extraire les vidéos
                extractVideos(playerUrl, langLabel, supportedServers)
            } catch (e: Exception) {
                emptyList()
            }
        }.coreSortVideos()
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace(Regex("[^a-z0-9]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun editDistance(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                if (s1[i - 1] == s2[j - 1]) {
                    dp[j] = prev
                } else {
                    dp[j] = 1 + minOf(prev, minOf(dp[j - 1], dp[j]))
                }
                prev = temp
            }
        }
        return dp[s2.length]
    }

    private fun similarityScore(query: String, target: String): Int {
        val q = normalize(query)
        val t = normalize(target)
        if (q.isEmpty()) return 0
        if (t == q) return 100
        if (t.contains(q)) return 95
        val qWords = q.split(" ").filter { it.length > 1 }
        val tWords = t.split(" ")
        if (qWords.isNotEmpty()) {
            val wordMatches = qWords.count { qw -> tWords.any { tw -> tw == qw } }
            val wordScore = (wordMatches.toDouble() / qWords.size * 90).toInt()
            if (wordScore > 0) return wordScore
        }
        val longer = if (q.length > t.length) q else t
        if (longer.isEmpty()) return 0
        val dist = editDistance(q, t)
        return ((longer.length - dist).toDouble() / longer.length * 80).toInt()
    }

    private fun pagesToAnimesPage(pages: List<Anime>, page: Int): AnimesPage {
        val chunks = pages.chunked(20)
        val hasNextPage = chunks.size > page
        val entries = pageToSAnimes(chunks.getOrNull(page - 1) ?: emptyList())
        return AnimesPage(entries, hasNextPage)
    }

    private val titleRegex by lazy { Regex("[^A-Za-z0-9 ]") }
    private fun titleToUrl(title: String) = titleRegex.replace(title, "").replace(" ", "-").lowercase()

    private fun pageToSAnimes(page: List<Anime>): List<SAnime> = page.map { anime ->
        SAnime.create().apply {
            title = anime.title
            thumbnail_url = anime.poster
            genre = anime.genres.joinToString()
            status = parseStatus(anime.status, anime.seasons.size, anime.seasons.size)
            description = buildString {
                if (!anime.startDate.isNullOrBlank()) append("Date de sortie : ${anime.startDate}\n")
                if (anime.format.isNotBlank()) append("Format : ${anime.format}\n")
                append("Note : ${anime.note} / 10\n\n")
                append(anime.description)
            }
            setUrlWithoutDomain("/anime/${anime.id}")
            if (anime.seasons.size > 1) {
                coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Seasons)
            } else {
                coreSetFetchType(eu.kanade.tachiyomi.animesource.model.FetchType.Episodes)
            }
        }
    }

    private fun parseStatus(statusString: String?, seasonCount: Int = 1, season: Int = 1): Int {
        if (season < seasonCount) return SAnime.COMPLETED
        return when (statusString?.trim()) {
            "EN COURS" -> SAnime.ONGOING
            "TERMINÉ" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun decryptFrAnime(encrypted: String): String? {
        if (encrypted.isEmpty()) return null
        val hexData = try {
            String(android.util.Base64.decode(encrypted, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            return null
        }
        if (hexData.isEmpty()) return null
        for (key in 0..255) {
            val sb = StringBuilder()
            try {
                var i = 0
                while (i < hexData.length) {
                    val hex = hexData.substring(i, i + 2)
                    val charCode = hex.toInt(16)
                    sb.append((charCode xor key).toChar())
                    i += 2
                }
                val result = sb.toString()
                if (result.startsWith("http")) return result
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }
}
