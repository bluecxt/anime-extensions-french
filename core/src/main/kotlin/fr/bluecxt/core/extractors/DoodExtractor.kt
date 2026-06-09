package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class DoodExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String): List<ExtractedSource> {
        // Remplacement des liens d-s.io par le miroir fonctionnel dsvplay.com
        val cleanUrl = url.replace("d-s.io", "dsvplay.com")

        val response = client.newCall(GET(cleanUrl)).execute()
        val newUrl = response.request.url.toString()

        val httpUrl = newUrl.toHttpUrl()
        val doodHost = "${httpUrl.scheme}://${httpUrl.host}"

        val content = response.body.string()
        if (!content.contains("'/pass_md5/")) return emptyList()

        val extractedQuality = Regex("\\d{3,4}p")
            .find(content.substringAfter("<title>").substringBefore("</title>"))
            ?.groupValues
            ?.getOrNull(0)

        val md5Url = doodHost + (Regex("/pass_md5/[^']*").find(content)?.value ?: return emptyList())
        val token = md5Url.substringAfterLast("/")
        val randomString = createHashTable()
        val expiry = System.currentTimeMillis()

        val videoHeaders = Headers.Builder()
            .add("User-Agent", DEFAULT_USER_AGENT)
            .add("Referer", "$doodHost/")
            .build()

        val videoUrlStart = client.newCall(
            GET(md5Url, Headers.headersOf("referer", newUrl)),
        ).execute().body.string()

        val videoUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"

        return listOf(
            ExtractedSource(
                url = videoUrl,
                quality = extractedQuality,
                headers = videoHeaders,
            ),
        )
    }

    private fun createHashTable(length: Int = 10): String {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return buildString {
            repeat(length) {
                append(alphabet.random())
            }
        }
    }
}
