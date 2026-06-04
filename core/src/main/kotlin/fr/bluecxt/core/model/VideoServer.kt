package fr.bluecxt.core.model

data class VideoServer(
    val name: String,
    val hosts: List<String>,
    val extractor: suspend (String) -> List<ExtractedSource>,
)
