package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class DoodExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String): List<ExtractedSource> {
        val response = client.newCall(GET(url)).execute()
        val newUrl = response.request.url.toString()

        val doodHost = newUrl.toHttpUrl()
            .newBuilder("/")
            ?.build()
            ?.toString()
            ?.removeSuffix("/") ?: ""

        val content = response.body.string()
        if (!content.contains("'/pass_md5/")) return emptyList()

        val extractedQuality = Regex("\\d{3,4}p")
            .find(content.substringAfter("<title>").substringBefore("</title>"))
            ?.groupValues
            ?.getOrNull(0)

        val md5 = doodHost + (Regex("/pass_md5/[^']*").find(content)?.value ?: return emptyList())
        val token = md5.substringAfterLast("/")
        val randomString = createHashTable()
        val expiry = System.currentTimeMillis()

        val videoUrlStart = client.newCall(GET(md5, Headers.headersOf("referer", newUrl))).execute().body.string()

        val videoUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"

        return listOf(
            ExtractedSource(
                url = videoUrl,
                quality = extractedQuality,
            ),
        )
    }

    // Method to generate a random string
    private fun createHashTable(length: Int = 10): String {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return buildString {
            repeat(length) {
                append(alphabet.random())
            }
        }
    }
}
