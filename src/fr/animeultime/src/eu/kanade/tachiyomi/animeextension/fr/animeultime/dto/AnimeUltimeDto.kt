package eu.kanade.tachiyomi.animeextension.fr.animeultime.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.Charset

enum class Categories {
    Anime,
    Drama,
    OST,
    Tokusatsu,
}

data class SearchResponseItem(
    val id: Int,
    val title: String,
    val img_url: String,
    val url: String,
    val searchType: Categories,
)

@Serializable
data class SearchResponse(
    val id: Int,
    val title: String,
    val img_url: String,
    val url: String,
)

@Serializable
data class UrlContent(
    val id: Int,
    val url: String,
    val searchType: Categories,
)

@Serializable
data class PlayList(
    val playlist: List<Episode>,
)

@Serializable
data class Episode(
    val id: Int,
    val title: String,
    val duration: String,
    val fansub: Int,
    val quality: String,
    val image: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = VideoPlayerResponseSerializer::class)
@KeepGeneratedSerializer
data class VideoPlayerResponse(
    val title: String,
    val quality: String,
    val videoData: VideoFormats,
    val fansub_link: String,
)

@Serializable
data class VideoFormats(
    val mp4: Mp4Data? = null,
)

@Serializable
data class Mp4Data(
    val url: String,
    val mime: String,
    val codecs: String,
)

@OptIn(ExperimentalSerializationApi::class)
object VideoPlayerResponseSerializer : JsonTransformingSerializer<VideoPlayerResponse>(VideoPlayerResponse.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val root = element.jsonObject
        val currentQuality = root["quality"]?.jsonPrimitive?.content ?: "1080p"
        val dynamicVideoNode = root[currentQuality] ?: JsonObject(emptyMap())

        return buildJsonObject {
            root.forEach { key, value ->
                put(key, value)
            }
            put("videoData", dynamicVideoNode)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = AlbumResponseSerializer::class)
@KeepGeneratedSerializer
data class AlbumResponse(
    val title: String,
    val files: List<Track>,
)

@Serializable
data class Track(
    val music_title: String,
    val serie_title: String,
    val duration: String,
    val url: String,
    val image: String,
)

@OptIn(ExperimentalSerializationApi::class)
object AlbumResponseSerializer : JsonTransformingSerializer<AlbumResponse>(AlbumResponse.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val root = element.jsonObject
        val innerObject = root.values.firstOrNull()
        return innerObject ?: JsonObject(emptyMap())
    }
}

// ============================ JSON Encoding Fix Helpers ============================

fun JsonElement.fixEncoding(): JsonElement = when (this) {
    is JsonObject -> JsonObject(this.mapValues { it.value.fixEncoding() })
    is JsonArray -> JsonArray(this.map { it.fixEncoding() })
    is JsonPrimitive -> if (this.isString) JsonPrimitive(this.content.fixEncoding()) else this
}

fun String.fixEncoding(): String {
    val bytes = ByteArray(this.length)
    for (i in this.indices) {
        val c = this[i]
        val b = when (c) {
            '\u20AC' -> 0x80.toByte()
            '\u201A' -> 0x82.toByte()
            '\u0192' -> 0x83.toByte()
            '\u201E' -> 0x84.toByte()
            '\u2026' -> 0x85.toByte()
            '\u2020' -> 0x86.toByte()
            '\u2021' -> 0x87.toByte()
            '\u02C6' -> 0x88.toByte()
            '\u2030' -> 0x89.toByte()
            '\u0160' -> 0x8A.toByte()
            '\u2039' -> 0x8B.toByte()
            '\u0152' -> 0x8C.toByte()
            '\u017D' -> 0x8E.toByte()
            '\u2018' -> 0x91.toByte()
            '\u2019' -> 0x92.toByte()
            '\u201C' -> 0x93.toByte()
            '\u201D' -> 0x94.toByte()
            '\u2022' -> 0x95.toByte()
            '\u2013' -> 0x96.toByte()
            '\u2014' -> 0x97.toByte()
            '\u02DC' -> 0x98.toByte()
            '\u2122' -> 0x99.toByte()
            '\u0161' -> 0x9A.toByte()
            '\u203A' -> 0x9B.toByte()
            '\u0153' -> 0x9C.toByte()
            '\u017E' -> 0x9E.toByte()
            '\u0178' -> 0x9F.toByte()
            else -> if (c.code in 0..255) c.code.toByte() else '?'.code.toByte()
        }
        bytes[i] = b
    }
    return String(bytes, Charsets.UTF_8)
}

inline fun <reified T> Json.decodeFromStringFixed(string: String): T {
    val element = this.parseToJsonElement(string)
    return this.decodeFromJsonElement(element.fixEncoding())
}
