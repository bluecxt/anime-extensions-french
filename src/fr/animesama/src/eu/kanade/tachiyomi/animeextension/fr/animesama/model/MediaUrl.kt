// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.model

import kotlinx.serialization.Serializable

/**
 * Payload stored in `SAnime.url` and `SEpisode.url`.
 *
 * The JSON keeps the relative path, the known titles, the season label and the language, so
 * that returning to an entry never needs an extra request to know what it points at.
 *
 * @property url relative path of the work or season, for example
 *   `/catalogue/07-ghost/saison1/vostfr`.
 * @property titles every title known for the work, used for the TVDB match.
 * @property season season label as displayed by the site (`Saison 1`, `Film`…), or `null`
 *   when the entry points at a hub listing several seasons.
 * @property lang language slug of a season entry, or `null` for a hub entry.
 */
@Serializable
data class MediaUrl(
    val url: String,
    val titles: Set<String> = emptySet(),
    val season: String? = null,
    val lang: String? = null,
)
