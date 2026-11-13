// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.model

/**
 * Kind of content a season page holds.
 *
 * It only drives how episodes are named and prefixed: the site itself exposes everything as a
 * "season" whose label says what it is.
 */
internal enum class ContentType {
    MOVIE,
    SPECIAL,
    OAV,
    SEASON,
    ;

    /**
     * Prefix prepended to the name of every episode of this kind.
     *
     * Movies are handled separately because a work can hold several of them, so the prefix is
     * decided per episode rather than per season.
     */
    val prefix: String
        get() = when (this) {
            MOVIE -> "[Film] "
            SPECIAL -> "[Special] "
            OAV -> "[OAV] "
            SEASON -> ""
        }

    companion object {
        /** Matches a "Kai" remaster, which shares its episodes with the original season. */
        private val kaiRegex = Regex("""(?i)\bKai(\s+saison\s+\d+)?$""")

        /**
         * Deduces the content kind from a season label and, as a fallback, from its path.
         *
         * @param label season label as displayed by the site, or the work title.
         * @param path relative path of the season page.
         */
        fun from(label: String, path: String = ""): ContentType = when {
            label.contains("Film", ignoreCase = true) ||
                label.contains("Movie", ignoreCase = true) ||
                path.contains("film", ignoreCase = true) ||
                path.contains("movie", ignoreCase = true) -> MOVIE

            label.contains("Special", ignoreCase = true) ||
                label.contains("Director's Cut", ignoreCase = true) ||
                kaiRegex.containsMatchIn(label) -> SPECIAL

            label.contains("OAV", ignoreCase = true) ||
                label.contains("OVA", ignoreCase = true) ||
                path.contains("oav", ignoreCase = true) -> OAV

            else -> SEASON
        }
    }
}
