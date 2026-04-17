package eu.kanade.tachiyomi.animeextension.fr.southtv

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

class SouthTV : AnimeHttpSource() {

    override val name = "SouthTV"
    override val baseUrl = "https://southtv.fr"
    private val videoUrlHost = "https://southtv.fr"
    override val lang = "fr"
    override val supportsLatest = false

    private val userAgent = " Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"

    private val episodesPerSeason = listOf(13, 18, 17, 17, 14, 17, 15, 14, 14, 14, 14, 14, 14, 14, 14, 14, 10, 10, 10, 10, 10, 10, 10, 4, 6, 6, 5, 5)
    private val movies = listOf(
        Triple("Creed", "creed.mp4", "https://tse4.mm.bing.net/th/id/OIP.cUq62US5QYmJx7ahQUT6vQHaEK?rs=1&pid=ImgDetMain&o=7&rm=3"),
        Triple("Panda Verse", "pandav.mp4", "https://tse3.mm.bing.net/th/id/OIP.6KFYYuFK79wOcDl71177owHaD4?rs=1&pid=ImgDetMain&o=7&rm=3"),
        Triple("The end of Obesity", "obes.mp4", "https://thumbnails.cbsig.net/CBS_Production_Entertainment_VMS/2024/05/01/2333651011699/SPTEO_US_2024_SA_16x9_1920x1080_NB_2695584_1920x1080.jpg"),
        Triple("Streaming War P1", "streamwar1.mp4", "https://m.media-amazon.com/images/S/pv-target-images/b5a1bd01e3984eec29a70506b160d7060895b7845f40c0e13b44f5a903815496._UR1920,1080_.jpg"),
        Triple("Streaming War P2", "streamwar2.mp4", "https://thumbnails.cbsig.net/CBS_Production_Entertainment_VMS/2022/06/22/2045697603902/SPTSW2_SAlone_16_9_1920x1080_NB_1476556_1920x1080.jpg"),
        Triple("Post Covid P1", "southpark/s24e3.mp4", "https://m.media-amazon.com/images/S/pv-target-images/c32dc76af0a3da928ce124c1e22d23f203ee5ddf6743186f058344bc9414931f.jpg"),
        Triple("Post Covid P2", "southpark/s24e4.mp4", "https://i.ytimg.com/vi/M19gImHO754/maxresdefault.jpg"),
        Triple("South Park le Film", "Filmsouthpark.mp4", "https://thumbnails.cbsig.net/CBS_Production_Entertainment_VMS/2021/11/10/1972863555958/SPBLU_SAlone_16_9_1920x1080_1040472_1920x1080.jpg"),
    )

    private fun getAnimeList(): List<SAnime> {
        val animeList = mutableListOf<SAnime>()
        // South Park
        animeList.add(
            SAnime.create().apply {
                title = "South Park (VF)"
                url = "south_park_vf"
                thumbnail_url = "https://image.tmdb.org/t/p/original/2PzoiWFm3WymmOPOkWe5DKv7xD8.jpg"
                description = "South Park est une série d\'animation américaine pour adultes. (VF)"
                status = SAnime.ONGOING
            },
        )
        // Movies
        movies.forEach { (title, file, thumb) ->
            animeList.add(
                SAnime.create().apply {
                    this.title = title
                    this.url = "movie_$file"
                    this.thumbnail_url = thumb
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
        val tmdbPath = getTmdbPath(anime.url)

        if (tmdbPath != null) {
            val url = "https://api.themoviedb.org/3/$tmdbPath?api_key=24621da8ae19dce721e59eff2ab479bb&language=fr-FR&append_to_response=credits"
            try {
                client.newCall(GET(url, Headers.Builder().build())).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body!!.string())

                        // Description (Overview)
                        val overview = json.optString("overview")
                        val releaseDate = json.optString("release_date").ifBlank { json.optString("first_air_date") }

                        anime.description = buildString {
                            if (releaseDate.isNotBlank()) append("Date de sortie : $releaseDate\n\n")
                            append(overview)
                        }

                        // Auteurs (Créateurs, Réalisateurs, Scénaristes)
                        val authors = mutableSetOf<String>()
                        json.optJSONArray("created_by")?.let { creators ->
                            for (i in 0 until creators.length()) {
                                authors.add(creators.getJSONObject(i).getString("name"))
                            }
                        }
                        json.optJSONObject("credits")?.optJSONArray("crew")?.let { crew ->
                            for (i in 0 until crew.length()) {
                                val member = crew.getJSONObject(i)
                                val job = member.optString("job")
                                if (job == "Director" || job == "Writer" || job == "Screenplay") {
                                    authors.add(member.getString("name"))
                                }
                            }
                        }
                        anime.author = authors.joinToString(", ")

                        // Genres
                        anime.genre = json.optJSONArray("genres")?.let { genres ->
                            (0 until genres.length()).joinToString(", ") { i ->
                                genres.getJSONObject(i).getString("name")
                            }
                        }

                        // Affiche (Poster)
                        val posterPath = json.optString("poster_path")
                        if (posterPath.isNotBlank()) {
                            anime.thumbnail_url = "https://image.tmdb.org/t/p/original$posterPath"
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        return anime
    }

    private fun getTmdbPath(url: String): String? = when {
        url == "south_park_vf" -> "tv/2190"
        url.contains("creed.mp4") -> "movie/1219926"
        url.contains("pandav.mp4") -> "movie/1190012"
        url.contains("obes.mp4") -> "movie/1290938"
        url.contains("streamwar1.mp4") -> "movie/974691"
        url.contains("streamwar2.mp4") -> "movie/993729"
        url.contains("s24e3.mp4") -> "movie/874299"
        url.contains("s24e4.mp4") -> "movie/874300"
        url.contains("Filmsouthpark.mp4") -> "movie/9473"
        else -> null
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        if (anime.url.startsWith("south_park")) {
            var episodeCountSoFar = 0
            episodesPerSeason.forEachIndexed { seasonIndex, count ->
                val seasonNum = seasonIndex + 1
                val namesMap = fetchSeasonNames(seasonNum)
                for (i in 1..count) {
                    val tmdbName = namesMap[i]
                    episodes.add(
                        SEpisode.create().apply {
                            name = "Season $seasonNum Episode $i" + (tmdbName?.let { " - $it" } ?: "")
                            url = "south_park#s=$seasonNum&e=$i"
                            episode_number = (episodeCountSoFar + i).toFloat()
                            scanlator = "Season $seasonNum"
                        },
                    )
                }
                episodeCountSoFar += count
            }
        } else { // movie
            val tmdbPath = getTmdbPath(anime.url)
            var movieTitle = anime.title
            if (tmdbPath != null) {
                try {
                    val url = "https://api.themoviedb.org/3/$tmdbPath?api_key=24621da8ae19dce721e59eff2ab479bb&language=fr-FR"
                    client.newCall(GET(url, Headers.Builder().build())).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body!!.string())
                            movieTitle = json.optString("title").ifBlank { json.optString("name") }.ifBlank { movieTitle }
                        }
                    }
                } catch (e: Exception) {}
            }
            episodes.add(
                SEpisode.create().apply {
                    name = movieTitle
                    url = anime.url
                    episode_number = 1f
                },
            )
        }
        return episodes.reversed()
    }

    private fun fetchSeasonNames(seasonNumber: Int): Map<Int, String> {
        val url = "https://api.themoviedb.org/3/tv/2190/season/$seasonNumber?api_key=24621da8ae19dce721e59eff2ab479bb&language=fr-FR"
        return try {
            client.newCall(GET(url, Headers.Builder().build())).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                val episodesJson = json.getJSONArray("episodes")
                val map = mutableMapOf<Int, String>()
                for (i in 0 until episodesJson.length()) {
                    val ep = episodesJson.getJSONObject(i)
                    map[ep.getInt("episode_number")] = ep.getString("name")
                }
                map
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = mutableListOf<Video>()
        val videoHeaders = Headers.Builder().add("User-Agent", userAgent).build()

        if (episode.url.startsWith("south_park")) {
            val parts = episode.url.split("#")[1].split("&")
            val season = parts.find { it.startsWith("s=") }!!.substring(2)
            val episodeNum = parts.find { it.startsWith("e=") }!!.substring(2)
            val videoUrl = "$videoUrlHost/southpark/s${season}e$episodeNum.mp4"

            videoList.add(Video(videoUrl, "(VF) SouthTV - HD", videoUrl, headers = videoHeaders))
        } else { // movie
            val moviePath = episode.url.substringAfter("movie_")
            val videoUrl = if (moviePath.contains("/")) "$videoUrlHost/$moviePath" else "$videoUrlHost/films/$moviePath"
            videoList.add(Video(videoUrl, "(VF) SouthTV - HD", videoUrl, headers = videoHeaders))
        }
        return videoList
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()
}
