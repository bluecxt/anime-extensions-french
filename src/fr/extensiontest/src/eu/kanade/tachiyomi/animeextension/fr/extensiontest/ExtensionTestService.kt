package eu.kanade.tachiyomi.animeextension.fr.extensiontest

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.OkHttpClient

class ExtensionTestService(
    private val client: OkHttpClient,
    private val extractVideos: suspend (playerUrl: String) -> List<Video>,
) {
    suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(
        listOf(
            SAnime.create().apply {
                title = "Test Extracteurs"
                url = "/test-extractors"
                thumbnail_url = "https://github.com/bluecxt/anime-extensions-french/raw/refs/heads/main/repo_logo.svg"
            },
        ),
        false,
    )

    suspend fun getAnimeDetails(anime: SAnime): SAnime = anime.apply {
        description = buildString {
            val char1 = "✦"
            val char2 = "⫻"
            append("Uqload $char1 802p $char2 23.98fps")
            append("\n")
            append("Sendvid $char1 1080p $char2 60fps")
            append("\n")
            append("Sibnet $char1 720p $char2 60fps")
            append("\n")
            append("Vidmoly $char1 1080p")
            append("\n")
            append("Vk $char1 60fps")
        }
    }

    suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Episode Test - Extracteurs"
            url = "/episode-test"
            episode_number = 1f
        },
    )

    suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterName = "Tests", internalData = "all_tests"))

    suspend fun getVideoList(hoster: Hoster, uqloadExtractorManual: suspend (url: String) -> List<Video>): List<Video> {
        val testLinks = listOf(
            "Vidoza" to "https://videzz.net/embed-y34qudiino2n.html",
            "Uqload" to "https://uqload.is/embed-hkhff5k2pjp7.html",
            "UqloadManual" to "https://uqload.is/embed-hkhff5k2pjp7.html",
            "Streamtape" to "https://streamtape.com/e/4RjVoMZ0zWcKQDb/",
            "Lulu" to "https://luluvdo.com/e/5q4zzr3cbn7k",
            "Vidara" to "https://vidara.to/e/E0PwlcdTTVuTZ",
            "Cda" to "https://ebd.cda.pl/1055x594/27664708b6",
            "Sibnet" to "https://video.sibnet.ru/shell.php?videoid=1028952",
            "Voe" to "https://jessicayeahcatch.com/e/jp2cdfcagow2",
            "Vidnest" to "https://vidnest.io/embed-5xsbjc4ohpyo.html",
            "Doodstream" to "https://dood.yt/e/aorzlvboafi6",
            "Mp4upload" to "https://www.mp4upload.com/y8xh3ip7qxey",
            "Savefiles" to "https://bigwarp.io/e/q8554e8tzewc.html",
            "Filemoon" to "https://rupertisdivingintoocean.com/eyi/qgdk9knxn0d3",
            "Okru" to "https://ok.ru/videoembed/4511946705484",
            "Veev" to "https://veev.to/e/2EvjtvNM7IF2vqAWgGH8ug7pAb9eIlagSuKIInw",
            "Vidguard" to "https://listeamed.net/e/JzkPxzX4NpAObyd",
            "Lycoris" to "https://www.lycoris.cafe/embed?id=181447&episode=10",
            "Pixeldrain" to "https://pixeldrain.com/u/rkHjhTWZ?embed",
            "Abstream" to "https://abstream.to/embed/blshnz6jt14e",
            "Streamup" to "https://strmup.to/c74b4341041c1",
            "GoogleDrive" to "https://drive.usercontent.google.com/download?id=1kp9oGevIWTAXmymgvRRB29qUvINU-3Qs&confirm=t&uuid=a45d4b4b-b284-4e14-836f-9a78ce980c1c",
            "Rumble" to "https://rumble.com/v716bwo-frixttwakrnin05.html",
            "Abyss" to "https://abysscdn.com/?v=Q1a8w6rjA",
            "Buzz" to "https://buzzheavier.com/hg1gtctkofos",
            "Earnvid" to "https://dhtpre.com/embed/grins3nycf6t",
            "Hqq" to "https://hqq.tv/e/NEYvTktac2pMOTFtQTNjNUhHUy9EUT09",
            "Dailymotion" to "https://www.dailymotion.com/embed/video/x9ybkyu",
            "Sendvid" to "https://sendvid.com/embed/nzhbbd7k",
        )

        // Simple loop to fetch videos one by one or asynchronously
        return testLinks.flatMap { (serverName, url) ->
            println("Testing $serverName with URL: $url")
            if (serverName == "UqloadManual") {
                uqloadExtractorManual(url)
            } else {
                extractVideos(url)
            }
        }
    }
}
