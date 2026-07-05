package fr.bluecxt.core.model

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.OkHttpClient

interface AnimeExtensionService {
    val client: OkHttpClient
    val baseUrl: String
    val supportsLatest: Boolean
    suspend fun getPopularAnime(page: Int): AnimesPage
    suspend fun getLatestUpdates(page: Int): AnimesPage
    suspend fun getAnimeDetails(anime: SAnime): SAnime
    suspend fun getEpisodeList(anime: SAnime): List<SEpisode>
    suspend fun getHosterList(episode: SEpisode): List<Hoster>
    suspend fun getVideoList(hoster: Hoster): List<Video>
}
