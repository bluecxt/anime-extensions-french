// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.movix

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class MovixFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        Movix(),
        MovixAnime(),
    )
}
