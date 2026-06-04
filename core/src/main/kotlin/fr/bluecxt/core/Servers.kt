package fr.bluecxt.core

import fr.bluecxt.core.extractors.SendvidExtractor
import fr.bluecxt.core.extractors.SibnetExtractor
import fr.bluecxt.core.model.VideoServer

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

    else -> null
}
