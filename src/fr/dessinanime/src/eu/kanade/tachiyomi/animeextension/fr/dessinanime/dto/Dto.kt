// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto

import kotlinx.serialization.Serializable

@Serializable
data class SearchItemDto(
    val title: String,
    val slug: String,
    val mediaType: String,
    val posterPath: String? = null,
)

@Serializable
data class CatalogueDto(
    val id: Int,
    val slug: String,
    val title: String,
    val releaseYear: Int,
    val posterPath: String,
    val mediaType: String,
)
