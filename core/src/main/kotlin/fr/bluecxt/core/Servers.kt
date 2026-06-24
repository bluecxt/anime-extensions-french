package fr.bluecxt.core

import fr.bluecxt.core.extractors.DoodExtractor
import fr.bluecxt.core.extractors.Embed4meExtractor
import fr.bluecxt.core.extractors.FilemoonExtractor
import fr.bluecxt.core.extractors.GoogleDriveExtractor
import fr.bluecxt.core.extractors.LuluExtractor
import fr.bluecxt.core.extractors.MinochinosExtractor
import fr.bluecxt.core.extractors.MymailExtractor
import fr.bluecxt.core.extractors.OkruExtractor
import fr.bluecxt.core.extractors.SendvidExtractor
import fr.bluecxt.core.extractors.SibnetExtractor
import fr.bluecxt.core.extractors.StreamixExtractor
import fr.bluecxt.core.extractors.UqloadExtractor
import fr.bluecxt.core.extractors.VidaraExtractor
import fr.bluecxt.core.extractors.VidmolyExtractor
import fr.bluecxt.core.extractors.VidoExtractor
import fr.bluecxt.core.extractors.VidozaExtractor
import fr.bluecxt.core.extractors.VoeExtractor
import fr.bluecxt.core.extractors.VudeoExtractor
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
    "Vidara",
    "Streamix",
    "Voe",
    "Vidoza",
    "Lulu",
    "Uqload",
    "Vudeo",
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
        hosts = listOf("drive.usercontent.google.com"),
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

    "Vidara" -> VideoServer(
        name = "Vidara",
        hosts = listOf("vidara.so", "vidara.to"),
        extractor = { url -> VidaraExtractor(source.client).videosFromUrl(url) },
    )

    "Streamix" -> VideoServer(
        name = "Streamix",
        hosts = listOf("streamix.so", "stmix.io"),
        extractor = { url -> StreamixExtractor(source.client).videosFromUrl(url) },
    )

    "Voe" -> VideoServer(
        name = "Voe",
        hosts = listOf(
            "voe.sx", "voe-unblock.com", "voe-unblock.net", "voeunblock.com", "un-block-voe.net",
            "voeunbl0ck.com", "voeunblck.com", "voeunblk.com", "voe-un-block.com", "jonathansociallike.com",
            "voeun-block.net", "v-o-e-unblock.com", "edwardarriveoften.com", "nathanfromsubject.com",
            "audaciousdefaulthouse.com", "launchreliantcleaverriver.com", "kennethofficialitem.com",
            "reputationsheriffkennethsand.com", "fittingcentermondaysunday.com", "lukecomparetwo.com",
            "housecardsummerbutton.com", "fraudclatterflyingcar.com", "wolfdyslectic.com",
            "bigclatterhomesguideservice.com", "uptodatefinishconferenceroom.com", "jayservicestuff.com",
            "realfinanceblogcenter.com", "tinycat-voe-fashion.com", "paulkitchendark.com",
            "metagnathtuggers.com", "gamoneinterrupted.com", "chromotypic.com", "crownmakermacaronicism.com",
            "generatesnitrosate.com", "yodelswartlike.com", "figeterpiazine.com", "strawberriesporail.com",
            "valeronevijao.com", "timberwoodanotia.com", "apinchcaseation.com", "nectareousoverelate.com",
            "nonesnanking.com", "smoki.cc", "chuckle-tube.com", "goofy-banana.com",
            "voeunblock1.com", "voeunblock2.com", "voeunblock3.com", "voeunblock4.com", "voeunblock5.com",
            "voeunblock6.com", "voeunblock7.com", "voeunblock8.com", "voeunblock9.com", "voeunblock10.com",
            "jessicayeahcatch.com", "kathyinformationwhether.com",
        ),
        extractor = { url -> VoeExtractor(source.client).videosFromUrl(url) },
    )

    "Vidoza" -> VideoServer(
        name = "Vidoza",
        hosts = listOf("vidoza.net", "vidoza.co", "videzz.net"),
        extractor = { url -> VidozaExtractor(source.client).videosFromUrl(url) },
    )

    "Lulu" -> VideoServer(
        name = "Lulu",
        hosts = listOf("luluvdo.com", "lulu.st", "luluvid.com", "luluvdoo.com"),
        extractor = { url -> LuluExtractor(source.client).videosFromUrl(url) },
    )

    "Uqload" -> VideoServer(
        name = "Uqload",
        hosts = listOf("uqload.com", "uqload.co", "uqload.to", "uqload.is", "uqload.bz"),
        extractor = { url -> UqloadExtractor(source.client).videosFromUrl(url) },
    )

    "Vudeo" -> VideoServer(
        name = "Vudeo",
        hosts = listOf("vudeo.co"),
        extractor = { url -> VudeoExtractor(source.client).videosFromUrl(url) },
    )

    "Vido" -> VideoServer(
        name = "Vido",
        hosts = listOf("Vido.*"),
        extractor = { url -> VidoExtractor(source.client).videosFromUrl(url) },
    )

    else -> null
}
