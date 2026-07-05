package fr.bluecxt.core.test

import fr.bluecxt.core.model.AnimeExtensionService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

abstract class BaseExtensionTest(
    private val service: AnimeExtensionService,
) {
    @Test
    fun testExtensionService() = runBlocking {
        println("--- DÉBUT DU TEST DE SERVICE ---")

        // 1. Popular
        val popular = service.getPopularAnime(1)
        assertFalse(popular.isEmpty(), "Le catalogue populaire est vide.")
        var testAnime = popular.first()
        println("-> Animé (Popular) : ${testAnime.title}")

        // 1b. Latest (if supported)
        if (service.supportsLatest) {
            println("--- TEST LATEST ---")
            val latest = service.getLatestUpdates(1)
            assertFalse(latest.isEmpty(), "Le catalogue latest est vide.")
            testAnime = latest.first()
            println("-> Animé (Latest) : ${testAnime.title}")
        }

        // 2. Details
        val details = service.getAnimeDetails(testAnime)
        assertNotNull(details.description, "La description ne doit pas être nulle.")
        println("-> Description : ${details.description}")

        // 3. Episodes
        val episodes = service.getEpisodeList(testAnime)
        assertFalse(episodes.isEmpty(), "Aucun épisode trouvé.")
        val firstEpisode = episodes.first()
        println("-> Épisode : ${firstEpisode.name}")

        // 4. Hosters
        val hosters = service.getHosterList(firstEpisode)
        assertFalse(hosters.isEmpty(), "Aucun lecteur trouvé.")
        val firstHoster = hosters.first()
        println("-> Lecteur : ${firstHoster.name}")

        // 5. Videos
        val videos = service.getVideoList(firstHoster)
        assertFalse(videos.isEmpty(), "Aucune vidéo trouvée.")
    }
}
