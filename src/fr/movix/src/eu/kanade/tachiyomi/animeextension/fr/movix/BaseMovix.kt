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
import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.Source
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.injectLazy

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

    // Supported voices
    override val supportedVoices = arrayOf("VOSTFR", "VF", "VA", "VCN", "VJ", "VKR", "VQC")

    protected var dynamicBaseUrl: String? = null

    override val baseUrl: String
        get() {
            val prefUrl = currentBaseUrl
            if (!prefUrl.isNullOrEmpty() && prefUrl != "https://movix.online") {
                dynamicBaseUrl = prefUrl
                return prefUrl
            }
            dynamicBaseUrl?.let { return it }
            return fetchAndSaveRealUrl()
        }

    override val baseUrlSummary: String
        get() = "Laissez vide pour trouver automatiquement le domaine actif via movix.online. Actuel: $baseUrl"

    private fun fetchAndSaveRealUrl(): String = try {
        val response = client.newCall(GET("https://movix.online/")).execute()
        val html = response.body.string()
        val regex = Regex("""La seule adresse active de Movix est <a href="(https://[^"]+)"""")
        val match = regex.find(html)
        if (match != null) {
            val domain = match.groupValues[1].removeSuffix("/")
            preferences.edit().putString(CommonPreferences.PREF_URL_KEY, domain).apply()
            dynamicBaseUrl = domain
            domain
        } else {
            defaultBaseUrl
        }
    } catch (e: Exception) {
        defaultBaseUrl
    }

    protected val domain: String
        get() = baseUrl.toHttpUrl().host

    protected val apiUrl: String
        get() = "https://api.$domain"

    override fun headersBuilder() = super.headersBuilder()

    protected val apiHeaders
        get() = headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("User-Agent", DEFAULT_USER_AGENT)
            .removeAll("Origin")
            .build()

    companion object {
        const val PREFIX_SEARCH = "id:"
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
            "Movix" -> {
                if (type == "movie") {
                    json.decodeFromString<MovixMovieLinksResponse>(response).data?.links?.let(links::addAll)
                } else {
                    json.decodeFromString<MovixTvLinksResponse>(response).data?.forEach { data ->
                        data.links?.let(links::addAll)
                    }
                }
            }

            "MovixTmdb" -> {
                json.decodeFromString<MovixTmdbResponse>(response).let { res ->
                    res.playerLinks?.forEach { it.decodedUrl?.let(links::add) }
                    res.currentEpisode?.playerLinks?.forEach { it.decodedUrl?.let(links::add) }
                    res.iframeSrc?.let(links::add)
                    res.currentEpisode?.iframeSrc?.let(links::add)
                }
            }

            "IMDB" -> {
                json.decodeFromString<MovixImdbResponse>(response).series?.forEach { series ->
                    series.seasons?.forEach { season ->
                        season.episodes?.filter {
                            episode == null || it.number == episode || it.number?.toIntOrNull() == episode.toIntOrNull()
                        }?.forEach { ep ->
                            ep.versions?.values?.forEach { version ->
                                version.players?.forEach { it.link?.let(links::add) }
                            }
                        }
                    }
                }
            }

            "FStream" -> {
                json.decodeFromString<MovixFstreamResponse>(fixedResponse).let { res ->
                    if (type == "movie") {
                        res.links?.values?.flatten()?.forEach { it.url?.let(links::add) }
                    } else {
                        val target = res.episodes?.entries?.find {
                            it.key == episode || it.key.toIntOrNull() == episode?.toIntOrNull()
                        }?.value
                        target?.languages?.values?.flatten()?.forEach { it.url?.let(links::add) }
                    }
                }
            }

            "Wiflix" -> {
                json.decodeFromString<MovixWiflixResponse>(response).let { res ->
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

            "Cpasmal" -> {
                json.decodeFromString<CpasmalRes>(fixedResponse).links?.values?.flatten()
                    ?.forEach { it.url?.let(links::add) }
            }

            "Drama" -> {
                json.decodeFromString<MovixDramaResponse>(response).data
                    ?.forEach { it.link?.let(links::add) }
            }

            "Purstream" -> {
                json.decodeFromString<MovixPurstreamResponse>(response).sources?.forEach { source ->
                    source.url?.takeIf { it.isNotBlank() }?.let(links::add)
                }
            }
        }

        return links.distinct().filter { it.isNotBlank() }
    }
}
