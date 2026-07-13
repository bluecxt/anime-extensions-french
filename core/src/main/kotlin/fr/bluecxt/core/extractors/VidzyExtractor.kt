package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.ExtractionException
import fr.bluecxt.core.SelectorException
import fr.bluecxt.core.VIDZY_LOG
import fr.bluecxt.core.defaultHeaders
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import fr.bluecxt.core.utils.unpacker.autoUnpacker
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class VidzyExtractor(private val client: OkHttpClient) {

    private val resolutionRegex = """(?<=^|[^a-zA-Z0-9])\d{3,4}p(?=$|[^a-zA-Z0-9])""".toRegex(RegexOption.IGNORE_CASE)

    private fun getHeaders(url: String): Headers {
        val parsedUrl = url.toHttpUrl()
        val baseHost = "${parsedUrl.scheme}://${parsedUrl.host}"
        return defaultHeaders(
            referer = "$baseHost/",
            origin = baseHost,
        )
    }

    private fun getDownloadUrl(url: String): String? {
        val parsedUrl = url.toHttpUrlOrNull() ?: return null

        val segments = parsedUrl.pathSegments.map { segment ->
            var clean = if (segment.startsWith("embed-")) segment.removePrefix("embed-") else segment

            if (clean.endsWith(".html")) {
                val nameWithoutExtension = clean.removeSuffix(".html")
                if (!nameWithoutExtension.endsWith("_n") && !nameWithoutExtension.endsWith("_o") && !nameWithoutExtension.endsWith("_d")) {
                    clean = "${nameWithoutExtension}_n.html"
                }
            }
            clean
        }.filter { segment ->
            segment.isNotEmpty() && segment != "d"
        }.toMutableList()

        segments.add(0, "d")

        return parsedUrl.newBuilder()
            .encodedPath("/" + segments.joinToString("/"))
            .build()
            .toString()
    }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val downloadUrl = getDownloadUrl(url) ?: throw ExtractionException("could not parse the url")
        val headers = getHeaders(url)
        val document = client.newCall(GET(downloadUrl, headers)).awaitSuccess().asJsoup()

        val op = document.selectFirst("input[name=op]")?.attr("value")?.takeIf { it.isNotEmpty() } ?: throw SelectorException("could not find op")
        val id = document.selectFirst("input[name=id]")?.attr("value")?.takeIf { it.isNotEmpty() } ?: throw SelectorException("could not find id")
        val mode = document.selectFirst("input[name=mode]")?.attr("value")?.takeIf { it.isNotEmpty() } ?: throw SelectorException("could not find mode")
        val hash = document.selectFirst("input[name=hash]")?.attr("value")?.takeIf { it.isNotEmpty() } ?: throw SelectorException("could not find hash")

        val formBody = FormBody.Builder()
            .add("op", op)
            .add("id", id)
            .add("mode", mode)
            .add("hash", hash)
            .build()

        val downloadDocument = client.newCall(POST(downloadUrl, headers, formBody)).awaitSuccess().asJsoup()

        Log.d(VIDZY_LOG, "post on $downloadUrl, headers = $url as referer and useragent classic")
        Log.d(VIDZY_LOG, "formbody: op = $op, id = $id mode = $mode hash = $hash")

        val downloadLink = downloadDocument.selectFirst("a.main-button")?.attr("abs:href")

        if (downloadLink.isNullOrBlank()) throw SelectorException("could not find the download button")

        val title = downloadDocument.selectFirst("h3")?.text() ?: ""

        val softSubs = downloadDocument.select("a.sub-btn").map { sub ->
            Track(
                url = sub.attr("abs:href"),
                lang = if (title.contains("fr", ignoreCase = true)) "fr" else "",
            )
        }

        Log.d(VIDZY_LOG, "title = $title")

        val quality = resolutionRegex.find(title)?.value

        Log.d(VIDZY_LOG, "quality = $quality")

        return listOf(
            ExtractedSource(
                url = downloadLink,
                quality = quality,
                headers = headers,
                subtitleTracks = softSubs,
            ),
        )
    }
}
