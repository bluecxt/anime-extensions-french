package eu.kanade.tachiyomi.animeextension.fr.extensiontest

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExtensionTestTest {

    @BeforeEach
    fun setUpMocks() {
        io.mockk.mockkObject(SAnime.Companion)
        io.mockk.every { SAnime.create() } answers { eu.kanade.tachiyomi.animesource.model.SAnimeImpl() }

        io.mockk.mockkObject(SEpisode.Companion)
        io.mockk.every { SEpisode.create() } answers { eu.kanade.tachiyomi.animesource.model.SEpisodeImpl() }
    }

    @Test
    fun testExtensionService() = runBlocking {
        val client = OkHttpClient()
        val service = ExtensionTestService(
            client = client,
            extractVideos = { url ->
                // Simulated video extraction for tests
                listOf(Video(videoUrl = url, videoTitle = "Test Video"))
            },
        )

        println("--- DÉBUT DU TEST DE SERVICE ---")

        // 1. Popular
        val popular = service.getPopularAnime(1)
        assertFalse(popular.animes.isEmpty(), "Le catalogue populaire est vide.")
        val firstAnime = popular.animes.first()
        println("-> Animé : ${firstAnime.title}")

        // 2. Details
        val details = service.getAnimeDetails(firstAnime)
        assertNotNull(details.description, "La description ne doit pas être nulle.")
        println("-> Description : ${details.description}")

        // 3. Episodes
        val episodes = service.getEpisodeList(firstAnime)
        assertFalse(episodes.isEmpty(), "Aucun épisode trouvé.")
        val firstEpisode = episodes.first()
        println("-> Épisode : ${firstEpisode.name}")

        // 4. Hosters
        val hosters = service.getHosterList(firstEpisode)
        assertFalse(hosters.isEmpty(), "Aucun lecteur trouvé.")
        val firstHoster = hosters.first()
        println("-> Lecteur : ${firstHoster.hosterName}")

        // 5. Videos
        val videos = service.getVideoList(firstHoster) { url ->
            listOf(Video(videoUrl = url, videoTitle = "Uqload Manual"))
        }
        assertFalse(videos.isEmpty(), "Aucune vidéo trouvée.")
        println("-> Liens vidéos extraits : ${videos.size}")
        for (video in videos) {
            println("   * ${video.videoTitle} : ${video.videoUrl}")
        }
    }
}
