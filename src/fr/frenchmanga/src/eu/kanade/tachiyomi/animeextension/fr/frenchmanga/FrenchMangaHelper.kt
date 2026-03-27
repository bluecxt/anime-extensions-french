package eu.kanade.tachiyomi.animeextension.fr.frenchmanga

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.regex.Pattern

class FrenchMangaExtractor(private val client: OkHttpClient) {

    private val defaultua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        val videos = mutableListOf<Video>()

        val uri = url.toHttpUrl()
        val referer = "https://${uri.host}/"

        val headers = Headers.Builder()
            .add("Referer", url)
            .add("Origin", referer.removeSuffix("/"))
            .add("User-Agent", defaultua)
            .add("Accept", "*/*")
            .build()

        try {
            val response = client.newCall(GET(url, headers)).execute()
            if (response.code == 403) return emptyList()

            val html = response.use { it.body.string() }
            val videoUrl = extractVideoUrl(html) ?: return emptyList()

            // SECURITY FILTER: Block suspicious content
            if (isSuspicious(videoUrl)) return emptyList()

            val fixedUrl = fixVideoLink(videoUrl)
            val resolution = getResolution(fixedUrl, headers)

            videos.add(Video(fixedUrl, "$prefix - $resolution", fixedUrl, headers))
        } catch (e: Exception) {}

        return videos
    }

    private fun isSuspicious(url: String): Boolean {
        val blackList = listOf("sex", "porn", "adult", "hentai", "streamhide", "shide")
        return blackList.any { url.lowercase().contains(it) }
    }

    private fun extractVideoUrl(html: String): String? {
        // Try multiple methods to find the video file
        return when {
            html.contains("eval(function(p,a,c,k,e") -> {
                val unpacked = JavaScriptUnpacker.unpack(html) ?: return null
                findInText(unpacked)
            }

            else -> findInText(html)
        }
    }

    private fun findInText(text: String): String? = Pattern.compile("sources:\\[\\{file:\"([^\"]+)\"")
        .matcher(text)
        .takeIf { it.find() }
        ?.group(1)
        ?: Pattern.compile("file:\"(https?://[^\"]+)\"")
            .matcher(text)
            .takeIf { it.find() }
            ?.group(1)
        ?: Pattern.compile("https?://[^\"]+\\.m3u8")
            .matcher(text)
            .takeIf { it.find() }
            ?.group(0)

    private fun fixVideoLink(link: String): String = try {
        val uri = link.toHttpUrl()
        val builder = uri.newBuilder()
        if (uri.queryParameter("i").isNullOrEmpty()) builder.setQueryParameter("i", "0.4")
        if (uri.queryParameter("sp").isNullOrEmpty()) builder.setQueryParameter("sp", "0")
        builder.build().toString()
    } catch (e: Exception) {
        link
    }

    private fun getResolution(videoUrl: String, headers: Headers): String {
        if (videoUrl.contains(".mp4")) return "720p"
        return try {
            val content = client.newCall(GET(videoUrl, headers)).execute()
                .use { it.body.string() }

            Pattern.compile("RESOLUTION=\\d+x(\\d+)")
                .matcher(content)
                .takeIf { it.find() }
                ?.group(1)
                ?.let { "${it}p" }
                ?: "HD"
        } catch (e: Exception) {
            "HD"
        }
    }
}

object JavaScriptUnpacker {
    private val UNPACK_REGEX by lazy {
        Regex(
            """\}\('(.*)', *(\d+), *(\d+), *'(.*?)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
    fun unpack(encodedJs: String): String? {
        val match = UNPACK_REGEX.find(encodedJs) ?: return null
        val (payload, radixStr, countStr, symtabStr) = match.destructured

        val radix = radixStr.toIntOrNull() ?: return null
        val count = countStr.toIntOrNull() ?: return null
        val symtab = symtabStr.split('|')

        if (symtab.size != count) return null

        val baseDict = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            .take(radix)
            .withIndex()
            .associate { it.value to it.index }

        return try {
            Regex("""\b\w+\b""").replace(payload) { mr ->
                symtab.getOrNull(unbase(mr.value, radix, baseDict)) ?: mr.value
            }.replace("\\", "")
        } catch (e: Exception) {
            null
        }
    }
    private fun unbase(value: String, radix: Int, dict: Map<Char, Int>): Int {
        var result = 0
        var multiplier = 1

        for (char in value.reversed()) {
            result += dict[char]?.times(multiplier) ?: 0
            multiplier *= radix
        }
        return result
    }
}
