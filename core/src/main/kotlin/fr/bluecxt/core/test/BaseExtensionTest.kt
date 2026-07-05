package fr.bluecxt.core.test

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import fr.bluecxt.core.model.AnimeExtensionService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

abstract class BaseExtensionTest(
    private val service: AnimeExtensionService,
    private val searchQuery: String? = null,
) {
    @BeforeEach
    fun setUpMocks() {
        io.mockk.mockkStatic(android.util.Log::class)
        io.mockk.every { android.util.Log.d(any(), any()) } returns 0
        io.mockk.every { android.util.Log.d(any(), any(), any()) } returns 0
        io.mockk.every { android.util.Log.i(any(), any()) } returns 0
        io.mockk.every { android.util.Log.w(any(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.e(any(), any()) } returns 0
        io.mockk.every { android.util.Log.e(any(), any(), any()) } returns 0

        io.mockk.mockkObject(SAnime.Companion)
        io.mockk.every { SAnime.create() } answers { eu.kanade.tachiyomi.animesource.model.SAnimeImpl() }

        io.mockk.mockkObject(SEpisode.Companion)
        io.mockk.every { SEpisode.create() } answers { eu.kanade.tachiyomi.animesource.model.SEpisodeImpl() }
    }

    @Test
    fun testExtensionService() = runBlocking {
        println("--- DÉBUT DU TEST DE SERVICE ---")

        // 1. Popular
        val popular = service.getPopularAnime(1)
        assertFalse(popular.animes.isEmpty(), "Le catalogue populaire est vide.")
        var testAnime = popular.animes.first()
        println("-> Animé (Popular) : ${testAnime.title}")

        // 1b. Latest (if supported)
        if (service.supportsLatest) {
            println("--- TEST LATEST ---")
            val latest = service.getLatestUpdates(1)
            assertFalse(latest.animes.isEmpty(), "Le catalogue latest est vide.")
            testAnime = latest.animes.first()
            println("-> Animé (Latest) : ${testAnime.title}")
        }

        // 1c. Search (if searchQuery is not null)
        if (searchQuery != null) {
            println("--- TEST RECHERCHE ---")
            val searchResult = service.getSearchAnime(1, searchQuery, service.getFilterList())
            assertFalse(searchResult.animes.isEmpty(), "La recherche pour '$searchQuery' a renvoyé un résultat vide.")
            testAnime = searchResult.animes.first()
            println("-> Animé (Search) : ${testAnime.title}")
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
        println("-> Lecteur : ${firstHoster.hosterName}")

        // 5. Videos
        val videos = service.getVideoList(firstHoster)
        assertFalse(videos.isEmpty(), "Aucune vidéo trouvée.")
    }
}
