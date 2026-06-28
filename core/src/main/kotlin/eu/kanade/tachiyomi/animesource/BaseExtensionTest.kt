package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import fr.bluecxt.core.Source
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// Rendre la classe abstraite pour qu'elle serve uniquement de modèle
abstract class BaseExtensionTest {

    // La classe enfant devra fournir son instance de source (ex: AniZen, Anikku)
    abstract val source: AnimeSource

    @BeforeEach
    fun setUpMocks() {
        io.mockk.mockkObject(SAnime.Companion)
        io.mockk.every { SAnime.create() } answers { eu.kanade.tachiyomi.animesource.model.SAnimeImpl() }

        io.mockk.mockkObject(SEpisode.Companion)
        io.mockk.every { SEpisode.create() } answers { eu.kanade.tachiyomi.animesource.model.SEpisodeImpl() }
    }

    @Test // Indispensable pour JUnit 5
    fun runGlobalIntegrationTest() = runBlocking {
        println("--- DÉBUT DU TEST D'INTÉGRATION : ${source.name} ---")

        val src = source as Source

        // 1. Étape Populaire / Accueil
        println("[1/5] Requête : Animés populaires...")
        val popularPage = src.getPopularAnime(1)

        assertFalse(popularPage.animes.isEmpty(), "Le catalogue populaire est vide.")
        val firstAnime = popularPage.animes.first()
        println("-> Succès. Premier animé détecté : ${firstAnime.title}")

        // 2. Étape Détails
        println("[2/5] Requête : Détails de l'animé...")
        val details = src.getAnimeDetails(firstAnime)

        assertNotNull(details.description, "La description ne doit pas être nulle.")
        println("-> Succès. Description : ${details.description}")

        // 3. Étape Liste des Épisodes
        println("[3/5] Requête : Liste des épisodes...")
        val episodes = src.getEpisodeList(firstAnime)

        assertFalse(episodes.isEmpty(), "Aucun épisode trouvé.")
        val firstEpisode = episodes.first()
        println("-> Succès. Épisode cible : ${firstEpisode.name}")

        // 4. Étape Liste des Lecteurs (Hosters)
        println("[4/5] Requête : Liste des lecteurs (Hosters)...")
        val hosters = src.getHosterList(firstEpisode)
        assertFalse(hosters.isEmpty(), "Aucun lecteur trouvé.")
        val firstHoster = hosters.first()
        println("-> Succès. Lecteur cible : ${firstHoster.hosterName}")

        // 5. Étape Liste des Vidéos (Tachiyomi Anime fusionne parfois Hoster et Video)
        println("[5/5] Requête : Extraction des liens de streaming...")
        val videos = src.getVideoList(firstHoster)

        assertFalse(videos.isEmpty(), "Le décodage n'a retourné aucun lien vidéo.")

        // Validation du lien final
        val mainVideo = videos.first()
        println("-> Succès. Serveur : ${mainVideo.videoTitle} | URL : ${mainVideo.videoUrl}")
        assertTrue(mainVideo.videoUrl.startsWith("http"), "L'URL de streaming extraite est invalide : ${mainVideo.videoUrl}")

        println("--- CONFIGURATION OPÉRATIONNELLE POUR ${source.name} ---")
    }
}
