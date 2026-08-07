// Copyright 2024 The Aniyomi Open Source Project
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
