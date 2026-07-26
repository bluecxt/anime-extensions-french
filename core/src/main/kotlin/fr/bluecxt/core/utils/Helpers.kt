package fr.bluecxt.core.utils

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.ContentUnavailableException
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.ExtractionException
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Document
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

/**
 * Simple builder for basic headers
 */
fun defaultHeaders(
    referer: String = "",
    userAgent: String = DEFAULT_USER_AGENT,
    origin: String = "",
    accept: String = "",
): Headers = Headers.Builder()
    .add("user-Agent", userAgent)
    .apply {
        if (!referer.isBlank()) add("Referer", referer)
        if (!origin.isBlank()) add("Origin", origin)
        if (!accept.isBlank()) add("Accept", accept)
    }.build()

/**
 * Normalize a String by putting everything in lowercase and removing all the non latin letter
 */
fun String.normalize(): String = this.lowercase().replace(Regex("""[^a-z0-9]"""), "")

/**
 * Convert a response to jsoup document with handling in the different error used in extractors
 */
fun Response.toDoc(url: String): Document = this.use { res ->
    if (res.code == 404) throw ContentUnavailableException("Video non available (404) $url")
    if (!res.isSuccessful) throw ExtractionException("failed for $url with ${res.code}: ${res.message}")
    res.asJsoup()
}
