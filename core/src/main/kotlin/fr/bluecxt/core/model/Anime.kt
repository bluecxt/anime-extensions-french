package fr.bluecxt.core.model

data class Anime(
    val title: String,
    val url: String,
    val thumbnailUrl: String? = null,
    var description: String? = null,
    var status: Int = 0,
    var genres: List<String> = emptyList(),
)
