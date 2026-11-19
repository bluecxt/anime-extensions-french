// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.model

import android.util.Log

/** Longueur du préfixe "https:" retiré pour isoler l'hôte. */
private const val SCHEME_PREFIX_LENGTH = 6

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
                    host.substring(SCHEME_PREFIX_LENGTH).toRegex(RegexOption.IGNORE_CASE).containsMatchIn(url)
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
