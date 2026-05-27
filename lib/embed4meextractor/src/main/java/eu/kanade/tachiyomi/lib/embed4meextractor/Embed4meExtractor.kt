package eu.kanade.tachiyomi.lib.embed4meextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Embed4meExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val decodedUrl = java.net.URLDecoder.decode(url, "UTF-8")
        val videoId = if (decodedUrl.contains("#")) {
            decodedUrl.substringAfterLast("#").trim()
        } else if (decodedUrl.lowercase().contains("/embed/")) {
            decodedUrl.substringBeforeLast("/").substringAfterLast("/embed/").trim()
        } else {
            decodedUrl.substringAfterLast("/").trim()
        }

        if (videoId.isEmpty()) return emptyList()

        val parsedUrl = url.toHttpUrl()
        val apiUrl = "${parsedUrl.scheme}://${parsedUrl.host}/api/source/$videoId"

        val headers = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
            .add("Origin", "${parsedUrl.scheme}://${parsedUrl.host}")
            .add("Referer", url)
            .build()

        return try {
            val response = client.newCall(POST(apiUrl, headers)).execute()
            val responseBody = response.body.string()
            val dataElement = json.parseToJsonElement(responseBody).jsonObject["data"] ?: return emptyList()
            
            // Check if data is a string (encrypted) or array (already decrypted)
            val jsonArray = if (dataElement is kotlinx.serialization.json.JsonPrimitive && dataElement.isString) {
                val encryptedHex = dataElement.content
                val decryptedJsonStr = decryptAesCbc(encryptedHex, "kiemtienmua911ca", "1234567890oiuytr")
                if (decryptedJsonStr == null) return emptyList()
                json.parseToJsonElement(decryptedJsonStr).jsonArray
            } else {
                dataElement.jsonArray
            }

            val videos = mutableListOf<Video>()
            for (source in jsonArray) {
                val file = source.jsonObject["file"]?.jsonPrimitive?.content ?: continue
                if (file.contains(".m3u8")) {
                    videos.add(Video(videoUrl = file, videoTitle = "${prefix}Embed4me"))
                }
            }
            videos
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decryptAesCbc(hexData: String, keyStr: String, ivStr: String): String? {
        return try {
            val cleanHex = hexData.trim().replace("\"", "")
            val data = hexStringToByteArray(cleanHex)
            
            val keyBytes = keyStr.toByteArray(Charsets.UTF_8)
            val ivBytes = ivStr.toByteArray(Charsets.UTF_8)
            
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivParameterSpec = IvParameterSpec(ivBytes)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
            
            val decryptedBytes = cipher.doFinal(data)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
