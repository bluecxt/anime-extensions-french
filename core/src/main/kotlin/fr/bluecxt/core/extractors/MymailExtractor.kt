package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.OkHttpClient
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy

class MymailExtractor(private val client: OkHttpClient) {
    private companion object {
        const val API = "https://my.mail.ru/+/video/meta/"
    }
    fun videosFromUrl(url: String): List<ExtractedSource> {
        val id = url.trimEnd('/').substringAfterLast("/")
        val apiUrl = API + id

        val response = client.newCall(GET(apiUrl)).execute()
        if (!response.isSuccessful) return emptyList()

        val json = JSONObject(response.body.string())
        val videosArray = json.optJSONArray("videos") ?: return emptyList()

        val results = mutableListOf<ExtractedSource>()

        for (i in 0 until videosArray.length()) {
            val videoObj = videosArray.getJSONObject(i)
            val quality = videoObj.getString("key")
            var videoUrl = videoObj.getString("url")

            if (videoUrl.startsWith("//")) {
                videoUrl = "https:$videoUrl"
            }

            results.add(
                ExtractedSource(
                    url = videoUrl,
                    quality = quality,
                ),
            )
        }
        return results.reversed()
    }
}
