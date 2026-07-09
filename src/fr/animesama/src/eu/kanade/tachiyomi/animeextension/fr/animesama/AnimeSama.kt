package eu.kanade.tachiyomi.animeextension.fr.animesama

import fr.bluecxt.core.ANIMESAMA_LOG
import fr.bluecxt.core.CommonPreferences

class AnimeSama :
    Source(),
    CommonPreferences {

    override val name = "Anime-Sama"

    override val defaultBaseUrl = "https://anime-sama.to"
    override val supportedServers = listOf("Sibnet", "Sendvid", "Vidmoly", "Embed4me", "Minochinos")
    override val supportedVoices = arrayOf("VOSTFR", "VF", "VA")
    override val lang = "fr"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        
    }
    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = response.request.header("X-Page") ?: "1"
        return parseCatalogue(response.asJsoup(), page)
    }


    }