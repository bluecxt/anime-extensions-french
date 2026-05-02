package eu.kanade.tachiyomi.animeextension.fr.southtv

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.Source
import okhttp3.Headers

class SouthTV : Source() {

    override val name = "SouthTV"
    override val baseUrl = "https://southtv.fr"
    private val videoUrlHost = "https://southtv.fr"
    override val lang = "fr"
    override val supportsLatest = false

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"

    private val episodesPerSeason = listOf(13, 18, 17, 17, 14, 17, 15, 14, 14, 14, 14, 14, 14, 14, 14, 14, 10, 10, 10, 10, 10, 10, 10, 4, 6, 6, 5, 5)

    // Movies (Name, Internal ID, TMDB ID)
    private val movies = listOf(
        Triple("Creed", "creed.mp4", 1219926),
        Triple("Panda Verse", "pandav.mp4", 1190012),
        Triple("The end of Obesity", "obes.mp4", 1290938),
        Triple("Streaming War P1", "streamwar1.mp4", 974691),
        Triple("Streaming War P2", "streamwar2.mp4", 993729),
        Triple("Post Covid P1", "southpark/s24e3.mp4", 874299),
        Triple("Post Covid P2", "southpark/s24e4.mp4", 874300),
        Triple("South Park le Film", "Filmsouthpark.mp4", 9473),
    )

    private fun getAnimeList(): List<SAnime> {
        val animeList = mutableListOf<SAnime>()
        // South Park
        animeList.add(
            SAnime.create().apply {
                title = "South Park (VF)"
                url = "south_park_vf"
                thumbnail_url = "https://image.tmdb.org/t/p/original/2PzoiWFm3WymmOPOkWe5DKv7xD8.jpg"
                description = "South Park est une série d'animation américaine pour adultes. (VF)"
                status = SAnime.ONGOING
            },
        )
        // Movies
        movies.forEach { (title, file, _) ->
            animeList.add(
                SAnime.create().apply {
                    this.title = title
                    this.url = "movie_$file"
                    this.status = SAnime.COMPLETED
                },
            )
        }
        return animeList
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(getAnimeList(), false)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val filtered = getAnimeList().filter { it.title.contains(query, ignoreCase = true) }
        return AnimesPage(filtered, false)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (type, id) = getTmdbInfo(anime.url) ?: return anime

        val meta = fetchTmdbMetadataById(id, type) ?: return anime

        anime.title = anime.title
        anime.author = meta.author
        anime.artist = meta.artist
        anime.genre = meta.genre
        anime.status = meta.status
        anime.thumbnail_url = meta.posterUrl ?: anime.thumbnail_url

        anime.description = buildString {
            if (meta.releaseDate != null) append("Date de sortie : ${meta.releaseDate}\n\n")
            append(meta.summary ?: "")
        }

        return anime
    }

    private fun getTmdbInfo(url: String): Pair<String, Int>? = when {
        url == "south_park_vf" -> "tv" to 2190
        url.contains("creed.mp4") -> "movie" to 1219926
        url.contains("pandav.mp4") -> "movie" to 1190012
        url.contains("obes.mp4") -> "movie" to 1290938
        url.contains("streamwar1.mp4") -> "movie" to 974691
        url.contains("streamwar2.mp4") -> "movie" to 993729
        url.contains("s24e3.mp4") -> "movie" to 874299
        url.contains("s24e4.mp4") -> "movie" to 874300
        url.contains("Filmsouthpark.mp4") -> "movie" to 9473
        else -> null
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        if (anime.url.startsWith("south_park")) {
            var episodeCountSoFar = 0
            episodesPerSeason.forEachIndexed { seasonIndex, count ->
                val seasonNum = seasonIndex + 1
                // Use core engine for season data
                val meta = fetchTmdbMetadataById(2190, "tv", seasonNum)

                for (i in 1..count) {
                    val epMeta = meta?.episodeSummaries?.get(i)
                    episodes.add(
                        SEpisode.create().apply {
                            val epTitle = epMeta?.first
                            name = "Episode $i" + (if (epTitle != null) " - $epTitle" else "")
                            url = "south_park#s=$seasonNum&e=$i"
                            episode_number = (episodeCountSoFar + i).toFloat()
                            scanlator = "Season $seasonNum"
                            preview_url = epMeta?.second
                            summary = epMeta?.third
                        },
                    )
                }
                episodeCountSoFar += count
            }
        } else { // movie
            val info = getTmdbInfo(anime.url)
            val meta = info?.let { fetchTmdbMetadataById(it.second, it.first) }

            episodes.add(
                SEpisode.create().apply {
                    name = meta?.episodeSummaries?.get(1)?.first ?: anime.title
                    url = anime.url
                    episode_number = 1f
                    preview_url = meta?.episodeThumbUrl ?: meta?.posterUrl
                    summary = null
                },
            )
        }
        return episodes.reversed()
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterName = "SouthTV", internalData = episode.url))

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val videoList = mutableListOf<Video>()
        val videoHeaders = Headers.Builder().add("User-Agent", userAgent).build()
        val url = hoster.internalData

        if (url.startsWith("south_park")) {
            val parts = url.split("#")[1].split("&")
            val season = parts.find { it.startsWith("s=") }!!.substring(2)
            val episodeNum = parts.find { it.startsWith("e=") }!!.substring(2)
            val videoUrl = "$videoUrlHost/southpark/s${season}e$episodeNum.mp4"

            videoList.add(Video(videoUrl = videoUrl, videoTitle = "(VF) SouthTV - HD", headers = videoHeaders))
        } else { // movie
            val moviePath = url.substringAfter("movie_")
            val videoUrl = if (moviePath.contains("/")) "$videoUrlHost/$moviePath" else "$videoUrlHost/films/$moviePath"
            videoList.add(Video(videoUrl = videoUrl, videoTitle = "(VF) SouthTV - HD", headers = videoHeaders))
        }
        return videoList
    }
}
