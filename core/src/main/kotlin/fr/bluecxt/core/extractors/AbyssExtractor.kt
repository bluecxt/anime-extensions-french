package fr.bluecxt.core.extractors

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AbyssExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        var targetUrl = url

        // Normalize host (short.icu and embedplayabyss.top to abysscdn.com)
        if (targetUrl.contains("short.icu") || targetUrl.contains("embedplayabyss.top")) {
            val vidMatch = Regex("""[?/]v=([0-9a-zA-Z_-]+)""").find(targetUrl)
            if (vidMatch != null) {
                targetUrl = "https://abysscdn.com/?v=${vidMatch.groupValues[1]}"
            }
        }

        val referer = targetUrl.toHttpUrl().newBuilder().encodedPath("/").build().toString()
        val headers = Headers.Builder().apply {
            add("User-Agent", DEFAULT_USER_AGENT)
            add("Referer", referer)
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }.build()

        val response = client.newCall(GET(targetUrl, headers)).awaitSuccess()
        val finalUrl = response.request.url.toString()
        val html = response.body.string()

        // Update referer if redirected
        val finalReferer = if (finalUrl != targetUrl) {
            finalUrl.toHttpUrl().newBuilder().encodedPath("/").build().toString()
        } else {
            referer
        }

        val datas = extractDatasPayload(html)
        if (datas != null) {
            val slug = datas.optString("slug", "")
            val md5Id = datas.optString("md5_id", "")
            val userId = datas.optString("user_id", "")
            val mediaObj = datas.opt("media")
            val isDownload = datas.optBoolean("isDownload", false)

            val mediaPayload = when (mediaObj) {
                is JSONObject -> mediaObj
                is String -> decryptMedia(mediaObj, userId, slug, md5Id)
                null -> null
                else -> null
            }

            if (mediaPayload != null) {
                return extractFromMediaPayload(mediaPayload, slug, md5Id, isDownload, finalReferer)
            }
        }

        // Fallback to legacy extraction
        val legacyUrl = legacyExtract(html)
        if (legacyUrl != null) {
            return listOf(
                ExtractedSource(
                    url = legacyUrl,
                    quality = "Video",
                    headers = Headers.Builder().add("Referer", finalReferer).build(),
                ),
            )
        }

        return emptyList()
    }

    private fun extractDatasPayload(html: String): JSONObject? {
        val match = datasRegex.find(html) ?: return null
        val base64Str = match.groupValues[1].trim()
        val rawBytes = try {
            Base64.decode(base64Str, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }

        // Try to parse raw bytes as UTF-8 JSON
        val utf8Str = rawBytes.toString(Charsets.UTF_8)
        try {
            if (utf8Str.trim().startsWith("{")) {
                return JSONObject(utf8Str)
            }
        } catch (_: Exception) {}

        // Fallback parsing (Latin-1 decoding + custom checks)
        val decodedStr = rawBytes.toString(Charsets.ISO_8859_1)
        val payload = JSONObject()

        // Slug
        Regex(""""slug"\s*:\s*"([^"]+)"""").find(decodedStr)?.let {
            payload.put("slug", it.groupValues[1])
        }
        // md5_id
        Regex(""""md5_id"\s*:\s*(\d+)""").find(decodedStr)?.let {
            payload.put("md5_id", it.groupValues[1])
        }
        // user_id
        Regex(""""user_id"\s*:\s*(\d+)""").find(decodedStr)?.let {
            payload.put("user_id", it.groupValues[1])
        }

        // Media block
        val mediaMarker = "\"media\":\""
        val configMarker = "\",\"config\""
        val mIdx = decodedStr.indexOf(mediaMarker)
        val cIdx = decodedStr.indexOf(configMarker)
        if (mIdx >= 0 && cIdx > mIdx) {
            val mediaEscaped = decodedStr.substring(mIdx + mediaMarker.length, cIdx)
            payload.put("media", decodeEscapedBinary(mediaEscaped))
        } else {
            Regex(""""media"\s*:\s*"((?:\\.|[^"\\])*)"""").find(decodedStr)?.let {
                payload.put("media", decodeEscapedBinary(it.groupValues[1]))
            }
        }

        // isDownload
        Regex(""""isDownload"\s*:\s*(true|false)""").find(decodedStr)?.let {
            payload.put("isDownload", it.groupValues[1] == "true")
        }

        return payload
    }

    private fun decodeEscapedBinary(escaped: String): String {
        if (escaped.isEmpty()) return ""
        val out = StringBuilder()
        var i = 0
        val escMap = mapOf(
            'n' to '\n',
            'r' to '\r',
            't' to '\t',
            'b' to '\b',
            'f' to '\u000c',
            '\\' to '\\',
            '"' to '"',
            '/' to '/',
        )
        while (i < escaped.length) {
            val ch = escaped[i]
            if (ch == '\\' && i + 1 < escaped.length) {
                val nxt = escaped[i + 1]
                if (nxt == 'u' && i + 5 < escaped.length) {
                    try {
                        val hex = escaped.substring(i + 2, i + 6)
                        out.append(hex.toInt(16).toChar())
                        i += 6
                        continue
                    } catch (_: Exception) {}
                }
                if (escMap.containsKey(nxt)) {
                    out.append(escMap[nxt])
                    i += 2
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun decryptMedia(encryptedText: String, userId: String, slug: String, md5Id: String): JSONObject? {
        if (encryptedText.isEmpty() || userId.isEmpty() || slug.isEmpty() || md5Id.isEmpty()) return null
        val keySeed = "$userId:$slug:$md5Id"

        val rawBytes = ByteArray(encryptedText.length) { i ->
            (encryptedText[i].code and 0xFF).toByte()
        }

        val result = aesCtrTransform(rawBytes, keySeed) ?: return null
        return try {
            JSONObject(result.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun deriveKey(seed: String): ByteArray {
        var cleaned = seed
        val firstDot = cleaned.indexOf('.')
        if (firstDot != -1) {
            cleaned = cleaned.substring(0, firstDot) + cleaned.substring(firstDot + 1)
        }
        cleaned = cleaned.replace(":", "").replace("-", "")
        val isAllDigits = cleaned.isNotEmpty() && cleaned.all { it.isDigit() }

        val digestSource = if (isAllDigits) {
            ByteArray(seed.length) { i ->
                val ch = seed[i]
                if (ch.isDigit()) {
                    ch.toString().toInt().toByte()
                } else {
                    (ch.code and 0xFF).toByte()
                }
            }
        } else {
            seed.toByteArray(Charsets.UTF_8)
        }

        val md5 = MessageDigest.getInstance("MD5")
        val hash = md5.digest(digestSource)
        val hexString = hash.joinToString("") { String.format("%02x", it) }
        return hexString.toByteArray(Charsets.UTF_8)
    }

    private fun aesCtrTransform(dataBytes: ByteArray, keySeed: String): ByteArray? = try {
        val key = deriveKey(keySeed)
        val iv = key.copyOfRange(0, 16)
        val secretKey = SecretKeySpec(key, "AES")
        val ivParameterSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
        cipher.doFinal(dataBytes)
    } catch (e: Exception) {
        null
    }

    private fun buildSoraToken(pathValue: String, sizeValue: String): String? {
        val transformed = aesCtrTransform(pathValue.toByteArray(Charsets.UTF_8), sizeValue) ?: return null
        val first = Base64.encodeToString(transformed, Base64.NO_WRAP).replace("=", "")
        val second = Base64.encodeToString(first.toByteArray(Charsets.UTF_8), Base64.NO_WRAP).replace("=", "")
        return second
    }

    private suspend fun extractFromMediaPayload(
        mediaPayload: JSONObject,
        slug: String,
        md5Id: String,
        isDownload: Boolean,
        referer: String,
    ): List<ExtractedSource> {
        val videoList = mutableListOf<ExtractedSource>()

        // 1. Try MP4 sources
        val mp4 = mediaPayload.optJSONObject("mp4")
        if (mp4 != null) {
            val sources = mp4.optJSONArray("sources")
            val domains = mp4.optJSONArray("domains")
            if (sources != null) {
                val sourceList = (0 until sources.length())
                    .mapNotNull { sources.optJSONObject(it) }
                    .sortedByDescending { it.optLong("size", 0L) }

                for (src in sourceList) {
                    val label = src.optString("label", "Video")
                    val size = src.optString("size", "")
                    val resId = src.optString("res_id", "")
                    val sub = src.optString("sub", "")

                    // Direct file
                    val direct = src.optString("file", "")
                    if (direct.isNotEmpty()) {
                        videoList.add(
                            ExtractedSource(
                                url = direct.replace("\\/", "/"),
                                quality = label,
                                headers = Headers.Builder().add("Referer", referer).build(),
                            ),
                        )
                        continue
                    }

                    // Direct URL/Path
                    if (!isDownload) {
                        val urlVal = src.optString("url", "")
                        val pathVal = src.optString("path", "")
                        if (urlVal.isNotEmpty() && pathVal.isNotEmpty()) {
                            val combined = "${urlVal.trimEnd('/')}/${pathVal.trimStart('/')}".replace("\\/", "/")
                            videoList.add(
                                ExtractedSource(
                                    url = combined,
                                    quality = label,
                                    headers = Headers.Builder().add("Referer", referer).build(),
                                ),
                            )
                            continue
                        }
                    }

                    // Sora token URL
                    if (size.isNotEmpty() && resId.isNotEmpty() && sub.isNotEmpty() && domains != null) {
                        val domain = (0 until domains.length())
                            .map { domains.optString(it, "") }
                            .firstOrNull { it.isNotEmpty() && it.contains(sub) }

                        if (domain != null) {
                            val pathValue = "/mp4/$md5Id/$resId/$size?v=$slug"
                            val token = buildSoraToken(pathValue, size)
                            if (token != null) {
                                val norm = if (domain.startsWith("http")) domain else "https://$domain"
                                val finalUrl = "${norm.trimEnd('/')}/sora/$size/$token"
                                videoList.add(
                                    ExtractedSource(
                                        url = finalUrl,
                                        quality = label,
                                        headers = Headers.Builder().add("Referer", referer).build(),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Try HLS
        val hls = mediaPayload.optJSONObject("hls")
        if (hls != null) {
            var hlsUrl: String? = null
            val hlsLabel = hls.optString("label", "")

            for (key in listOf("file", "url", "master", "src", "source")) {
                val valStr = hls.optString(key, "")
                if (valStr.isNotEmpty()) {
                    hlsUrl = valStr.replace("\\/", "/")
                    break
                }
            }

            if (hlsUrl != null) {
                try {
                    videoList.addAll(
                        playlistUtils.extractFromHls(
                            playlistUrl = hlsUrl,
                            referer = referer,
                            subtitleList = emptyList(),
                            toStandardQuality = { quality: String -> if (hlsLabel.isNotEmpty()) "Abyss - $hlsLabel ($quality)" else "Abyss ($quality)" },
                        ),
                    )
                } catch (_: Exception) {}
            } else {
                val hlsSources = hls.optJSONArray("sources")
                if (hlsSources != null) {
                    (0 until hlsSources.length()).mapNotNull { hlsSources.optJSONObject(it) }.forEach { hs ->
                        val f = listOf("file", "url", "src").map { hs.optString(it, "") }.firstOrNull { it.isNotEmpty() }
                        if (f != null) {
                            val label = hs.optString("label", "Video")
                            try {
                                videoList.addAll(
                                    playlistUtils.extractFromHls(
                                        playlistUrl = f.replace("\\/", "/"),
                                        referer = referer,
                                        subtitleList = emptyList(),
                                        toStandardQuality = { quality: String -> "Abyss - $label ($quality)" },
                                    ),
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            // HLS fallback by ID
            val hlsId = hls.optString("id", "")
            if (videoList.isEmpty() && hlsId.isNotEmpty()) {
                val fallbackUrl = "https://abysscdn.com/#hls/$hlsId/master.m3u8"
                try {
                    videoList.addAll(
                        playlistUtils.extractFromHls(
                            playlistUrl = fallbackUrl,
                            referer = referer,
                            subtitleList = emptyList(),
                            toStandardQuality = { quality: String -> "Abyss ($quality)" },
                        ),
                    )
                } catch (_: Exception) {}
            }
        }

        return videoList
    }

    private fun legacyExtract(html: String): String? {
        val m = legacyRegex.find(html)
        if (m != null) {
            try {
                val decoded = customDecode(m.groupValues[1])
                val json = JSONObject(decoded)
                val domain = json.optString("domain", "")
                val id = json.optString("id", "")
                if (domain.isNotEmpty() && id.isNotEmpty()) {
                    return "https://${domain.trim('/')}/$id"
                }
            } catch (_: Exception) {}
        }
        val dm = domainRegex.find(html)
        val im = idRegex.find(html)
        if (dm != null && im != null) {
            return "https://${dm.groupValues[1].trim('/')}/${im.groupValues[1]}"
        }
        return null
    }

    private fun customDecode(encoded: String): String {
        val out = java.io.ByteArrayOutputStream()
        encoded.chunked(4).forEach { chunk ->
            val padded = chunk.padEnd(4, '=')
            val c = padded.map { ch ->
                val idx = CHARSET.indexOf(ch)
                if (idx != -1) idx else 64
            }
            out.write((c[0] shl 2) or (c[1] ushr 4))
            if (c[2] != 64) {
                out.write(((c[1] and 15) shl 4) or (c[2] ushr 2))
            }
            if (c[3] != 64) {
                out.write(((c[2] and 3) shl 6) or c[3])
            }
        }
        return out.toString("UTF-8")
    }

    companion object {
        private const val CHARSET = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="
        private val datasRegex = Regex("""(?:const|var)\s+datas\s*=\s*"([^"]+)"""")
        private val legacyRegex = Regex("""[\w\$]+='([A-Za-z0-9+/=RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW]{30,})_'""")
        private val domainRegex = Regex("""['"]domain['"]\s*:\s*['"]([^'"]+)['"]""")
        private val idRegex = Regex("""['"]id['"]\s*:\s*['"]([^'"]+)['"]""")
    }
}
