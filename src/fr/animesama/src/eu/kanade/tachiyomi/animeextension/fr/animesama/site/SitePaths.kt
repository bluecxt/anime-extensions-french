// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.site

/**
 * Path conventions of the anime-sama URL scheme.
 *
 * The site uses four path shapes:
 *  - `/catalogue` — the paginated catalogue;
 *  - `/catalogue/<work>` — the work page ("hub"), carrying metadata and the season list;
 *  - `/catalogue/<work>/<season>` — a season, before a language is selected;
 *  - `/catalogue/<work>/<season>/<language>` — the playable page of one season in one language.
 *
 * Season slugs are free-form (`saison1`, `saison1hs`, `film`, `oav`…), so the language is
 * always detected from the last segment against a fixed list instead of being parsed.
 */
internal object SitePaths {
    /** Language slugs the site accepts as the last segment of a season page. */
    private val languageSlugs = setOf(
        "vostfr",
        "vf",
        "vf1",
        "vf2",
        "va",
        "vasta",
        "vosta",
        "vcn",
        "vqc",
        "vj",
        "vkr",
        "var",
    )

    /**
     * Normalises a path to a leading slash and no trailing slash.
     *
     * @param path absolute path, possibly with a trailing slash.
     * @return the normalised path, for example `/catalogue/07-ghost/saison1`.
     */
    fun normalize(path: String): String {
        val trimmed = path.trim().trimEnd('/')
        return when {
            trimmed.isEmpty() -> "/"
            trimmed.startsWith("/") -> trimmed
            else -> "/$trimmed"
        }
    }

    /**
     * Splits a path into its non-empty segments.
     *
     * @param path absolute path.
     */
    fun segments(path: String): List<String> = normalize(path).trim('/').split('/').filter { it.isNotEmpty() }

    /**
     * Returns the language slug carried by a season path.
     *
     * @param path absolute path of a season, with or without its language suffix.
     * @return the slug (`vostfr`, `vf`…), or `null` when the last segment is a season slug.
     */
    fun language(path: String): String? = segments(path).lastOrNull()?.takeIf { it in languageSlugs }

    /**
     * Removes the language suffix from a season path.
     *
     * @param path absolute path of a season page.
     * @return the season root, for example `/catalogue/07-ghost/saison1`.
     */
    fun seasonPath(path: String): String {
        val parts = segments(path)
        val withoutLanguage = if (parts.lastOrNull()?.let { it in languageSlugs } == true) {
            parts.dropLast(1)
        } else {
            parts
        }
        return "/" + withoutLanguage.joinToString("/")
    }

    /**
     * Cuts a path down to its hub form.
     *
     * @param path absolute path of any catalogue page.
     * @return `/catalogue/<work>`.
     */
    fun hubPath(path: String): String = "/" + segments(path).take(2).joinToString("/")

    /**
     * Builds the season page path for one language.
     *
     * @param seasonPath season root, for example `/catalogue/07-ghost/saison1`.
     * @param lang language slug, for example `vostfr`.
     */
    fun seasonUrl(seasonPath: String, lang: String): String = "${normalize(seasonPath)}/$lang"
}
