// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.site

import eu.kanade.tachiyomi.animeextension.fr.animesama.model.PlayerLink

/**
 * Resolves the `<id>` references stored by the site into playable embed URLs.
 *
 * Since November 2026 a season page no longer contains embed URLs: it only contains references,
 * resolved one by one by `/api/lien.php`. Resolving them lazily, when the user actually starts a
 * video, keeps a season listing at two requests instead of one request per link.
 */
internal class LinkResolver(
    private val callApi: suspend (token: String, id: String) -> ApiResult,
    private val freshToken: suspend (seasonPath: String, lang: String) -> String?,
) {
    /**
     * Outcome of one API call.
     *
     * The API answers `403` for an expired token, `404` for an unknown reference and `400` for a
     * malformed request; only an expired token is worth a second attempt.
     */
    internal sealed interface ApiResult {
        /** The embed URL. */
        data class Url(val value: String) : ApiResult

        /** The page token is expired or unknown. */
        data object TokenRejected : ApiResult

        /** The reference no longer exists. */
        data object Unavailable : ApiResult

        /** Network failure or unreadable answer. */
        data object Failed : ApiResult
    }

    /**
     * Resolves one link.
     *
     * @param seasonPath season the link was read from, used to renew an expired token.
     * @param link link to resolve.
     * @return the embed URL, or `null` when the link cannot be played anymore.
     */
    suspend fun resolve(seasonPath: String, link: PlayerLink): String? {
        if (link.directUrl.isNotBlank()) return link.directUrl
        if (link.id.isBlank()) return null

        if (link.token.isNotBlank()) {
            when (val result = callApi(link.token, link.id)) {
                is ApiResult.Url -> return result.value
                ApiResult.Unavailable, ApiResult.Failed -> return null
                ApiResult.TokenRejected -> Unit
            }
        }

        // The token stored with the episode is page-scoped and time-bound: reload the season
        // page once to get a fresh one before giving up.
        val token = freshToken(seasonPath, link.lang) ?: return null
        return when (val result = callApi(token, link.id)) {
            is ApiResult.Url -> result.value
            else -> null
        }
    }
}
