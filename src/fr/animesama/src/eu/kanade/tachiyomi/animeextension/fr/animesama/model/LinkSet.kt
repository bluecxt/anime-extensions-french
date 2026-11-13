// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.model

import kotlinx.serialization.Serializable

/**
 * One playable link, attached to a language and to the hoster that serves it.
 *
 * The site no longer stores embed URLs: it stores a reference `<id>` that
 * `/api/lien.php` resolves on demand. Some older rows still carry a full URL, kept in
 * [directUrl].
 *
 * @property lang language slug the link belongs to (`vostfr`, `vf`…).
 * @property host hostname announced by the site, for example `ansembed.net`.
 * @property server router name of the extractor that handles [host], for example `Vidmoly`.
 * @property id reference resolved by the API, empty for a [directUrl] link.
 * @property directUrl full embed URL, empty for a referenced link.
 * @property token page token required by the API when the link was read.
 */
@Serializable
data class PlayerLink(
    val lang: String,
    val host: String,
    val server: String,
    val id: String = "",
    val directUrl: String = "",
    val token: String = "",
)

/**
 * Player as stored by the previous version of the extension.
 *
 * That version resolved the embed URL while listing, so an episode holds plain URLs instead of
 * page references.
 *
 * @property url resolved embed URL.
 * @property lang language slug the link belongs to.
 */
@Serializable
internal data class StoredPlayer(
    val url: String,
    val lang: String,
)

/**
 * A group of links sharing one season page, stored in `internalData` of an [Hoster].
 *
 * @property seasonPath season root the links were read from, needed to renew an expired token.
 * @property links links exposed by the hoster.
 */
@Serializable
data class LinkSet(
    val seasonPath: String,
    val links: List<PlayerLink> = emptyList(),
)
