// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.voiranime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnimeResult(
    @SerialName("post_title") val postTitle: String,
    @SerialName("post_image") val postImage: String,
    @SerialName("post_link") val postLink: String,
)

@Serializable
data class SearchResponse(
    val all: List<AnimeResult>,
)
