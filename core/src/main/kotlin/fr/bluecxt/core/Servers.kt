package fr.bluecxt.core

import fr.bluecxt.core.extractors.DoodExtractor
import fr.bluecxt.core.extractors.Embed4meExtractor
import fr.bluecxt.core.extractors.FilemoonExtractor
import fr.bluecxt.core.extractors.GoogleDriveExtractor
import fr.bluecxt.core.extractors.JWplayerExtractor
import fr.bluecxt.core.extractors.MinochinosExtractor
import fr.bluecxt.core.extractors.MymailExtractor
import fr.bluecxt.core.extractors.OkruExtractor
import fr.bluecxt.core.extractors.SendvidExtractor
import fr.bluecxt.core.extractors.SibnetExtractor
import fr.bluecxt.core.extractors.VidmolyExtractor
import fr.bluecxt.core.extractors.VkExtractor
import fr.bluecxt.core.extractors.WaveplayerExtractor
import fr.bluecxt.core.model.VideoServer

val DEFAULT_SERVER = listOf(
    "Sibnet",
    "Sendvid",
    "Vidmoly",
    "Minochinos",
    "Embed4me",
    "Filemoon",
    "Okru",
    "Mymail",
    "Dood",
)

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

    // "Waveplayer" -> VideoServer(
    //     name = "WavePlayer",
    //     hosts = listOf("waveanime.fr"),
    //     extractor = { url -> WaveplayerExtractor(source.client, source.headers).videosFromUrl(url, "") },
    // )

    "GoogleDrive" -> VideoServer(
        name = "GoogleDrive",
        hosts = listOf("drive.*.google.com"),
        extractor = { id -> GoogleDriveExtractor(source.client).videosFromUrl(id) },
    )

    "Vidmoly" -> VideoServer(
        name = "Vidmoly",
        hosts = listOf("vidmoly.me", "vidmoly.to", "vidmoly.biz", "vidmoly.net"),
        extractor = { url -> VidmolyExtractor(source.client, source.headers).videosFromUrl(url) },
    )

    "Minochinos" -> VideoServer(
        name = "Minochinos",
        hosts = listOf("minochinos.com", "vidhide.com"),
        extractor = { url -> MinochinosExtractor(source.client).videosFromUrl(url) },
    )

    "Embed4me" -> VideoServer(
        name = "Embed4me",
        hosts = listOf("*embed4me.*", "seekstreaming.com"),
        extractor = { url -> Embed4meExtractor(source.client).videosFromUrl(url) },
    )

    // "Vk" -> VideoServer( // the majority of urls look down
    //     name = "Vk",
    //     hosts = listOf("vk.com", "vk.ru"),
    //     extractor = { url -> VkExtractor(source.client, source.headers).videosFromUrl(url) },
    // )

    "Filemoon" -> VideoServer(
        name = "Filemoon",
        hosts = listOf("filemoon.to", "filemoon.sx", "filemoon.ps", "filemoon.eu", "nzn3.org"),
        extractor = { url -> FilemoonExtractor(source.client).videosFromUrl(url) },
    )

    // "JWPlayer" -> VideoServer(
    //     name = "JWPlayer",
    //     hosts = emptyList(), // Generic
    //     extractor = { url -> JWplayerExtractor(source.client).videosFromUrl(url) },
    // )

    "Okru" -> VideoServer(
        name = "Okru",
        hosts = listOf("ok.ru", "odnoklassniki.ru"),
        extractor = { url -> OkruExtractor(source.client).videosFromUrl(url) },
    )

    "Mymail" -> VideoServer(
        name = "Mymail",
        hosts = listOf("my.mail.ru"),
        extractor = { url -> MymailExtractor(source.client).videosFromUrl(url) },
    )

    "Dood" -> VideoServer(
        name = "Dood",
        hosts = listOf(
            "dood.watch", "doodstream.com", "dood.to", "dood.so", "dood.cx", "dood.la", "dood.ws",
            "dood.sh", "doodstream.co", "dood.pm", "dood.wf", "dood.re", "dood.yt", "dooood.com",
            "dood.stream", "ds2play.com", "doods.pro", "ds2video.com", "d0o0d.com", "do0od.com",
            "d0000d.com", "d000d.com", "dood.li", "dood.work", "dooodster.com", "vidply.com",
            "all3do.com", "do7go.com", "doodcdn.io", "doply.net", "vide0.net", "vvide0.com",
            "d-s.io", "dsvplay.com", "myvidplay.com", "playmogo.com",
        ),
        extractor = { url -> DoodExtractor(source.client).videosFromUrl(url) },
    )

    else -> null
}
