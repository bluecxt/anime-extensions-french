// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.extensiontest

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.EXTENSIONTEST_LOG
import fr.bluecxt.core.Source
import fr.bluecxt.core.extractors.UqloadExtractor
import keiyoushi.utils.parallelCatchingFlatMap
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
        "Upstream", "StreamHide", "StreamVid", "StreamHub", "StreamDav",
    )

    override val baseUrl: String get() = defaultBaseUrl
    override val lang = "fr"
    override val supportsLatest = false

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(
        listOf(
            SAnime.create().apply {
                title = "Test Extracteurs"
                url = "/test-extractors"
                thumbnail_url = "https://github.com/bluecxt/anime-extensions-french/raw/refs/heads/main/assets/repo_logo.svg"
            },
        ),
        false,
    )
// combo 1
// val char1 = "❱"
// val char2 = "•"
//
// combo 2
// val char1 = "-"
// val char2 = "•"
//
// combo 3
// val char1 = "➜"
// val char2 = "•"
//
// combo 4
// val char1 = "✦"
// val char2 = "⫻"

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime.apply {
        description = buildString {
            val char1 = "✦"
            val char2 = "⫻"
            append("Uqload $char1 802p $char2 23.98fps")
            append("\n")
            append("Sendvid $char1 1080p $char2 60fps")
            append("\n")
            append("Sibnet $char1 720p $char2 60fps")
            append("\n")
            append("Vidmoly $char1 1080p")
            append("\n")
            append("Vk $char1 60fps")
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Épisode 1 - Test Hosters LAZY (Délais 3s à la demande)"
            url = "/episode-lazy"
            episode_number = 1f
        },
        SEpisode.create().apply {
            name = "Épisode 2 - Test Hosters NON-LAZY (Délais 3s immédiat)"
            url = "/episode-non-lazy"
            episode_number = 2f
        },
    )

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val isLazy = episode.url == "/episode-lazy"
        return listOf(
            Hoster(
                hosterName = "Serveur Rapide (Sibnet)",
                internalData = "fast",
                lazy = isLazy,
            ),
            Hoster(
                hosterName = "Serveur Lent 1 (DoodStream - 3s delay)",
                internalData = "slow_1",
                lazy = isLazy,
            ),
            Hoster(
                hosterName = "Serveur Lent 2 (Voe - 4s delay)",
                internalData = "slow_2",
                lazy = isLazy,
            ),
            Hoster(
                hosterName = "Serveur Très Lent (Filemoon - 6s delay)",
                internalData = "slow_3",
                lazy = isLazy,
            ),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        Log.d("ExtensionTest", "getVideoList appelé pour : ${hoster.hosterName} (internalData=${hoster.internalData})")

        // Simulation de délais réseau lourds selon le hoster
        when (hoster.internalData) {
            "fast" -> {
                kotlinx.coroutines.delay(300)
                return listOf(Video(videoUrl = "https://example.com/fast.mp4", videoTitle = "Sibnet 720p (300ms)"))
            }

            "slow_1" -> {
                Log.d("ExtensionTest", "Début extraction DoodStream (3s)...")
                kotlinx.coroutines.delay(3000)
                Log.d("ExtensionTest", "Fin extraction DoodStream")
                return listOf(Video(videoUrl = "https://example.com/slow1.mp4", videoTitle = "DoodStream 1080p (3s)"))
            }

            "slow_2" -> {
                Log.d("ExtensionTest", "Début extraction Voe (4s)...")
                kotlinx.coroutines.delay(4000)
                Log.d("ExtensionTest", "Fin extraction Voe")
                return listOf(Video(videoUrl = "https://example.com/slow2.mp4", videoTitle = "Voe HD (4s)"))
            }

            "slow_3" -> {
                Log.d("ExtensionTest", "Début extraction Filemoon (6s)...")
                kotlinx.coroutines.delay(6000)
                Log.d("ExtensionTest", "Fin extraction Filemoon")
                return listOf(Video(videoUrl = "https://example.com/slow3.mp4", videoTitle = "Filemoon 1080p (6s)"))
            }

            else -> {
                kotlinx.coroutines.delay(1000)
                return listOf(Video(videoUrl = "https://example.com/default.mp4", videoTitle = "Défaut 720p"))
            }
        }
    }

    // Dummy implementations for unused methods
    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)
    override fun latestUpdatesRequest(page: Int): Request = Request.Builder().url(defaultBaseUrl).build()
    override fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = Request.Builder().url(defaultBaseUrl).build()
}
