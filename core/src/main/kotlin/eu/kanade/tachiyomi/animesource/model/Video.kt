package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers

@Serializable
data class Track(val url: String, val lang: String)

@Serializable
enum class ChapterType {
    Opening,
    Ending,
    Recap,
    MixedOp,
    Other,
}

@Serializable
data class TimeStamp(
    val start: Double,
    val end: Double,
    val name: String,
    val type: ChapterType = ChapterType.Other,
)

open class Video(
    var videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: Headers? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
) {

    var type: VideoType = VideoType.VIDEO
    var mimeType: String? = null

    @Transient
    @Volatile
    var status: State = State.QUEUE
        set(value) {
            field = value
        }

    @Transient
    @Volatile
    var lastByteRead: Long = 0
        set(value) {
            field = value
        }

    fun copy(
        videoUrl: String = this.videoUrl,
        videoTitle: String = this.videoTitle,
        resolution: Int? = this.resolution,
        bitrate: Int? = this.bitrate,
        headers: Headers? = this.headers,
        preferred: Boolean = this.preferred,
        subtitleTracks: List<Track> = this.subtitleTracks,
        audioTracks: List<Track> = this.audioTracks,
        timestamps: List<TimeStamp> = this.timestamps,
        mpvArgs: List<Pair<String, String>> = this.mpvArgs,
        ffmpegStreamArgs: List<Pair<String, String>> = this.ffmpegStreamArgs,
        ffmpegVideoArgs: List<Pair<String, String>> = this.ffmpegVideoArgs,
        internalData: String = this.internalData,
        initialized: Boolean = this.initialized,
    ): Video = Video(
        videoUrl = videoUrl,
        videoTitle = videoTitle,
        resolution = resolution,
        bitrate = bitrate,
        headers = headers,
        preferred = preferred,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        timestamps = timestamps,
        mpvArgs = mpvArgs,
        ffmpegStreamArgs = ffmpegStreamArgs,
        ffmpegVideoArgs = ffmpegVideoArgs,
        internalData = internalData,
        initialized = initialized,
    ).also {
        it.type = this.type
        it.mimeType = this.mimeType
    }

    fun copy(
        videoUrl: String = this.videoUrl,
        videoTitle: String = this.videoTitle,
        resolution: Int? = this.resolution,
        bitrate: Int? = this.bitrate,
        headers: Headers? = this.headers,
        preferred: Boolean = this.preferred,
        subtitleTracks: List<Track> = this.subtitleTracks,
        audioTracks: List<Track> = this.audioTracks,
        timestamps: List<TimeStamp> = this.timestamps,
        mpvArgs: List<Pair<String, String>> = this.mpvArgs,
        ffmpegStreamArgs: List<Pair<String, String>> = this.ffmpegStreamArgs,
        ffmpegVideoArgs: List<Pair<String, String>> = this.ffmpegVideoArgs,
        internalData: String = this.internalData,
        initialized: Boolean = this.initialized,
        type: VideoType = this.type,
        mimeType: String? = this.mimeType,
    ): Video = Video(
        videoUrl = videoUrl,
        videoTitle = videoTitle,
        resolution = resolution,
        bitrate = bitrate,
        headers = headers,
        preferred = preferred,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        timestamps = timestamps,
        mpvArgs = mpvArgs,
        ffmpegStreamArgs = ffmpegStreamArgs,
        ffmpegVideoArgs = ffmpegVideoArgs,
        internalData = internalData,
        initialized = initialized,
    ).also {
        it.type = type
        it.mimeType = mimeType
    }

    enum class State {
        QUEUE,
        LOAD_VIDEO,
        READY,
        ERROR,
    }

    companion object {
        const val MPV_ARGS_TAG = "ANIYOMI_MPV_ARGS"
    }
}

@Serializable
data class SerializableVideo(
    val videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: List<Pair<String, String>>? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
    val type: VideoType = VideoType.VIDEO,
    var mimeType: String? = null,
) {

    companion object {
        fun List<Video>.serialize(): String = Json.encodeToString(
            this.map { vid ->
                SerializableVideo(
                    vid.videoUrl,
                    vid.videoTitle,
                    vid.resolution,
                    vid.bitrate,
                    vid.headers?.toList(),
                    vid.preferred,
                    vid.subtitleTracks,
                    vid.audioTracks,
                    vid.timestamps,
                    vid.mpvArgs,
                    vid.ffmpegStreamArgs,
                    vid.ffmpegVideoArgs,
                    vid.internalData,
                    vid.initialized,
                    vid.type,
                    vid.mimeType,
                )
            },
        )

        fun String.toVideoList(): List<Video> = Json.decodeFromString<List<SerializableVideo>>(this)
            .map { sVid ->
                Video(
                    sVid.videoUrl,
                    sVid.videoTitle,
                    sVid.resolution,
                    sVid.bitrate,
                    sVid.headers
                        ?.flatMap { it.toList() }
                        ?.let { Headers.headersOf(*it.toTypedArray()) },
                    sVid.preferred,
                    sVid.subtitleTracks,
                    sVid.audioTracks,
                    sVid.timestamps,
                    sVid.mpvArgs,
                    sVid.ffmpegStreamArgs,
                    sVid.ffmpegVideoArgs,
                    sVid.internalData,
                    sVid.initialized,
                ).apply {
                    type = sVid.type
                    mimeType = sVid.mimeType
                }
            }
    }
}
