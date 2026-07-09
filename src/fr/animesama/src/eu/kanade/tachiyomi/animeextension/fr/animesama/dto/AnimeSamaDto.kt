package eu.kanade.tachiyomi.animeextension.fr.animesama.dto

import kotlinx.serialization.Serializable

@Serializable
data class UrlContent(
    val url: String,
    val titles: listOf<String>,
)