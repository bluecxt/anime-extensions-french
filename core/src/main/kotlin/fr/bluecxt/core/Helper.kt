package fr.bluecxt.core

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.model.ExtractedSource
import keiyoushi.utils.addEditTextPreference
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element

/**
 * Safely extracts the relative path from an element's href attribute.
 * Uses abs:href to ensure a full URL is parsed, then returns only the encoded path.
 */
fun Element.safeRelativePath(): String = this.attr("abs:href").toHttpUrlOrNull()?.encodedPath ?: ""

/**
 * Resolves a URL (relative or absolute) against a base URL and returns the cleaned relative path.
 * Uses OkHttp's resolve engine to handle normalization and special characters.
 */
fun String.safeRelativePath(base: String): String = base.toHttpUrlOrNull()?.resolve(this)?.encodedPath ?: ""

/**
 * Returns a new Video instance with secured headers.
 * Injects the global DEFAULT_USER_AGENT and sets the Referer to baseUrl if missing.
 * Useful for bypassing 403 Forbidden errors on various hosters.
 */
fun Video.withDefaultHeaders(baseUrl: String): Video {

    val builder = this.headers?.newBuilder() ?: Headers.Builder()

    builder.set("User-Agent", DEFAULT_USER_AGENT)

    if (this.headers?.get("Referer") == null) {
        builder.set("Referer", "$baseUrl/")
    }

    return this.copy(headers = builder.build())
}

/**
 * Adds an EditTextPreference for managing the extension's base URL.
 * Automatically cleans the input (trim and trailing slash removal).
 * If the field is cleared, it resets the preference to the default URL.
 */
fun PreferenceScreen.addBaseUrlPreference(
    preferences: SharedPreferences,
    defaultUrl: String,
    title: String = "URL de base",
    key: String = "base_url_pref",
    summary: String? = null,
    onComplete: (String) -> Unit = {},
) {
    val currentUrl = preferences.getString(key, defaultUrl) ?: defaultUrl

    addEditTextPreference(
        key = key,
        title = title,
        summary = if (summary.isNullOrBlank()) currentUrl else summary,
        default = defaultUrl,
        getSummary = { newValue ->
            if (summary.isNullOrBlank()) {
                if (newValue.isBlank()) defaultUrl else newValue
            } else {
                summary
            }
        },
        onChange = { _, newValue ->
            val cleanUrl = newValue.trim().removeSuffix("/")

            if (newValue.isBlank()) {
                // Reset à la valeur par défaut
                preferences.edit().remove(key).apply()
                onComplete(defaultUrl)
                true
            } else if (cleanUrl.isNotEmpty()) {
                // Enregistrement de la nouvelle URL nettoyée
                preferences.edit().putString(key, cleanUrl).apply()
                onComplete(cleanUrl)
                true
            } else {
                false
            }
        },
    )
}

fun List<Video>.toExtractedSources(headers: Headers? = null): List<ExtractedSource> = this.map { video ->
    val builderHeaders = headers?.newBuilder() ?: Headers.Builder()

    ExtractedSource(
        url = video.videoUrl,
        quality = video.videoTitle,
        headers = builderHeaders,
        subtitleTracks = video.subtitleTracks,
        audioTracks = video.audioTracks,
    )
}
