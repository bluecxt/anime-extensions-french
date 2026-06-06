package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.Headers
import okhttp3.OkHttpClient

class VkExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val documentHeaders by lazy {
        headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .build()
    }

    private val videoHeaders by lazy {
        headers.newBuilder()
            .add("Accept", "*/*")
            .add("Origin", VK_URL)
            .add("Referer", "$VK_URL/")
            .build()
    }

    fun videosFromUrl(url: String): List<ExtractedSource> {
        val data = client.newCall(GET(url, documentHeaders)).execute().body.string()

        return REGEX_VIDEO.findAll(data).map {
            val quality = it.groupValues[1]
            val videoUrl = it.groupValues[2].replace("\\/", "/")
            ExtractedSource(
                url = videoUrl,
                headers = videoHeaders,
                quality = quality,
            )
        }.toList()
    }

    companion object {
        private const val VK_URL = "https://vk.com"
        private val REGEX_VIDEO = """"url(\d+)":"(.*?)"""".toRegex()
    }
}
