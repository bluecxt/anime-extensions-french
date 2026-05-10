package eu.kanade.tachiyomi.animesource.model

import okhttp3.Headers

data class Track(val url: String, val lang: String)

data class Video(
    val url: String,
    val quality: String,
    var videoUrl: String?,
    val headers: Headers? = null,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
)
