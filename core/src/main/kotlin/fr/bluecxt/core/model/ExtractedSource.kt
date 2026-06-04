package fr.bluecxt.core.model

import eu.kanade.tachiyomi.animesource.model.Track
import okhttp3.HttpUrl

data class ExtractedSource(
    val url: String,
    val quality: String? = "", // ex: "720p"
    val referer: HttpUrl? = null,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
)
