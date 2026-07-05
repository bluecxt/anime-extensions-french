package eu.kanade.tachiyomi.animeextension.fr.extensiontest

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.Source
import fr.bluecxt.core.utils.buildFromSource
import okhttp3.Request
import okhttp3.Response
import fr.bluecxt.core.model.Anime as CoreAnime
import fr.bluecxt.core.model.Episode as CoreEpisode
import fr.bluecxt.core.model.Hoster as CoreHoster
import fr.bluecxt.core.model.Video as CoreVideo

class ExtensionTest :
    Source(),
    CommonPreferences {
    override val name = "Extension-Test"
    override val defaultBaseUrl = "https://example.com"
    override val supportedServers = listOf(
        "Embed4me", "Filemoon", "GoogleDrive", "Minochinos", "Sendvid",
        "Sibnet", "Vidmoly", "Vk", "Waveplayer", "Okru", "Doodstream", "Voe",
        "Vidoza", "Uqload", "Lulu", "Streamtape", "SouthTV", "Cda", "Mp4upload",
        "Streamup", "Vidguard", "Lycoris", "Pixeldrain", "Abstream", "Rumble",
        "Abyss", "Buzz", "Earnvid", "Hqq", "Dailymotion",
    )

    override val baseUrl: String get() = defaultBaseUrl
    override val lang = "fr"
    override val supportsLatest = false

    private val service by lazy {
        ExtensionTestService(
            client = client,
            extractVideos = { url ->
                extractVideos(playerUrl = url, allowedServers = supportedServers).map { extSource ->
                    CoreVideo(url = extSource.videoUrl ?: "", title = extSource.videoTitle ?: "Video", headers = extSource.headers)
                }
            },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val coreAnimes = service.getPopularAnime(page)
        val sAnimes = coreAnimes.map { coreAnime ->
            SAnime.create().apply {
                title = coreAnime.title
                url = coreAnime.url
                thumbnail_url = coreAnime.thumbnailUrl
            }
        }
        return AnimesPage(sAnimes, false)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val coreAnime = CoreAnime(title = anime.title, url = anime.url)
        val updatedCore = service.getAnimeDetails(coreAnime)
        return anime.apply {
            description = updatedCore.description
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val coreAnime = CoreAnime(title = anime.title, url = anime.url)
        return service.getEpisodeList(coreAnime).map { coreEpisode ->
            SEpisode.create().apply {
                name = coreEpisode.name
                url = coreEpisode.url
                episode_number = coreEpisode.episodeNumber
            }
        }
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val coreEpisode = CoreEpisode(name = episode.name, url = episode.url, episodeNumber = episode.episode_number)
        return service.getHosterList(coreEpisode).map { coreHoster ->
            Hoster(hosterName = coreHoster.name, internalData = coreHoster.url)
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val coreHoster = CoreHoster(name = hoster.hosterName, url = hoster.internalData)
        val coreVideos = service.getVideoList(coreHoster)
        return coreVideos.map { coreVideo ->
            val extSource = fr.bluecxt.core.model.ExtractedSource(
                url = coreVideo.url,
                quality = coreVideo.title,
                headers = coreVideo.headers,
            )
            extSource.buildFromSource(null, coreVideo.title)
        }
    }

    // Dummy implementations for unused methods
    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)
    override fun latestUpdatesRequest(page: Int): Request = Request.Builder().url(defaultBaseUrl).build()
    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)
    override fun popularAnimeRequest(page: Int): Request = Request.Builder().url(defaultBaseUrl).build()
    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()
    override fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = Request.Builder().url(defaultBaseUrl).build()
}
