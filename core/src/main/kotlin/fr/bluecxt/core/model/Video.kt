package fr.bluecxt.core.model

import okhttp3.Headers

data class Video(
    val url: String,
    val title: String,
    val headers: Headers? = null,
)
