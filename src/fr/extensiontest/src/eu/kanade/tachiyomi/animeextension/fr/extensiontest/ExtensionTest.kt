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
import okhttp3.Request
import okhttp3.Response

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
            supportedServers = supportedServers,
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    override suspend fun getPopularAnime(page: Int): AnimesPage = service.getPopularAnime(page)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = service.getAnimeDetails(anime)

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = service.getEpisodeList(anime)

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = service.getHosterList(episode)

    override suspend fun getVideoList(hoster: Hoster): List<Video> = service.getVideoList(hoster)

    // Dummy implementations for unused methods
    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)
    override fun latestUpdatesRequest(page: Int): Request = Request.Builder().url(defaultBaseUrl).build()
    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)
    override fun popularAnimeRequest(page: Int): Request = Request.Builder().url(defaultBaseUrl).build()
    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()
    override fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = Request.Builder().url(defaultBaseUrl).build()
}
