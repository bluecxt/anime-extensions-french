package eu.kanade.tachiyomi.animeextension.fr.animeultime.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
