package eu.kanade.tachiyomi.animeextension.fr.extensiontest

import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.getVideoServer
import fr.bluecxt.core.test.BaseExtensionTest
import okhttp3.OkHttpClient
import fr.bluecxt.core.utils.buildFromSource as buildVideoFromSource

class ExtensionTestTest :
    BaseExtensionTest(
        service = ExtensionTestService(
            client = OkHttpClient(),
            extractVideos = { url ->
                val supportedServers = listOf(
                    "Embed4me", "Filemoon", "GoogleDrive", "Minochinos", "Sendvid",
                    "Sibnet", "Vidmoly", "Vk", "Waveplayer", "Okru", "Doodstream", "Voe",
                    "Vidoza", "Uqload", "Lulu", "Streamtape", "SouthTV", "Cda", "Mp4upload",
                    "Streamup", "Vidguard", "Lycoris", "Pixeldrain", "Abstream", "Rumble",
                    "Abyss", "Buzz", "Earnvid", "Hqq", "Dailymotion",
                )
                val client = okhttp3.OkHttpClient()
                val headers = okhttp3.Headers.Builder().build()
                val servers = supportedServers.mapNotNull { getVideoServer(client, headers, it) }
                val server = servers.find { s -> s.matches(url) }
                if (server != null) {
                    try {
                        server.extractor(url).map { extSource ->
                            extSource.buildVideoFromSource(null, server.name)
                        }
                    } catch (e: Exception) {
                        listOf(Video(videoUrl = url, videoTitle = "Error ${server.name}: ${e.message}"))
                    }
                } else {
                    emptyList()
                }
            },
            supportsLatest = true,
        ),
    )
