package fr.bluecxt.core.utils

import android.net.Uri
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.PLAYLIST_LOG
import fr.bluecxt.core.model.ExtractedSource
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelMapNotNullBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.abs

class PlaylistUtils(private val client: OkHttpClient, private val headers: Headers = Headers.EMPTY) {

    // ================================ M3U8 ================================

    /**
     * Extracts video information from an HLS .m3u8 file using direct headers.
     *
     * @param playlistUrl the URL of the .m3u8 file
     * @param referer the referer header value
     * @param masterHeaders headers for the master playlist request
     * @param videoHeaders headers for the individual video requests
     * @param subtitleList initial list of subtitles
     * @param audioList initial list of audio tracks
     * @param toStandardQuality function to map resolution to a standard string (e.g. 1080p)
     */
    suspend fun extractFromHls(
        playlistUrl: String,
        referer: String = playlistUrl.toDefaultReferer(),
        masterHeaders: Headers,
        videoHeaders: Headers,
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
        toStandardQuality: (String) -> String = { quality ->
            stnQuality(quality)
        },
    ): List<ExtractedSource> = extractFromHls(
        playlistUrl,
        referer,
        { _, _ -> masterHeaders },
        { _, _, _ -> videoHeaders },
        subtitleList,
        audioList,
        toStandardQuality,
    )

    /**
     * Extracts video information from an HLS .m3u8 file using header generators.
     *
     * @param playlistUrl the URL of the .m3u8 file
     * @param referer the referer header value
     * @param masterHeadersGen function to generate master playlist headers
     * @param videoHeadersGen function to generate individual video headers
     * @param subtitleList initial list of subtitles
     * @param audioList initial list of audio tracks
     * @param toStandardQuality function to map resolution to a standard string (e.g. 1080p)
     */
    suspend fun extractFromHls(
        playlistUrl: String,
        referer: String = playlistUrl.toDefaultReferer(),
        masterHeadersGen: (Headers, String) -> Headers = ::generateMasterHeaders,
        videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, referer, _ ->
            generateMasterHeaders(baseHeaders, referer)
        },
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
        toStandardQuality: (String) -> String = { quality ->
            stnQuality(quality)
        },
    ): List<ExtractedSource> {
        val masterHeaders = masterHeadersGen(headers, referer)

        var masterPlaylist = ""
        var attempts = 0
        while (attempts < 3) {
            try {
                masterPlaylist = client.newCall(GET(playlistUrl, masterHeaders))
                    .awaitSuccess().bodyString()
                break
            } catch (e: Exception) {
                attempts++
                if (attempts >= 3) {
                    Log.e(PLAYLIST_LOG, "Failed to fetch HLS playlist after 3 attempts: $playlistUrl", e)
                    throw e
                }
                Log.w(PLAYLIST_LOG, "HLS fetch failed (attempt $attempts), retrying in 1s... (${e.message})")
                kotlinx.coroutines.delay(1000)
            }
        }
        // Check if there isn't multiple streams available
        if (PLAYLIST_SEPARATOR !in masterPlaylist) {
            return listOf(
                ExtractedSource(
                    url = playlistUrl,
                    headers = masterHeaders,
                    subtitleTracks = subtitleList,
                    audioTracks = audioList,
                ),
            )
        }

        // Get subtitles
        val subtitleTracks = subtitleList + SUBTITLE_REGEX.findAll(masterPlaylist).mapNotNull {
            Track(
                UrlUtils.fixUrl(it.groupValues[2], playlistUrl) ?: return@mapNotNull null,
                it.groupValues[1],
            )
        }.toList()

        // Get audio tracks
        val audioTracks = audioList + AUDIO_REGEX.findAll(masterPlaylist).mapNotNull {
            Track(
                UrlUtils.fixUrl(it.groupValues[2], playlistUrl) ?: return@mapNotNull null,
                it.groupValues[1],
            )
        }.toList()

        return masterPlaylist.substringAfter(PLAYLIST_SEPARATOR).split(PLAYLIST_SEPARATOR).mapNotNull { stream ->
            val codec = CODECS_REGEX.find(stream)?.groupValues?.get(1)
            if (!codec.isNullOrBlank()) {
                // Skip audio only streams. Can check if `codecs` starts with any of avc/hev1/hvc1/vp9/av01.
                val codecs = codec.split(',')
                if (codecs.all { it.startsWith("mp4a") }) return@mapNotNull null
            }

            val resolution = RESOLUTION_REGEX.find(stream)?.groupValues?.get(1) ?: ""
            val rawQuality = QUALITY_REGEX.find(resolution)?.groupValues?.get(1) ?: ""
            val quality = toStandardQuality(rawQuality)

            val frameRate = FRAME_RATE_REGEX.find(stream)?.groupValues?.get(1)
                ?.toDoubleOrNull()
                ?.let { fps ->
                    "${fps.toInt()}fps"
                }

            val bandwidth = BANDWIDTH_REGEX.find(stream)
                ?.groupValues?.get(1)
                ?.toLongOrNull()

            val videoUrl = stream.substringAfter("\n").substringBefore("\n").let { url ->
                UrlUtils.fixUrl(url, playlistUrl)?.trimEnd()
            } ?: return@mapNotNull null

            bandwidth to ExtractedSource(
                url = videoUrl,
                quality = quality,
                frameRate = frameRate,
                headers = videoHeadersGen(headers, referer, videoUrl),
                subtitleTracks = subtitleTracks,
                audioTracks = audioTracks,
            )
        }
            .sortedByDescending { (bandwidth, _) ->
                bandwidth ?: 0L
            }
            .map { (_, video) -> video }
    }

    fun generateMasterHeaders(baseHeaders: Headers, referer: String): Headers = baseHeaders.newBuilder().apply {
        set("Accept", "*/*")
        if (referer.isNotEmpty()) {
            set("Origin", "https://${referer.toHttpUrl().host}")
            set("Referer", referer)
        }
    }.build()

    // ================================ DASH ================================

    /**
     * Extracts video information from a DASH .mpd file using direct headers.
     *
     * @param mpdUrl the URL of the .mpd file
     * @param mpdHeaders the headers to be sent in the HTTP request for the MPD file
     * @param videoHeaders the headers to be sent in the HTTP requests for video segments
     * @param referer the referer header value
     * @param subtitleList initial list of subtitles
     * @param audioList initial list of audio tracks
     * @param toStandardQuality function to map resolution to a standard string (e.g. 1080p)
     */
    @Suppress("unused")
    suspend fun extractFromDash(
        mpdUrl: String,
        mpdHeaders: Headers,
        videoHeaders: Headers,
        referer: String = mpdUrl.toDefaultReferer(),
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
        toStandardQuality: (String) -> String = { quality ->
            stnQuality(quality)
        },
    ): List<ExtractedSource> = extractFromDash(
        mpdUrl,
        referer,
        { _, _ -> mpdHeaders },
        { _, _, _ -> videoHeaders },
        subtitleList,
        audioList,
        toStandardQuality,
    )

    /**
     * Extracts video information from a DASH .mpd file using header generators.
     *
     * @param mpdUrl the URL of the .mpd file
     * @param referer the referer header value
     * @param mpdHeadersGen function to generate MPD request headers
     * @param videoHeadersGen function to generate video segment headers
     * @param subtitleList initial list of subtitles
     * @param audioList initial list of audio tracks
     * @param toStandardQuality function to map resolution to a standard string (e.g. 1080p)
     */
    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun extractFromDash(
        mpdUrl: String,
        referer: String = mpdUrl.toDefaultReferer(),
        mpdHeadersGen: (Headers, String) -> Headers = ::generateMasterHeaders,
        videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, referer, _ ->
            generateMasterHeaders(baseHeaders, referer)
        },
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
        toStandardQuality: (String) -> String = { quality ->
            stnQuality(quality)
        },
    ): List<ExtractedSource> {
        val mpdHeaders = mpdHeadersGen(headers, referer)

        val doc = client.newCall(GET(mpdUrl, mpdHeaders))
            .awaitSuccess().useAsJsoup()

        // Get audio tracks
        val audioTracks = audioList + doc.select("Representation[mimetype~=audio]").map { audioSrc ->
            val bandwidth = audioSrc.attr("bandwidth").toLongOrNull()
            Track(audioSrc.text(), formatBytes(bandwidth))
        }

        return doc.select("Representation[mimetype~=video]").map { videoSrc ->
            val bandwidth = videoSrc.attr("bandwidth")
            val quality = videoSrc.attr("height")
                .let(toStandardQuality)
            val videoUrl = videoSrc.text()
            ExtractedSource(
                url = videoUrl,
                quality = quality,
                audioTracks = audioTracks,
                subtitleTracks = subtitleList,
                headers = videoHeadersGen(headers, referer, videoUrl),
            )
        }
    }

    private fun formatBytes(bytes: Long?): String = when {
        bytes == null -> ""
        bytes >= 1_000_000_000 -> "%.2f GB/s".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB/s".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB/s".format(bytes / 1_000.0)
        bytes > 1 -> "$bytes bytes/s"
        bytes == 1L -> "$bytes byte/s"
        else -> ""
    }

    // ============================= Utilities ==============================

    private fun String.toDefaultReferer(): String = try {
        toHttpUrl().run { "$scheme://$host/" }
    } catch (_: IllegalArgumentException) {
        ""
    }

    private fun stnQuality(quality: String): String {
        val intQuality = quality.trim().toIntOrNull() ?: return quality
        val result = STANDARD_QUALITIES.minByOrNull { abs(it - intQuality) } ?: intQuality
        return if (abs(result - intQuality) <= 5) "${result}p" else "${intQuality}p"
    }

    /**
     * When the regex finds a match (illegal newlines), this function is called to replace them.
     * Instead of just deleting the lines (which might mess up the visual timing/positioning intended by the creator),
     * it replaces them with non-breaking spaces (`&nbsp;`).
     * This preserves the "height" or "spacing" of the original text without breaking the VTT file structure.
     */
    private fun cleanSubtitleData(matchResult: MatchResult): String {
        val lineCount = matchResult.groupValues[1].count { it == '\n' }
        return "\n" + "&nbsp;\n".repeat(lineCount - 1)
    }

    /**
     * Fix a common issue in VTT (WebVTT) subtitle files where extra or unexpected newline characters break the subtitle format,
     * potentially causing players to fail to render them correctly.
     */
    fun fixSubtitles(subtitleList: List<Track>): List<Track> = subtitleList.parallelMapNotNullBlocking {
        runCatching {
            val subData = client.newCall(GET(it.url))
                .awaitSuccess().bodyString()

            val file = File.createTempFile("subs", "vtt")
                .also(File::deleteOnExit)

            file.writeText(FIX_SUBTITLE_REGEX.replace(subData, ::cleanSubtitleData))
            val uri = Uri.fromFile(file)

            Track(uri.toString(), it.lang)
        }.getOrNull()
    }

    companion object {
        /**
         * This regex identifies "illegal" gaps or line breaks within a subtitle file.
         *
         * * `$`: Matches the end of a line.
         * * `(\n{2,})`: Captures a group of two or more consecutive newline characters. In VTT files, a double newline usually indicates the end of one "cue" (subtitle block) and the start of another.
         * * `(?!(?:\d+:)*\d+(?:\.\d+)?\s-+>\s(?:\d+:)*\d+(?:\.\d+)?)`: This is a negative lookahead. It checks that what follows the newlines is NOT a VTT timestamp (e.g., `00:00:10.000 --> 00:00:12.000)`.
         *
         * **Logic**: If the code finds multiple empty lines, but the next thing it sees isn't a new timestamp, it assumes those empty lines are garbage or mid-text breaks that will break the parser.
         */
        private val FIX_SUBTITLE_REGEX = Regex("""$(\n{2,})(?!(?:\d+:)*\d+(?:\.\d+)?\s-+>\s(?:\d+:)*\d+(?:\.\d+)?)""", RegexOption.MULTILINE)

        private const val PLAYLIST_SEPARATOR = "#EXT-X-STREAM-INF:"

        private val SUBTITLE_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""") }
        private val AUDIO_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=AUDIO.*?NAME="(.*?)".*?URI="(.*?)"""") }

        private val CODECS_REGEX by lazy { Regex("""CODECS="([^"]+)"""") }
        private val RESOLUTION_REGEX by lazy { Regex("""RESOLUTION=([xX\d]+)""") }
        private val QUALITY_REGEX by lazy { Regex("""[xX](\d+)""") }
        private val BANDWIDTH_REGEX by lazy { Regex("""BANDWIDTH=(\d+)""") }
        private val FRAME_RATE_REGEX by lazy { Regex("""FRAME-RATE=([\d.]+)""") }

        private val STANDARD_QUALITIES = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
    }
}
