package fr.bluecxt.core.model

import eu.kanade.tachiyomi.animesource.model.Track
import okhttp3.Headers
import okhttp3.HttpUrl

data class ExtractedSource(
    val url: String,
    val quality: String? = null, // ex: "720p"
    val frameRate: String? = null, // ex: "30fps"
    val headers: Headers? = null,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
)
