// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.movix

import eu.kanade.tachiyomi.animeextension.fr.movix.dto.CpasmalRes
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixDramaResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixFstreamResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixImdbResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixMovieLinksResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixPurstreamResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixTmdbResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixTvLinksResponse
import eu.kanade.tachiyomi.animeextension.fr.movix.dto.MovixWiflixResponse
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.Source
import fr.bluecxt.core.model.VoiceLanguage.VA
import fr.bluecxt.core.model.VoiceLanguage.VCN
import fr.bluecxt.core.model.VoiceLanguage.VF
import fr.bluecxt.core.model.VoiceLanguage.VOSTFR
import fr.bluecxt.core.utils.addClientHints
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import uy.kohesive.injekt.injectLazy

private val MOVIX_REDIRECT_REGEX = Regex("""La seule adresse active de Movix est <a href="(https://[^"]+)"""")

abstract class BaseMovix(override val name: String) :
    Source(),
    CommonPreferences {
    override val defaultBaseUrl = "https://movix.fun"
    override val lang = "fr"
    override val supportsLatest = true
    override val json: Json by injectLazy()

    // Default servers from Movix
    override val supportedServers = listOf(
        "Sibnet", "Sendvid", "Vidmoly", "Filemoon", "Dood", "Streamtape",
        "Vidoza", "Voe", "Minochinos", "Embed4me", "Lulu", "Uqload",
        "Okru", "Mymail", "Vidara", "Streamix",
    )
    override val defaultServer = "Vidmoly"
    override val supportedVoices = setOf(VOSTFR, VF, VA, VCN)

    protected var dynamicBaseUrl: String? = null

    override val baseUrl: String
        get() {
            val prefUrl = currentBaseUrl
            if (prefUrl.isNotEmpty() && prefUrl != "https://movix.online") {
                dynamicBaseUrl = prefUrl
                return prefUrl
            }
            dynamicBaseUrl?.let { return it }
            return fetchAndSaveRealUrl()
        }

    override val baseUrlSummary: String
        get() = "Laissez vide pour trouver automatiquement le domaine actif via movix.online. Actuel: $baseUrl"

    private fun fetchAndSaveRealUrl(): String = try {
        val html = client.newCall(GET("https://movix.online/")).execute().use { it.body.string() }
        val match = MOVIX_REDIRECT_REGEX.find(html)
        if (match != null) {
            val domain = match.groupValues[1].removeSuffix("/")
            preferences.edit().putString(CommonPreferences.PREF_URL_KEY, domain).apply()
            dynamicBaseUrl = domain
            domain
        } else {
            defaultBaseUrl
        }
    } catch (_: Exception) {
        defaultBaseUrl
    }

    protected val domain: String
        get() = baseUrl.toHttpUrl().host

    protected val apiUrl: String
        get() = "https://api.$domain"

    protected val apiHeaders
        get() = headersBuilder()
            .set("Referer", "$baseUrl/")
            .removeAll("Origin")
            .addClientHints()
            .build()

    companion object {
        const val PREFIX_SEARCH = "id:"
    }

    protected fun buildMovixHosters(
        type: String,
        tmdbId: String,
        season: String?,
        ep: String?,
    ): List<Hoster> {
        val query = if (type == "tv" && season != null && ep != null) "?season=$season&episode=$ep" else ""
        return buildList {
            add(Hoster(hosterName = "Movix", internalData = "links/$type/$tmdbId$query|$type|$tmdbId|$season|$ep"))
            add(Hoster(hosterName = "MovixTmdb", internalData = "tmdb/$type/$tmdbId$query|$type|$tmdbId|$season|$ep"))
            add(Hoster(hosterName = "IMDB", internalData = "imdb/$type/$tmdbId|$type|$tmdbId|$season|$ep"))
            if (type == "movie") {
                add(Hoster(hosterName = "FStream", internalData = "fstream/$type/$tmdbId|$type|$tmdbId|$season|$ep"))
                add(Hoster(hosterName = "Wiflix", internalData = "wiflix/$type/$tmdbId|$type|$tmdbId|$season|$ep"))
                add(Hoster(hosterName = "Cpasmal", internalData = "cpasmal/$type/$tmdbId|$type|$tmdbId|$season|$ep"))
            } else {
                add(Hoster(hosterName = "FStream", internalData = "fstream/$type/$tmdbId/season/$season|$type|$tmdbId|$season|$ep"))
                add(Hoster(hosterName = "Wiflix", internalData = "wiflix/$type/$tmdbId/$season|$type|$tmdbId|$season|$ep"))
                add(Hoster(hosterName = "Cpasmal", internalData = "cpasmal/$type/$tmdbId/$season/$ep|$type|$tmdbId|$season|$ep"))
                add(Hoster(hosterName = "Drama", internalData = "drama/$type/$tmdbId$query|$type|$tmdbId|$season|$ep"))
            }
            add(
                Hoster(
                    hosterName = "Purstream",
                    internalData = if (type == "movie") {
                        "purstream/movie/$tmdbId/stream|$type|$tmdbId|$season|$ep"
                    } else {
                        "purstream/tv/$tmdbId/stream$query|$type|$tmdbId|$season|$ep"
                    },
                ),
            )
        }
    }

    protected suspend fun fetchApiResponse(targetUrl: String): String? {
        val res = client.newCall(
            Request.Builder()
                .url(targetUrl)
                .headers(apiHeaders)
                .build(),
        ).awaitSuccess()

        val location = res.header("location")
        if (res.isRedirect && !location.isNullOrBlank()) {
            res.close()
            return client.newCall(
                Request.Builder().url(location).headers(apiHeaders).build(),
            ).awaitSuccess().use { it.body.string() }
        }

        return res.use { it.body.string() }
    }

    protected fun isValidResponse(response: String): Boolean {
        val lower = response.lowercase()
        return listOf("success", "player_links", "iframe_src", "series", "sources", "players", "links", "purstream_id", "wiflix")
            .any { lower.contains(it) }
    }

    protected fun extractLinks(brand: String, response: String, type: String, episode: String?): List<String> {
        val links = mutableListOf<String>()
        val fixedResponse = response.replace("\"players\":", "\"links\":")

        when (brand) {
            "Movix" -> extractMovixLinks(response, type, links)

            "MovixTmdb" -> extractMovixTmdbLinks(response, links)

            "IMDB" -> extractImdbLinks(response, episode, links)

            "FStream" -> extractFstreamLinks(fixedResponse, type, episode, links)

            "Wiflix" -> extractWiflixLinks(response, type, episode, links)

            "Cpasmal" -> json.decodeFromString<CpasmalRes>(fixedResponse).links?.values?.flatten()?.forEach { it.url?.let(links::add) }

            "Drama" -> json.decodeFromString<MovixDramaResponse>(response).data?.forEach { it.link?.let(links::add) }

            "Purstream" -> json.decodeFromString<MovixPurstreamResponse>(response).sources?.forEach { source ->
                source.url?.takeIf { it.isNotBlank() }?.let(links::add)
            }
        }

        return links.distinct()
    }

    private fun extractMovixLinks(response: String, type: String, links: MutableList<String>) {
        if (type == "movie") {
            json.decodeFromString<MovixMovieLinksResponse>(response).data?.links?.let(links::addAll)
        } else {
            json.decodeFromString<MovixTvLinksResponse>(response).data?.forEach { data ->
                data.links?.let(links::addAll)
            }
        }
    }

    private fun extractMovixTmdbLinks(response: String, links: MutableList<String>) {
        val res = json.decodeFromString<MovixTmdbResponse>(response)
        res.playerLinks?.forEach { it.decodedUrl?.let(links::add) }
        res.currentEpisode?.playerLinks?.forEach { it.decodedUrl?.let(links::add) }
        res.iframeSrc?.let(links::add)
        res.currentEpisode?.iframeSrc?.let(links::add)
    }

    private fun extractImdbLinks(response: String, episode: String?, links: MutableList<String>) {
        val seriesList = json.decodeFromString<MovixImdbResponse>(response).series ?: return
        for (series in seriesList) {
            val episodes = series.seasons.orEmpty().flatMap { it.episodes.orEmpty() }
            for (ep in episodes) {
                val matches = episode == null || ep.number == episode || ep.number?.toIntOrNull() == episode.toIntOrNull()
                if (!matches) continue
                for (version in ep.versions?.values.orEmpty()) {
                    version.players.orEmpty().mapNotNull { it.link }.forEach(links::add)
                }
            }
        }
    }

    private fun extractFstreamLinks(fixedResponse: String, type: String, episode: String?, links: MutableList<String>) {
        val res = json.decodeFromString<MovixFstreamResponse>(fixedResponse)
        if (type == "movie") {
            res.links?.values?.flatten()?.forEach { it.url?.let(links::add) }
        } else {
            val target = res.episodes?.entries?.find {
                it.key == episode || it.key.toIntOrNull() == episode?.toIntOrNull()
            }?.value
            target?.languages?.values?.flatten()?.forEach { it.url?.let(links::add) }
        }
    }

    private fun extractWiflixLinks(response: String, type: String, episode: String?, links: MutableList<String>) {
        val res = json.decodeFromString<MovixWiflixResponse>(response)
        if (type == "movie") {
            res.movie?.values?.flatten()?.forEach { it.url?.let(links::add) }
        } else {
            val epData = res.episodes?.entries?.find {
                it.key == episode || it.key.toIntOrNull() == episode?.toIntOrNull()
            }?.value
            epData?.vf?.forEach { it.url?.let(links::add) }
            epData?.vostfr?.forEach { it.url?.let(links::add) }
        }
    }
}
