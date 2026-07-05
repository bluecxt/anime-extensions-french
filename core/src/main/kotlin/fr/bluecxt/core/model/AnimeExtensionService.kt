package fr.bluecxt.core.model

interface AnimeExtensionService {
    val supportsLatest: Boolean
    suspend fun getPopularAnime(page: Int): List<Anime>
    suspend fun getLatestUpdates(page: Int): List<Anime>
    suspend fun getAnimeDetails(anime: Anime): Anime
    suspend fun getEpisodeList(anime: Anime): List<Episode>
    suspend fun getHosterList(episode: Episode): List<Hoster>
    suspend fun getVideoList(hoster: Hoster): List<Video>
}
