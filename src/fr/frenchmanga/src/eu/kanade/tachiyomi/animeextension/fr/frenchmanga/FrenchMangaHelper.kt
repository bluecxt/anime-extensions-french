package eu.kanade.tachiyomi.animeextension.fr.frenchmanga

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.regex.Pattern

class FrenchMangaExtractor(private val client: OkHttpClient, private val siteUrl: String) {

    private val defaultua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        val videos = mutableListOf<Video>()
        val playerUri = try {
            url.toHttpUrl()
        } catch (e: Exception) {
            return emptyList()
        }
        val playerOrigin = "https://${playerUri.host}"

        val headers = Headers.Builder()
            .add("User-Agent", defaultua)
            .add("Referer", "$siteUrl/")
            .build()

        val videoHeaders = Headers.Builder()
            .add("User-Agent", defaultua)
            .add("Referer", "$siteUrl/")
            .add("Origin", playerOrigin)
            .build()

        try {
            val response = client.newCall(GET(url, headers)).execute()
            if (!response.isSuccessful) return emptyList()

            // Vidzy deep hunt: Simulate the "Download" button to get the real MP4
            if (url.contains("vidzy")) {
                val videoId = url.substringAfter("embed-").substringBefore(".html")
                    .substringAfter("/e/").substringAfter("/v/")

                listOf("${videoId}_n.html", "${videoId}_o.html", "$videoId.html").forEach { path ->
                    val dlUrl = "https://vidzy.org/d/$path"
                    try {
                        val dlHtml = client.newCall(GET(dlUrl, headers)).execute().use { it.body.string() }

                        val op = findTag(dlHtml, "op")
                        val id = findTag(dlHtml, "id")
                        val mode = findTag(dlHtml, "mode")
                        val hash = findTag(dlHtml, "hash")

                        if (op != null && id != null && hash != null) {
                            val formBody = okhttp3.FormBody.Builder()
                                .add("op", op).add("id", id).add("mode", mode ?: "").add("hash", hash)
                                .build()

                            val postHeaders = headers.newBuilder().add("Referer", dlUrl).build()
                            client.newCall(Request.Builder().url(dlUrl).post(formBody).headers(postHeaders).build()).execute().use { postRes ->
                                if (postRes.isSuccessful) {
                                    val resultHtml = postRes.body.string()
                                    val directLinkMatcher = Pattern.compile("""<a[^>]+href="([^"]+)"[^>]+class="main-button"""").matcher(resultHtml)
                                    if (directLinkMatcher.find()) {
                                        val directUrl = directLinkMatcher.group(1).replace("\\/", "/")
                                        val quality = if (directUrl.contains("1080")) "1080p" else "720p"
                                        videos.add(Video(directUrl, "$prefix $quality", directUrl, videoHeaders))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }

            // Fallback for other players or if Vidzy direct fails
            if (videos.isEmpty()) {
                val html = response.use { it.body.string() }
                extractAllVideoUrls(html).forEach { videoUrl ->
                    if (!isSuspicious(videoUrl) && videoUrl.startsWith("http")) {
                        val quality = if (videoUrl.contains("1080")) "1080p" else "720p"
                        videos.add(Video(videoUrl, "$prefix $quality", videoUrl, videoHeaders))
                    }
                }
            }
        } catch (e: Exception) {}

        return videos.distinctBy { it.videoUrl }
    }

    private fun findTag(html: String, name: String): String? {
        val matcher = Pattern.compile("""name="$name"\s+value="([^"]+)"""").matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun isSuspicious(url: String): Boolean {
        val blackList = listOf("sex", "porn", "adult", "hentai", "streamhide", "shide", "google", "histats")
        return blackList.any { url.lowercase().contains(it) }
    }

    private fun extractAllVideoUrls(html: String): List<String> {
        val urls = mutableListOf<String>()
        val patterns = listOf(
            Pattern.compile("""file\s*:\s*"([^"]+)""""),
            Pattern.compile("""src\s*:\s*"([^"]+)""""),
            Pattern.compile("""(https?://[^\s"]+\.(?:mp4|mkv|m3u8|ts)[^\s"]*)"""),
        )

        fun find(text: String) {
            patterns.forEach { pattern ->
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    val match = if (matcher.groupCount() >= 1) matcher.group(1) else matcher.group(0)
                    if (match != null) {
                        urls.add(match.replace("\\/", "/").trimEnd('.'))
                    }
                }
            }
        }

        find(html)
        if (html.contains("eval(function(p,a,c,k,e")) {
            JavaScriptUnpacker.unpack(html)?.let { find(it) }
        }
        return urls.distinct()
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
