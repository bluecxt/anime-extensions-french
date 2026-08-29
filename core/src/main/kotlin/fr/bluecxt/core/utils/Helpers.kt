// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.utils

import android.app.Application
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.ContentUnavailableException
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.ExtractionException
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Safely extracts the relative path from an element's href attribute.
 * Uses abs:href to ensure a full URL is parsed, then returns only the encoded path.
 */
fun Element.safeRelativePath(): String? {
    val url = this.attr("abs:href").toHttpUrlOrNull() ?: return null
    val query = url.encodedQuery
    return if (query.isNullOrBlank()) url.encodedPath else "${url.encodedPath}?$query"
}

/**
 * Resolves a URL (relative or absolute) against a base URL and returns the cleaned relative path.
 * Uses OkHttp's resolve engine to handle normalization and special characters.
 */
fun String.safeRelativePath(base: String): String? {
    val url = base.toHttpUrlOrNull()?.resolve(this) ?: return null
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
        builder["User-Agent"] = DEFAULT_USER_AGENT
    }

    if (this.headers?.get("Referer") == null) {
        builder["Referer"] = "$baseUrl/"
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
 * Awaits response and verifies status:
 * - Throws ContentUnavailableException on 404 or 410 (dead/unavailable content)
 * - Throws ExtractionException on non-2xx status codes
 * Automatically closes response on failure.
 */
suspend fun Call.awaitSuccessOrUnavailable(url: String = ""): Response {
    val response = this.await()
    if (response.code == 404 || response.code == 410) {
        response.close()
        throw ContentUnavailableException("Video unavailable (${response.code}) $url".trim())
    }
    if (!response.isSuccessful) {
        val errCode = response.code
        val errMsg = response.message
        response.close()
        throw ExtractionException("HTTP $errCode ($errMsg) for $url".trim())
    }
    return response
}

/**
 * Convert a response to jsoup document with handling in the different error used in extractors
 */
fun Response.toDoc(url: String): Document = this.use { res ->
    if (res.code == 404 || res.code == 410) {
        throw ContentUnavailableException("Video unavailable (${res.code}) $url".trim())
    }
    if (!res.isSuccessful) {
        throw ExtractionException("HTTP ${res.code} (${res.message}) for $url".trim())
    }
    res.asJsoup()
}

/**
 * Parses a status string into SAnime status integer constants.
 */
fun String.parseStatus(): Int = when (this.trim().lowercase()) {
    "en cours", "ongoing", "en-cours", "releasing", "airing", "en diffusion", "en cours de diffusion", "broadcasting" -> SAnime.ONGOING
    "terminé", "termine", "completed", "end", "finished", "fini", "complete", "complété" -> SAnime.COMPLETED
    "licencié", "licencie", "licensed" -> SAnime.LICENSED
    "publishing finished" -> SAnime.PUBLISHING_FINISHED
    "annulé", "annule", "canceled", "cancelled", "abandonné", "abandonne" -> SAnime.CANCELLED
    "en pause", "on-hold", "on hold", "on_hiatus", "hiatus", "en attente", "paused" -> SAnime.ON_HIATUS
    else -> SAnime.UNKNOWN
}
