package eu.kanade.tachiyomi.animeextension.fr.southtv

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.Source
import fr.bluecxt.core.TmdbMetadata
import fr.bluecxt.core.fetchTmdbMetadataById
import fr.bluecxt.core.utils.withDefaultHeaders
import okhttp3.Request

private const val TMDB_ID_SOUTHPARK = 2190
private const val TMDB_ID_CREED = 1219926
private const val TMDB_ID_PANDAV = 1190012
private const val TMDB_ID_OBES = 1290938
private const val TMDB_ID_STREAMWAR1 = 974691
private const val TMDB_ID_STREAMWAR2 = 993729
private const val TMDB_ID_POSTCOVID1 = 874299
private const val TMDB_ID_POSTCOVID2 = 874300
private const val TMDB_ID_SOUTHPARKMOVIE = 9473

private data class MediaInfo(
    val title: String,
    val urlSuffix: String,
    val tmdbId: Int,
    val isMovie: Boolean = true,
)

private val mediaList = listOf(
    MediaInfo("South Park", "southpark", TMDB_ID_SOUTHPARK, isMovie = false),
    MediaInfo("Creed", "films/creed.mp4", TMDB_ID_CREED, isMovie = true),
    MediaInfo("Panda Verse", "films/pandav.mp4", TMDB_ID_PANDAV, isMovie = true),
    MediaInfo("The end of Obesity", "films/obes.mp4", TMDB_ID_OBES, isMovie = true),
    MediaInfo("Streaming War P1", "films/streamwar1.mp4", TMDB_ID_STREAMWAR1, isMovie = true),
    MediaInfo("Streaming War P2", "films/streamwar2.mp4", TMDB_ID_STREAMWAR2, isMovie = true),
    MediaInfo("Post Covid P1", "southpark/s24e3.mp4", TMDB_ID_POSTCOVID1, isMovie = true),
    MediaInfo("Post Covid P2", "southpark/s24e4.mp4", TMDB_ID_POSTCOVID2, isMovie = true),
    MediaInfo("South Park le Film", "films/Filmsouthpark.mp4", TMDB_ID_SOUTHPARKMOVIE, isMovie = true),
)

class SouthTV :
    Source(),
    CommonPreferences {

    override val name = "SouthTV"
    override val defaultBaseUrl = "https://southtv.fr"
    override val supportedServers = listOf("SouthTV")
    override val forceShowQualityPreference = false
    override val forceShowVoicesPreference = false

    override val lang = "fr"
    override val supportsLatest = false

// ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(getAnimeList(), false)

    private suspend fun getAnimeList(): List<SAnime> = mediaList.map { media ->
        val meta = fetchTmdbMetadataById(media.tmdbId, if (media.isMovie) "movie" else "tv")

        SAnime.create().apply {
            title = media.title
            url = media.urlSuffix
            status = if (media.isMovie) SAnime.COMPLETED else SAnime.ONGOING
            thumbnail_url = meta?.posterUrl
        }
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val filtered = getAnimeList().filter { it.title.contains(query, ignoreCase = true) }
        return AnimesPage(filtered, false)
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (type, id) = getTmdbID(anime.url) ?: return anime
        val seasonNumber = anime.url.substringAfter("#s=").toIntOrNull()
        val meta = fetchTmdbMetadataById(id, type, if (seasonNumber != null) seasonNumber else 1) ?: return anime

        if (seasonNumber != null) {
            anime.title = "${anime.title} Saison $seasonNumber"
        }

        anime.author = meta.author
        anime.artist = meta.artist
        anime.genre = meta.genre
        anime.status = meta.status
        anime.thumbnail_url = meta.posterUrl ?: anime.thumbnail_url

        anime.description = buildString {
            if (meta.releaseDate != null) append("Date de sortie : ${meta.releaseDate}\n\n")
            append(meta.summary ?: "")
        }
        anime.fetch_type = if (type == "tv" && anime.url == "southpark") FetchType.Seasons else FetchType.Episodes

        return anime
    }

    // ============================== Episodes ==============================

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = fetchEpisodesPerSeason().mapIndexed { index, media ->
        val seasonIndex = index + 1
        val (type, id) = getTmdbID(anime.url) ?: Pair("", 0)
        val meta = fetchTmdbMetadataById(id, type, seasonIndex)

        SAnime.create().apply {
            title = anime.title + " Saison $seasonIndex"
            season_number = HUB_SEASON_NUMBER // seasonIndex.toDouble()
            url = anime.url + "#s=$seasonIndex"
            thumbnail_url = meta?.posterUrl ?: anime.thumbnail_url
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val seasonNumber = anime.url.substringAfter("#s=").toIntOrNull()
        val episodesPerSeason = fetchEpisodesPerSeason()
        val info = getTmdbID(anime.url)

        val tmdbSeason = if (info?.first == "tv") seasonNumber ?: 1 else 1
        val meta = info?.let { fetchTmdbMetadataById(it.second, it.first, tmdbSeason) }

        val count = if (seasonNumber != null) {
            episodesPerSeason.getOrNull(seasonNumber - 1) ?: 0
        } else {
            1
        }

        return List(count) { index ->
            createEpisode(anime.url, seasonNumber ?: 1, index + 1, meta)
        }.asReversed()
    }

    private fun createEpisode(animeUrl: String, seasonNumber: Int, episodeNumber: Int, meta: TmdbMetadata?): SEpisode {
        val (title, thumb, summary) = meta?.episodeSummaries?.get(episodeNumber) ?: Triple(null, null, null)

        return SEpisode.create().apply {
            episode_number = episodeNumber.toFloat()
            name = buildEpisodeName(seasonNumber, episodeNumber, title)
            url = buildEpisodeUrl(animeUrl, seasonNumber, episodeNumber)
            preview_url = thumb
            this.summary = summary
        }
    }

    private fun buildEpisodeName(seasonNumber: Int, episodeNumber: Int, title: String?): String = buildString {
        if (seasonNumber > 1) append("[S$seasonNumber]")
        append("Episode $episodeNumber")
        if (title != null) append(" - $title")
    }

    private fun buildEpisodeUrl(animeUrl: String, seasonNumber: Int, episodeNumber: Int): String = buildString {
        val url = animeUrl.substringBefore("#")
        if (url.contains("/")) {
            append(url)
        } else {
            append(url)
            append("/")
            append("s$seasonNumber")
            append("e$episodeNumber")
            append(".mp4")
        }
    }

    private suspend fun fetchEpisodesPerSeason(): List<Int> {
        val request = Request.Builder().url(baseUrl).headers(headers).build()
        val body = client.newCall(request).awaitSuccess().body.string()
        return Regex("""const\s+episodesPerSeason\s*=\s*\[(.*?)\]""")
            .find(body)?.groupValues?.get(1)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterName = "SouthTV", internalData = episode.url))

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.internalData
        val videoUrl = "$baseUrl/$url"
        return listOf(Video(videoUrl = videoUrl, videoTitle = "SouthTV").withDefaultHeaders(baseUrl))
    }

    // ============================ Utils =============================

    private fun getTmdbID(url: String): Pair<String, Int>? {
        val media = mediaList.find { url.contains(it.urlSuffix) } ?: return null

        return (if (media.isMovie) "movie" else "tv") to media.tmdbId
    }
}
