package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.toExtractedSources
import fr.bluecxt.core.utils.PlaylistUtils
import okhttp3.Headers
import okhttp3.OkHttpClient

class WavePlayerExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(
        masterUrl: String,
        refererUrl: String,
        subtitleTracks: List<Track> = emptyList(),
    ): List<ExtractedSource> {
        // On utilise l'outil officiel pour analyser le DASH (.mpd)
        // La fonction génère le nom avec la qualité (ex: "1080p")
        val extHeaders = Headers.Builder().add("Referer", refererUrl).build()

        return playlistUtils.extractFromDash(
            mpdUrl = masterUrl,
            videoNameGen = { quality -> quality },
            referer = refererUrl,
            subtitleList = subtitleTracks,
        ).toExtractedSources(extHeaders)
    }
}
