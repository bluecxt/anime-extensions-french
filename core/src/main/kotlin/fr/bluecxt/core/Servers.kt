package fr.bluecxt.core

import fr.bluecxt.core.extractors.Embed4meExtractor
import fr.bluecxt.core.extractors.MinochinosExtractor
import fr.bluecxt.core.extractors.SendvidExtractor
import fr.bluecxt.core.extractors.SibnetExtractor
import fr.bluecxt.core.extractors.VidmolyExtractor
import fr.bluecxt.core.extractors.WaveplayerExtractor
import fr.bluecxt.core.model.VideoServer

val DEFAULT_SERVER = listOf("Sibnet", "Sendvid", "Vidmoly")

/**
 * Factory pour créer les objets serveurs à la demande.
 * Cela permet à R8/ProGuard de supprimer les extracteurs non utilisés par une extension.
 */
fun getVideoServer(source: Source, name: String): VideoServer? = when (name) {
    "Sibnet" -> VideoServer(
        name = "Sibnet",
        hosts = listOf("sibnet.ru"),
        extractor = { url -> SibnetExtractor(source.client).videosFromUrl(url) },
    )

    "Sendvid" -> VideoServer(
        name = "Sendvid",
        hosts = listOf("sendvid.com"),
        extractor = { url -> SendvidExtractor(source.client, source.headers).videosFromUrl(url) },
    )

    "WavePlayer" -> VideoServer(
        name = "WavePlayer",
        hosts = listOf("waveanime.fr"),
        extractor = { url ->
            // WavePlayer est spécial, l'appel sera géré manuellement dans WaveAnime
            // à cause de la gestion complexe des sous-titres, mais on l'inscrit ici pour l'architecture
            emptyList()
        },
    )

    "Vidmoly" -> VideoServer(
        name = "Vidmoly",
        hosts = listOf("vidmoly.*"),
        extractor = { url -> VidmolyExtractor(source.client, source.headers).videosFromUrl(url) },
    )

    "Minochinos" -> VideoServer(
        name = "Minochinos",
        hosts = listOf("minochinos.com"),
        extractor = { url -> MinochinosExtractor(source.client).videosFromUrl(url) },
    )

    "Embed4me" -> VideoServer(
        name = "Embed4me",
        hosts = listOf("*embed4me*"),
        extractor = { url -> Embed4meExtractor(source.client).videosFromUrl(url) },
    )

    else -> null
}
