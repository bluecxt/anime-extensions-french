package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.OkHttpClient
import org.json.JSONObject

class MymailExtractor(private val client: OkHttpClient) {
    private companion object {
        const val API = "https://my.mail.ru/+/video/meta/"
    }
    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val id = url.trimEnd('/').substringAfterLast("/")
        val apiUrl = API + id

        val response = client.newCall(GET(apiUrl)).awaitSuccess()
        val responseBody = response.body.string()

        val json = JSONObject(responseBody)
        val videosArray = json.optJSONArray("videos") ?: throw Exception("Could not find video data in Mymail response")

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

        if (results.isEmpty()) throw Exception("Mymail: No video sources found")

        return results.reversed()
    }
}
