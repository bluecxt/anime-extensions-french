package eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto

import kotlinx.serialization.Serializable

@Serializable
data class SearchItemDto(
    val title: String,
    val slug: String,
    val mediaType: String,
    val posterPath: String? = null,
)
