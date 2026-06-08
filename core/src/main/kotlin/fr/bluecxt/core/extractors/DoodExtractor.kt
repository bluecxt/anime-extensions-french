package fr.bluecxt.core.extractors

import fr.bluecxt.core.model.ExtractedSource
import okhttp3.HttpUrl.Companion.toHttpUrl


class DoodExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String): List<ExtractedSource> {
        val response = client.newCall(GET(url)).execute()
        val newUrl = if (redirect) response.request.url.toString() else url

        val doodHost = newUrl.toHttpUrl()
            .newBuilder("/")
            .build()
            .toString()
            .removeSuffix("/")

        
    }

}