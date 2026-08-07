// Copyright 2024 The Aniyomi Open Source Project
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.frenchmanga

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class FrenchMangaFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        FrenchManga(),
        VoirAnimeHomes(),
    )
}
