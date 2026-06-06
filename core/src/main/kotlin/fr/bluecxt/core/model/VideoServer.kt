package fr.bluecxt.core.model

import android.util.Log

data class VideoServer(
    val name: String,
    val hosts: List<String>,
    val extractor: suspend (String) -> List<ExtractedSource>,
) {
    fun matches(url: String): Boolean = hosts.any { host ->
        Log.d("VideoServer", "url = $url")
        when {
            host.isEmpty() -> false

            host.startsWith("regex:") -> {
                runCatching {
                    host.substring(6).toRegex(RegexOption.IGNORE_CASE).containsMatchIn(url)
                }.getOrDefault(false)
            }

            host.contains("*") -> {
                runCatching {
                    host.replace(".", "\\.").replace("*", ".*").toRegex(RegexOption.IGNORE_CASE).containsMatchIn(url)
                }.getOrDefault(false)
            }

            else -> url.contains(host, ignoreCase = true)
        }
    }
}
