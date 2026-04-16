package eu.kanade.tachiyomi.animeextension.fr.frenchmanga

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class FrenchMangaFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        FrenchManga(),
        VoirAnimeHomes(),
    )
}
