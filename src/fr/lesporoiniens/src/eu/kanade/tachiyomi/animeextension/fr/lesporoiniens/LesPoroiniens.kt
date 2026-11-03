// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.lesporoiniens

import eu.kanade.tachiyomi.animeextension.fr.lesporoiniens.dto.EpisodeData
import eu.kanade.tachiyomi.animeextension.fr.lesporoiniens.dto.Media
import eu.kanade.tachiyomi.animeextension.fr.lesporoiniens.dto.MediaListDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.extractors.GoogleDriveExtractor
import fr.bluecxt.core.preference.CommonPreferences
import fr.bluecxt.core.source.ConfigurableSource
import fr.bluecxt.core.tvdb.fetchTvdbMetadata
import fr.bluecxt.core.utils.megabytes
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import okhttp3.Cache
import okhttp3.Headers
import java.io.File
import java.text.Normalizer
import kotlin.time.Duration.Companion.minutes

private val MAX_CACHE_SIZE = 10.megabytes
private val MAX_CACHE_TIME = 30.minutes

class LesPoroiniens : ConfigurableSource() {

    override val name = "Les Poroïniens"

    override val defaultBaseUrl = "https://lesporoiniens.org"
    override val supportedServers = listOf("Google Drive")
    override val forceShowQualityPreference = false

    override val lang = "fr"
    override val supportsLatest = false

    override val client = super.client.newBuilder()
        .cache(Cache(File(context.cacheDir, baseUrl.hashCode().toUInt().toString(16)), MAX_CACHE_SIZE))
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request())
                .newBuilder()
                .header("Cache-Control", "public, max-age=${MAX_CACHE_TIME.inWholeSeconds}")
                .build()
        }
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val animes = getMedias().mapNotNull { it.toSAnime() }
        return AnimesPage(animes, false)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)

        val mediaList = getMedias()
        val filteredMedia = mediaList.filter { media ->
            media.mediaTitle.contains(query, ignoreCase = true) || media.alternativeTitles.any { it.contains(query, ignoreCase = true) }
        }.mapNotNull { it.toSAnime() }

        return AnimesPage(filteredMedia, false)
    }

    private suspend fun getMedias(): Set<Media> = client.get("$baseUrl/data/config.json").parseAs<MediaListDto>(json).toMediaList(baseUrl, client, json).localSeries

    // --- Détails ---
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val fileName = if (anime.url.endsWith(".json")) {
            anime.url
        } else { // Retrocompatibility
            val slug = anime.url.removePrefix("/").substringBefore("/")
            getMedias().firstOrNull { slugify(it.mediaTitle) == slug || it.alternativeTitles.any { alt -> slugify(alt) == slug } }?.mediaUrl
                ?: "$slug.json"
        }
        val media = client.get("$baseUrl/data/series/$fileName").parseAs<Media>(json)

        return media.toListOfSEpisode { title, season -> fetchTvdbMetadata(title, season = season) }.asReversed()
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val episodeData = episode.url.parseAs<EpisodeData>()
        return listOf(
            Hoster(
                hosterUrl = episodeData.id,
                hosterName = episodeData.type.replaceFirstChar { it.uppercase() },
                internalData = episodeData.type,
            ),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = if (hoster.internalData == "gdrive") {
        GoogleDriveExtractor(client).videosFromUrl(hoster.hosterUrl)
            .map { it.buildFromSource(lang = null, hoster.hosterName) }
    } else {
        emptyList()
    }

    // --- Utils ---
    private fun slugify(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(SLUG_REGEX_1, "")
        .lowercase().replace(SLUG_REGEX_2, "_").replace(SLUG_REGEX_3, "_").trim('_')

    private fun parseStatus(status: String?): Int = when (status?.lowercase()?.trim()) {
        "en cours", "en cou" -> SAnime.ONGOING
        "terminé", "fini" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    companion object {
        private val SLUG_REGEX_1 = Regex("[\\u0300-\\u036f]")
        private val SLUG_REGEX_2 = Regex("[^a-z0-9]")
        private val SLUG_REGEX_3 = Regex("_+")
    }
}
