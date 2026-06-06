package fr.bluecxt.core.model

data class VideoServer(
    val name: String,
    val hosts: List<String>,
    val extractor: suspend (String) -> List<ExtractedSource>,
) {
    fun matches(url: String): Boolean = hosts.any { host ->
        when {
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
