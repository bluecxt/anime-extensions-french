package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

private const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

class GoogleDriveExtractor(private val client: OkHttpClient) {

    private val cookieList = client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl())

    fun videosFromUrl(itemId: String): List<ExtractedSource> {
        val videoList = mutableListOf<ExtractedSource>()

        val initialVideoUrl = "https://drive.usercontent.google.com/download?id=$itemId"

        val docHeaders = Headers.Builder().apply {
            add("User-Agent", DEFAULT_USER_AGENT)
            add("Accept", ACCEPT)
            add("Cookie", cookieList.toStr())
        }.build()

        val docResp = client.newCall(
            GET(initialVideoUrl, docHeaders),
        ).execute()

        if (!docResp.peekBody(15).string().equals("<!DOCTYPE html>", true)) {
            videoList.add(
                ExtractedSource(
                    url = initialVideoUrl,
                    headers = docHeaders,
                ),
            )
        } else {
            val document = docResp.asJsoup()

            val itemSize: String = document.selectFirst("span.uc-name-size")
                ?.let { " ${it.ownText().trim()} " }
                ?: ""

            val finalVideoUrl = initialVideoUrl.toHttpUrl().newBuilder().apply {
                document.select("input[type=hidden]").forEach {
                    setQueryParameter(it.attr("name"), it.attr("value"))
                }
            }.build().toString()

            videoList.add(
                ExtractedSource(
                    url = finalVideoUrl,
                    quality = itemSize,
                    headers = docHeaders,
                ),
            )
        }
        return videoList
    }

    private fun List<Cookie>.toStr(): String = this.joinToString("; ") { "${it.name}=${it.value}" }
}
