package eu.kanade.tachiyomi.animeextension.fr.extensiontest

import fr.bluecxt.core.getVideoServer
import fr.bluecxt.core.test.BaseExtensionTest
import okhttp3.OkHttpClient
import fr.bluecxt.core.model.Video as CoreVideo

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
                            CoreVideo(url = extSource.url, title = extSource.quality ?: "Video", headers = extSource.headers)
                        }
                    } catch (e: Exception) {
                        listOf(CoreVideo(url = url, title = "Error ${server.name}: ${e.message}"))
                    }
                } else {
                    emptyList()
                }
            },
            supportsLatest = true,
        ),
    )
