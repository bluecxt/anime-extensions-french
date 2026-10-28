// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.utils

import eu.kanade.tachiyomi.animesource.model.Track
import fr.bluecxt.core.model.ExtractedSource
import org.jsoup.Jsoup.parse
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.select.Elements
import java.util.Dictionary

data class HlsAudioStream(
    val id: String,
    val language: String,
    val codecs: String,
    val bandwidth: Long,
    val initSegmentUri: String,
    val segments: List<HlsSegment>,
)

data class HlsVideoStream(
    val id: String,
    val codecs: String,
    val bandwidth: Long,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val initSegmentUri: String,
    val segments: List<HlsSegment>,
)

data class HlsSegment(
    val uri: String,
    val durationSeconds: Double,
)

enum class Lang(val langCode: String, val dict: Map<String, String>) {
    FR(
        "fr",
        mapOf(
            "jpn" to "Japonais",
            "fra" to "Français",
            "fre" to "Français",
            "eng" to "Anglais",
        ),
    ),
    EN(
        "en",
        mapOf(
            "jpn" to "Japanese",
            "fra" to "French",
            "fre" to "French",
            "eng" to "English",
        ),
    ),
    ;

    fun getLabel(code: String): String = dict[code] ?: code
}

class DashToExtractedSource {
    // TODO: refactor cette function pour quelle ne fasse que le parsing de base mais pour quelle en une passe fasse les audios puis les video pour pouvoir catch les throw avec un runcatchingcancellable comme sa on peux return manuellement gerer les throw et si toute les qualité n'ont pas fail log celle qui ne passe pas avec leur corps complet
    fun fromString(xmlString: String, baseUrl: String, userLanguage: Lang): List<ExtractedSource>? {
        val doc = parse(xmlString, baseUrl, Parser.xmlParser())

        val mpd = doc.selectFirst("MPD") ?: return null
        val period = mpd.select("Period").takeIf { it.isNotEmpty() } ?: return null
        val adaptationSet = period.select("AdaptationSet").takeIf { it.isNotEmpty() } ?: return null
        val videoAdaptationSet = adaptationSet.typeIs("video") ?: return null
        val audioAdaptationSet = adaptationSet.typeIs("audio") ?: emptyList()

        val hlsAudioTracks = audioAdaptationSet.mapNotNull { element ->
            if (element == null) return@mapNotNull null
            val langAttr = element.attr("lang").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val id = element.attr("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = userLanguage.getLabel(langAttr)
            val representation = element.select("Representation")

            representation.mapNotNull { rep ->
                val repId = rep.attr("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val initSegmentUri = parseInitSegmentUri(rep, baseUrl, repId) ?: return@mapNotNull null
                val codecs = rep.attr("codecs").ifEmpty { adaptationSet.attr("codecs") }
                val bandwidth = rep.attr("bandwidth").ifEmpty { adaptationSet.attr("bandwidth") }.toLongOrNull()

                val segments = parseSegments(rep, baseUrl, repId)
                HlsAudioStream(
                    id = id,
                    language = langAttr,
                    codecs = codecs,
                    bandwidth = bandwidth ?: 0L,
                    initSegmentUri = initSegmentUri,
                    segments = segments,
                )
            }
        }.flatten()

        val videoTracks = videoAdaptationSet.mapNotNull { element ->
            val id = element.attr("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val representation = element.select("Representation")

            representation.mapNotNull { rep ->
                val repId = rep.attr("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val initSegmentUri = parseInitSegmentUri(rep, baseUrl, repId) ?: return@mapNotNull null

                val rawFps = rep.attr("frameRate").ifEmpty { adaptationSet.attr("frameRate") }
                val codecs = rep.attr("codecs").ifEmpty { adaptationSet.attr("codecs") }
                val bandwidth = rep.attr("bandwidth").ifEmpty { adaptationSet.attr("bandwidth") }.toLongOrNull() ?: 0L
                val width = rep.attr("width").ifEmpty { adaptationSet.attr("width") }.toIntOrNull()
                val height = rep.attr("height").ifEmpty { adaptationSet.attr("height") }.toIntOrNull()
                val segments = parseSegments(rep, baseUrl, repId)
                HlsVideoStream(
                    id = id,
                    codecs = codecs,
                    bandwidth = bandwidth,
                    width = width,
                    height = height,
                    fps = parseFrameRate(rawFps),
                    initSegmentUri = initSegmentUri,
                    segments = segments,
                )
            }
        }.flatten()
        val audioTracks = hlsAudioTracks.map { it.toTrack() }
        return videoTracks.map { it.toExtractedSource(audioTracks) }
    }

    private fun HlsVideoStream.toExtractedSource(audioTracks: List<Track>): ExtractedSource {
        val url = ""

        return ExtractedSource(
            url = url,
            quality = "${this.height}p",
            frameRate = "${this.fps}fps",
            audioTracks = audioTracks,
        )
    }

    private fun HlsAudioStream.toTrack(): Track {
        val url = ""
        val lang = ""

        return Track(
            url = url,
            lang = lang,
        )
    }

    private fun parseInitSegmentUri(rep: Element, baseUrl: String, repId: String): String? {
        val templateElement = rep.selectFirst("SegmentTemplate")
        val initTemplate = templateElement?.attr("initialization") ?: return null
        val initPath = initTemplate.replace($$"RepresentationID$$", repId)
        val initSegmentUri = "$baseUrl/$initPath"

        return initSegmentUri
    }

    fun parseFrameRate(rawFps: String): Double? {
        if (rawFps.isEmpty()) return null

        return if (rawFps.contains("/")) {
            val parts = rawFps.split("/")
            val num = parts[0].toDoubleOrNull() ?: return null
            val den = parts[1].toDoubleOrNull() ?: return null
            if (den != 0.0) num / den else null
        } else {
            rawFps.toDoubleOrNull()
        }
    }

    private fun parseSegments(rep: Element, baseUrl: String, repId: String): List<HlsSegment> {
        val template = rep.selectFirst("SegmentTemplate") ?: return emptyList()

        val timescale = template.attr("timescale").toDoubleOrNull() ?: 1.0
        var currentNumber = template.attr("startNumber").toLongOrNull() ?: 1L

        val initPattern = template.attr("initialization").replace($$"RepresentationID$$", repId)
        val initUri = "$baseUrl/$initPattern"

        val mediaPattern = template.attr("media").replace($$"RepresentationID$$", repId)

        val segments = mutableListOf<HlsSegment>()
        val timelineElements = template.select("SegmentTimeline > S")

        for (s in timelineElements) {
            val durationTicks = s.attr("d").toDoubleOrNull() ?: 0.0
            val durationSec = durationTicks / timescale
            val repeatCount = s.attr("r").toIntOrNull() ?: 0
            for (i in 0..repeatCount) {
                val formattedNumber = String.format("%05d", currentNumber)
                val segmentPath = mediaPattern.replace($$"Number%05d$$", formattedNumber)
                segments.add(
                    HlsSegment(
                        uri = "$baseUrl/$segmentPath",
                        durationSeconds = durationSec,
                    ),
                )
                currentNumber++
            }
        }
        return segments
    }

    private fun Elements.typeIs(type: String): Elements? = this.select("[contentType=$type], [mimeType^=$type]").takeIf { it.isNotEmpty() }
}
