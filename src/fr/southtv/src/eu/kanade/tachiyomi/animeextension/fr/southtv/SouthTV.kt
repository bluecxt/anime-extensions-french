package eu.kanade.tachiyomi.animeextension.fr.southtv

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.Source
import okhttp3.Headers
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
    MediaInfo("South Park", "south_park_vf", TMDB_ID_SOUTHPARK, isMovie = false),
    MediaInfo("Creed", "creed.mp4", TMDB_ID_CREED, isMovie = true),
    MediaInfo("Panda Verse", "pandav.mp4", TMDB_ID_PANDAV, isMovie = true),
    MediaInfo("The end of Obesity", "obes.mp4", TMDB_ID_OBES, isMovie = true),
    MediaInfo("Streaming War P1", "streamwar1.mp4", TMDB_ID_STREAMWAR1, isMovie = true),
    MediaInfo("Streaming War P2", "streamwar2.mp4", TMDB_ID_STREAMWAR2, isMovie = true),
    MediaInfo("Post Covid P1", "southpark/s24e3.mp4", TMDB_ID_POSTCOVID1, isMovie = true),
    MediaInfo("Post Covid P2", "southpark/s24e4.mp4", TMDB_ID_POSTCOVID2, isMovie = true),
    MediaInfo("South Park le Film", "Filmsouthpark.mp4", TMDB_ID_SOUTHPARKMOVIE, isMovie = true),
)

class SouthTV : Source() {

    private val log = "southTvDebug"
    private val userAgent = DEFAULT_USER_AGENT

    override val name = "SouthTV"
    override val baseUrl = "https://southtv.fr"
    override val lang = "fr"
    override val supportsLatest = false

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(getAnimeList(), false)

    private suspend fun getAnimeList(): List<SAnime> = mediaList.map { media ->
        val meta = fetchTmdbMetadataById(media.tmdbId, if (media.isMovie) "movie" else "tv")

        SAnime.create().apply {
            title = media.title
            url = if (media.isMovie) "movie_${media.urlSuffix}" else media.urlSuffix
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
        anime.fetch_type = if (anime.url.contains("south_park_vf")) FetchType.Seasons else FetchType.Episodes

        return anime
    }

    // ============================== Episodes ==============================

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = fetchEpisodesPerSeason().mapIndexed { index, media ->
        val seasonIndex = index + 1
        val (type, id) = getTmdbID(anime.url) ?: Pair("", 0)
        val meta = fetchTmdbMetadataById(id, type, seasonIndex)

        SAnime.create().apply {
            title = anime.title + " Saison $seasonIndex"
            season_number = -2.0 // seasonIndex.toDouble()
            url = anime.url + "#s=$seasonIndex"
            thumbnail_url = meta?.posterUrl ?: anime.thumbnail_url
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val seasonNumber = anime.url.substringAfter("#s=").toIntOrNull()
        val episodesPerSeason = fetchEpisodesPerSeason()
        val info = getTmdbID(anime.url)
        val meta = info?.let {
            Log.d(log, "type = ${it.first}")
            fetchTmdbMetadataById(it.second, it.first, if (it.first == "tv" && seasonNumber != null) seasonNumber else 1)
        }
        if (seasonNumber != null) Log.d(log, "Saison $seasonNumber")

        return List(if (seasonNumber == null) 1 else episodesPerSeason.getOrNull(seasonNumber - 1) ?: 0) { episodeIndex ->
            val info = getTmdbID(anime.url)
            val episodeNumber = episodeIndex + 1
            SEpisode.create().apply {
                val episodeData = meta?.episodeSummaries[episodeNumber]
                val (title, thumb, summary) = episodeData ?: Triple(null, null, null)

                this.name = buildString {
                    if (seasonNumber != null && seasonNumber > 1) append("[S$seasonNumber] ")
                    append("Episode $episodeNumber")
                    if (title != null) append(" - $title")
                }
                this.url = "south_park#s=$seasonNumber&e=$episodeNumber"
                this.episode_number = episodeNumber.toFloat()
                this.scanlator = "VF"
                if (thumb != null) this.preview_url = thumb
                if (summary != null) this.summary = summary
            }
        }.reversed()
    }

    private fun fetchEpisodesPerSeason(): List<Int> {
        val request = Request.Builder().url(baseUrl).headers(headers).build()
        val body = client.newCall(request).execute().body.string()
        return Regex("""const\s+episodesPerSeason\s*=\s*\[(.*?)\]""")
            .find(body)?.groupValues?.get(1)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterName = "SouthTV", internalData = episode.url))

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val videoList = mutableListOf<Video>()
        val videoHeaders = Headers.Builder().add("User-Agent", userAgent).build()
        val url = hoster.internalData

        if (url.startsWith("south_park")) {
            val parts = url.split("#")[1].split("&")
            val season = parts.find { it.startsWith("s=") }!!.substring(2)
            val episodeNum = parts.find { it.startsWith("e=") }!!.substring(2)
            val videoUrl = "$baseUrl/southpark/s${season}e$episodeNum.mp4"

            videoList.add(Video(videoUrl = videoUrl, videoTitle = "(VF) SouthTV", headers = videoHeaders))
        } else { // movie
            val moviePath = url.substringAfter("movie_")
            val videoUrl = if (moviePath.contains("/")) "$baseUrl/$moviePath" else "$baseUrl/films/$moviePath"
            videoList.add(Video(videoUrl = videoUrl, videoTitle = "(VF) SouthTV", headers = videoHeaders))
        }
        return videoList
    }

    // ============================ Utils =============================

    private fun getTmdbID(url: String): Pair<String, Int>? {
        val media = mediaList.find { url.contains(it.urlSuffix) } ?: return null

        return (if (media.isMovie) "movie" else "tv") to media.tmdbId
    }
}
