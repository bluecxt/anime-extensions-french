package fr.bluecxt.core.model

data class Episode(
    val name: String,
    val url: String,
    val episodeNumber: Float,
    val dateUpload: Long = 0L,
)
