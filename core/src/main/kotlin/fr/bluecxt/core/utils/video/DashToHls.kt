// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.utils.video

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup.parse
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import kotlin.math.ceil

/**
 * Une piste audio DASH déjà résolue (init + segments fMP4) prête à être convertie en playlist HLS.
 *
 * @property id identifiant du `Representation` d'origine.
 * @property language code de langue brut du MPD (ex: `jpn`, `fra`).
 * @property label libellé humain dérivé de [Lang] (ex: `Japonais`).
 * @property codecs chaîne `codecs` (héritée du `Representation` ou de l'`AdaptationSet`).
 * @property bandwidth débit déclaré en bits/s, `0` si absent du manifeste.
 * @property initSegmentUri URI absolue du segment d'initialisation (`#EXT-X-MAP`).
 * @property segments segments média ordonnés du `SegmentTimeline`.
 */
data class HlsAudioStream(
    val id: String,
    val language: String,
    val label: String,
    val codecs: String,
    val bandwidth: Long,
    val initSegmentUri: String,
    val segments: List<HlsSegment>,
)

/**
 * Une piste vidéo DASH déjà résolue (init + segments fMP4) prête à être convertie en playlist HLS.
 *
 * @property id identifiant du `Representation` d'origine.
 * @property codecs chaîne `codecs` (héritée du `Representation` ou de l'`AdaptationSet`).
 * @property bandwidth débit déclaré en bits/s, `0` si absent du manifeste.
 * @property width largeur en pixels, `null` si absente.
 * @property height hauteur en pixels, `null` si absente (sert à construire la qualité `720p`).
 * @property fps images par seconde déjà normalisées depuis `frameRate` (`30000/1001` → `29.97`).
 * @property initSegmentUri URI absolue du segment d'initialisation (`#EXT-X-MAP`).
 * @property segments segments média ordonnés du `SegmentTimeline`.
 */
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

/**
 * Un segment média résolu.
 *
 * @property uri URI absolue du segment.
 * @property durationSeconds durée du segment en secondes (`d / timescale`).
 */
data class HlsSegment(
    val uri: String,
    val durationSeconds: Double,
)

/**
 * Libellés de langue utilisés pour nommer les pistes audio exposées au lecteur.
 */
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

    /**
     * Traduit un code ISO du MPD en libellé lisible, ou retourne le code tel quel s'il est inconnu.
     */
    fun getLabel(code: String): String = dict[code] ?: code
}

/**
 * Convertit un manifeste **DASH (MPD)** en sources lisibles par le lecteur, sous forme de **playlists HLS
 * générées à la volée** et exposées en `data:` URI (aucun serveur intermédiaire n'est nécessaire).
 *
 * ### Fonctionnement
 * Les segments DASH fMP4 sont directement jouables par HLS tant que la playlist est conforme : on émet
 * donc un `#EXTM3U` VOD version 7, avec `#EXT-X-MAP` (segment d'initialisation), un `#EXTINF` par segment
 * puis `#EXT-X-ENDLIST`. Chaque qualité vidéo devient un [ExtractedSource] indépendant, chacune portant
 * toutes les pistes audio DASH en `audioTracks` (le lecteur fusionne vidéo + audio).
 *
 * ### Limites assumées
 * - Le `data:` URI grossit avec le nombre de segments (~60 octets par segment en base64). Sur un film
 *   très découpé (2000+ segments) l'URL dépasse 100 Ko : le lecteur interne d'Aniyomi l'accepte, mais
 *   **un lecteur externe peut refuser** une URL aussi longue. Préférer alors le proxy/serveur du site.
 * - Seuls les manifestes à `SegmentTemplate` (`$Number$` / `$Number%0Nd$`) + `SegmentTimeline` sont
 *   résolus. `SegmentList`, `SegmentBase`, `$Time$` et les manifestes à `@duration` implicite sont ignorés
 *   (la représentation est écartée et signalée via `onQualityError`).
 * - Un manifeste multi-périodes (insertion publicitaire) verra ses `AdaptationSet` fusionnés, ce qui n'est
 *   pas un cas supporté.
 *
 * ### Gestion des erreurs
 * Un `Representation` invalide n'invalide pas les autres : chaque qualité est traitée isolément. Les
 * échecs sont remontés à `onQualityError` (typiquement branché sur `ErrorWebhook.sendWebhook` côté
 * extension) afin de pouvoir diagnostiquer une qualité précise sans perdre les autres.
 */
class DashToExtractedSource {
    /** Manifeste DASH non pris en charge (template manquant, jeton `$Time$`, etc.). */
    private class DashUnsupported(message: String) : Exception(message)

    /**
     * Analyse un manifeste MPD et retourne les sources vidéo jouables, une par qualité.
     *
     * @param xmlString corps brut du manifeste MPD.
     * @param baseUrl URL absolue servant de base à la résolution des URI relatives (typiquement l'URL du MPD).
     * @param userLanguage langue de l'utilisateur, utilisée pour nommer les pistes audio.
     * @param onQualityError callback optionnel notifié pour chaque qualité écartée (message lisible).
     * @return les sources extraites, ou `null` si le manifeste n'est pas un MPD exploitable.
     */
    fun fromString(
        xmlString: String,
        baseUrl: String,
        userLanguage: Lang,
        onQualityError: ((String) -> Unit)? = null,
    ): List<ExtractedSource>? {
        val doc = parse(xmlString, baseUrl, Parser.xmlParser())

        val mpd = doc.selectFirst("MPD") ?: return null
        val periods = mpd.select("Period").takeIf { it.isNotEmpty() } ?: return null
        val adaptationSets = periods.flatMap { it.select("AdaptationSet") }.takeIf { it.isNotEmpty() } ?: return null

        val videoAdaptationSet = adaptationSets.filter { it.attr("contentType") == "video" || it.attr("mimeType").startsWith("video") }.takeIf { it.isNotEmpty() } ?: return null
        val audioAdaptationSet = adaptationSets.filter { it.attr("contentType") == "audio" || it.attr("mimeType").startsWith("audio") }

        val hlsAudioTracks = audioAdaptationSet.mapNotNull { adaptationSet ->
            val langAttr = adaptationSet.attr("lang").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val id = adaptationSet.attr("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = userLanguage.getLabel(langAttr)

            adaptationSet.select("Representation").mapNotNull { rep ->
                resolveAudioStream(rep, adaptationSet, mpd, baseUrl, id, langAttr, label, onQualityError)
            }
        }.flatten()

        val videoTracks = videoAdaptationSet.mapNotNull { adaptationSet ->
            val id = adaptationSet.attr("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            adaptationSet.select("Representation").mapNotNull { rep ->
                resolveVideoStream(rep, adaptationSet, mpd, baseUrl, id, onQualityError)
            }
        }.flatten()

        val audioTracks = hlsAudioTracks.mapNotNull { it.toTrack() }
        return videoTracks.mapNotNull { it.toExtractedSource(audioTracks) }
    }

    /**
     * Décode une valeur `frameRate` DASH, qui peut être une fraction (`30000/1001`).
     */
    fun parseFrameRate(rawFps: String): Double? {
        if (rawFps.isEmpty()) return null

        return if (rawFps.contains("/")) {
            val parts = rawFps.split("/")
            if (parts.size != 2) return null
            val num = parts[0].toDoubleOrNull() ?: return null
            val den = parts[1].toDoubleOrNull() ?: return null
            if (den != 0.0) num / den else null
        } else {
            rawFps.toDoubleOrNull()
        }
    }

    // ============================ Résolution des pistes =============================

    private fun resolveAudioStream(
        rep: Element,
        adaptationSet: Element,
        mpd: Element,
        baseUrl: String,
        id: String,
        langAttr: String,
        label: String,
        onQualityError: ((String) -> Unit)?,
    ): HlsAudioStream? = runCatching {
        val repId = rep.attr("id").ifBlank { throw DashUnsupported("audio $id: Representation sans attribut 'id'") }
        val initSegmentUri = parseInitSegmentUri(rep, adaptationSet, mpd, baseUrl, repId)
            ?: throw DashUnsupported("audio $id/$repId: SegmentTemplate@initialization absent")
        val segments = parseSegments(rep, adaptationSet, baseUrl, repId)
            ?: throw DashUnsupported("audio $id/$repId: SegmentTimeline introuvable")

        HlsAudioStream(
            id = id,
            language = langAttr,
            label = label,
            codecs = rep.attr("codecs").ifBlank { adaptationSet.attr("codecs") },
            bandwidth = rep.attr("bandwidth").ifBlank { adaptationSet.attr("bandwidth") }.toLongOrNull() ?: 0L,
            initSegmentUri = initSegmentUri,
            segments = segments,
        )
    }.onFailure {
        onQualityError?.invoke(it.message ?: "audio $id/${rep.attr("id")}: ${it::class.java.simpleName}")
    }.getOrNull()

    private fun resolveVideoStream(
        rep: Element,
        adaptationSet: Element,
        mpd: Element,
        baseUrl: String,
        id: String,
        onQualityError: ((String) -> Unit)?,
    ): HlsVideoStream? = runCatching {
        val repId = rep.attr("id").ifBlank { throw DashUnsupported("video $id: Representation sans attribut 'id'") }
        val initSegmentUri = parseInitSegmentUri(rep, adaptationSet, mpd, baseUrl, repId)
            ?: throw DashUnsupported("video $id/$repId: SegmentTemplate@initialization absent")
        val segments = parseSegments(rep, adaptationSet, baseUrl, repId)
            ?: throw DashUnsupported("video $id/$repId: SegmentTimeline introuvable")

        HlsVideoStream(
            id = id,
            codecs = rep.attr("codecs").ifBlank { adaptationSet.attr("codecs") },
            bandwidth = rep.attr("bandwidth").ifBlank { adaptationSet.attr("bandwidth") }.toLongOrNull() ?: 0L,
            width = rep.attr("width").ifBlank { adaptationSet.attr("width") }.toIntOrNull(),
            height = rep.attr("height").ifBlank { adaptationSet.attr("height") }.toIntOrNull(),
            fps = parseFrameRate(rep.attr("frameRate").ifBlank { adaptationSet.attr("frameRate") }),
            initSegmentUri = initSegmentUri,
            segments = segments,
        )
    }.onFailure {
        onQualityError?.invoke(it.message ?: "video $id/${rep.attr("id")}: ${it::class.java.simpleName}")
    }.getOrNull()

    // ============================ Conversion HLS =============================

    /**
     * Construit la playlist HLS média d'une qualité vidéo et l'expose en `data:` URI.
     */
    private fun HlsVideoStream.toExtractedSource(audioTracks: List<Track>): ExtractedSource? {
        val playlist = buildMediaPlaylist(initSegmentUri, segments) ?: return null

        return ExtractedSource(
            url = playlist.toDataUri(),
            quality = height?.let { "${it}p" }
                ?: bandwidth.takeIf { it > 0 }?.let { "${it / 1000} kbps" }
                ?: "Auto",
            frameRate = fps?.let { "${formatDecimal(it)}fps" },
            audioTracks = audioTracks,
        )
    }

    /**
     * Construit la playlist HLS média d'une piste audio et l'expose en `data:` URI.
     */
    private fun HlsAudioStream.toTrack(): Track? {
        val playlist = buildMediaPlaylist(initSegmentUri, segments) ?: return null
        return Track(url = playlist.toDataUri(), lang = label)
    }

    /**
     * Génère une playlist HLS VOD conforme pour des segments fMP4.
     *
     * @return la playlist, ou `null` si la piste n'a aucun segment exploitable (elle serait illisible).
     */
    private fun buildMediaPlaylist(initSegmentUri: String, segments: List<HlsSegment>): String? {
        if (segments.isEmpty()) return null

        // EXT-X-TARGETDURATION doit être un entier >= la durée du plus long segment.
        val targetDuration = ceil(segments.maxOf { it.durationSeconds }).toLong().coerceAtLeast(1L)

        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-TARGETDURATION:$targetDuration")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-MAP:URI=\"$initSegmentUri\"")
            for (segment in segments) {
                append("#EXTINF:").append(formatDecimal(segment.durationSeconds)).appendLine(",")
                appendLine(segment.uri)
            }
            appendLine("#EXT-X-ENDLIST")
        }
    }

    /**
     * Encode une playlist HLS en `data:` URI base64 (compatible ExoPlayer / `DataSchemeDataSource`).
     */
    private fun String.toDataUri(): String {
        val encoded = Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "data:application/vnd.apple.mpegurl;base64,$encoded"
    }

    // ============================ Parsing MPD =============================

    /**
     * Résout l'URI du segment d'initialisation déclaré par `SegmentTemplate@initialization`.
     *
     * @return l'URI absolue, ou `null` si le manifeste n'expose pas de template d'initialisation
     * (cas `SegmentBase`/`SegmentList`, non supportés).
     */
    private fun parseInitSegmentUri(
        rep: Element,
        adaptationSet: Element,
        mpd: Element,
        baseUrl: String,
        repId: String,
    ): String? {
        val template = segmentTemplateOf(rep, adaptationSet) ?: return null
        val initTemplate = template.attr("initialization").takeIf { it.isNotBlank() } ?: return null

        val path = expandTemplate(initTemplate, repId, number = null) ?: return null
        return resolveUri(baseUrl, rep, adaptationSet, mpd, path)
    }

    /**
     * Résout la liste ordonnée des segments média depuis `SegmentTimeline`.
     *
     * @return les segments, ou `null` si le manifeste n'utilise pas `SegmentTimeline`
     * (cas `@duration` implicite, non supporté).
     */
    private fun parseSegments(
        rep: Element,
        adaptationSet: Element,
        baseUrl: String,
        repId: String,
    ): List<HlsSegment>? {
        val template = segmentTemplateOf(rep, adaptationSet) ?: return null
        val mediaPattern = template.attr("media").takeIf { it.isNotBlank() } ?: return null
        val timeline = template.select("SegmentTimeline > S")
        if (timeline.isEmpty()) return null

        val timescale = template.attr("timescale").toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
        var currentNumber = template.attr("startNumber").toLongOrNull() ?: 1L

        val segments = mutableListOf<HlsSegment>()
        for (s in timeline) {
            val durationTicks = s.attr("d").toDoubleOrNull() ?: continue
            val repeatCount = s.attr("r").toIntOrNull() ?: 0
            // r = -1 signifie « jusqu'à la fin du Period » : non résolvable sans durée de période connue.
            if (repeatCount < 0) return null

            val durationSeconds = durationTicks / timescale
            repeat(repeatCount + 1) {
                val segmentPath = expandTemplate(mediaPattern, repId, currentNumber) ?: return null
                val uri = resolveUri(baseUrl, rep, adaptationSet, null, segmentPath) ?: return null
                segments.add(HlsSegment(uri = uri, durationSeconds = durationSeconds))
                currentNumber++
            }
        }
        return segments.takeIf { it.isNotEmpty() }
    }

    /**
     * Retourne le `SegmentTemplate` du `Representation`, ou à défaut celui de l'`AdaptationSet`.
     */
    private fun segmentTemplateOf(rep: Element, adaptationSet: Element): Element? = rep.selectFirst("SegmentTemplate") ?: adaptationSet.selectFirst("SegmentTemplate")

    /**
     * Remplace les jetons `$RepresentationID$`, `$Number$` et `$Number%0Nd$` d'un template DASH.
     *
     * @param number numéro de segment, `null` pour un template d'initialisation.
     * @return le chemin étendu, ou `null` si un jeton non supporté (`$Time$`, `$Bandwidth$`) est présent.
     */
    private fun expandTemplate(template: String, repId: String, number: Long?): String? {
        if (UNSUPPORTED_TOKEN.containsMatchIn(template)) return null

        var expanded = template.replace(REPRESENTATION_ID_TOKEN, repId)
        expanded = NUMBER_PATTERN.replace(expanded) { match ->
            val width = match.groupValues[1].toIntOrNull() ?: 0
            val value = number ?: 0L
            value.toString().padStart(width, '0')
        }
        return expanded
    }

    /**
     * Résout une URI relative en URI absolue, en tenant compte des `<BaseURL>` du manifeste.
     */
    private fun resolveUri(
        baseUrl: String,
        rep: Element,
        adaptationSet: Element,
        mpd: Element?,
        path: String,
    ): String? {
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) return path

        val manifestBaseUrl = listOfNotNull(
            rep.selectFirst("BaseURL")?.text(),
            adaptationSet.selectFirst("BaseURL")?.text(),
            mpd?.selectFirst("Period")?.selectFirst("BaseURL")?.text(),
            mpd?.selectFirst("BaseURL")?.text(),
        ).firstOrNull { it.isNotBlank() }

        val base = manifestBaseUrl?.let { baseUrl.toHttpUrlOrNull()?.resolve(it)?.toString() } ?: baseUrl
        return base.toHttpUrlOrNull()?.resolve(path)?.toString()
            ?: "$base/$path".replace(Regex("(?<!:)/{2,}"), "/")
    }

    /**
     * Formate un nombre décimal sans zéros inutiles (`29.970` → `29.97`, `24.0` → `24`).
     */
    private fun formatDecimal(value: Double): String {
        val rounded = kotlin.math.round(value * 1000.0) / 1000.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private companion object {
        const val REPRESENTATION_ID_TOKEN = "\$RepresentationID\$"

        /** Jetons DASH non supportés : ils dépendent d'informations absentes du seul `SegmentTimeline`. */
        val UNSUPPORTED_TOKEN = Regex("""\$(Time|Bandwidth|Segment|SubNumber)(%0\d+d)?\$""")

        /** `$Number$` ou `$Number%0Nd$` (`0` = largeur de padding absente). */
        val NUMBER_PATTERN = Regex("""\${'$'}Number(?:%0(\d+)d)?\${'$'}""")
    }
}
