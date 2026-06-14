package fr.bluecxt.core

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element

/**
 * Safely extracts the relative path from an element's href attribute.
 * Uses abs:href to ensure a full URL is parsed, then returns only the encoded path.
 */
fun Element.safeRelativePath(): String {
    val url = this.attr("abs:href").toHttpUrlOrNull() ?: return ""
    val query = url.encodedQuery
    return if (query.isNullOrBlank()) url.encodedPath else "${url.encodedPath}?$query"
}

/**
 * Resolves a URL (relative or absolute) against a base URL and returns the cleaned relative path.
 * Uses OkHttp's resolve engine to handle normalization and special characters.
 */
fun String.safeRelativePath(base: String): String {
    val url = base.toHttpUrlOrNull()?.resolve(this) ?: return ""
    val query = url.encodedQuery
    return if (query.isNullOrBlank()) url.encodedPath else "${url.encodedPath}?$query"
}

/**
 * Returns a new Video instance with secured headers.
 * Injects the global DEFAULT_USER_AGENT and sets the Referer to baseUrl if missing.
 * Useful for bypassing 403 Forbidden errors on various hosters.
 */
fun Video.withDefaultHeaders(baseUrl: String): Video {
    val builder = this.headers?.newBuilder() ?: Headers.Builder()

    if (this.headers?.get("User-Agent") == null) {
        builder.set("User-Agent", DEFAULT_USER_AGENT)
    }

    if (this.headers?.get("Referer") == null) {
        builder.set("Referer", "$baseUrl/")
    }

    return this.copy(headers = builder.build())
}
