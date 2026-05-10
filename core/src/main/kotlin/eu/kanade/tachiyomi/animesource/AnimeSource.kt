package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video

interface AnimeSource {
    val id: Long
    val name: String
    suspend fun getAnimeDetails(anime: SAnime): SAnime
    suspend fun getEpisodeList(anime: SAnime): List<SEpisode>
    suspend fun getVideoList(episode: SEpisode): List<Video>

    // AniZen hierarchical seasons
    suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    // Stub for ResolvableAnimeSource if needed
    // suspend fun getRelatedAnimeList(...)
}
