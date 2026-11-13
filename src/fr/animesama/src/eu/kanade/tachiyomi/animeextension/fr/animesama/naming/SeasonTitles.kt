// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.naming

import fr.bluecxt.core.utils.normalize

/**
 * Builds the title displayed for a season entry.
 *
 * The site writes season labels in an irregular way (`Saison 1`, `Saison 2 Partie 2`,
 * `Saison 1 Remake 2026`, `Film`), so the label is only used as a suffix when it adds
 * information: a work whose label already contains the title keeps it as is, and "Saison 1" is
 * dropped because it is the default season.
 */
internal object SeasonTitles {
    private val seasonRegex = Regex("""(?i)^Saison\s*\d+.*""")
    private val seasonReplaceRegex = Regex("""(?i)\s*Saison\s*""")
    private val partReplaceRegex = Regex("""(?i)Partie\s*(\d+)""")

    /** Matches the episode range the site appends to some season labels. */
    private val episodeRangeRegex = Regex("""\s*\[[^\]]*[ÉE]pisode[^\]]*]\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Composes the display title of a season.
     *
     * @param seriesTitle title of the work.
     * @param rawSeasonName season label as written by the site.
     * @param titles every title known for the work, used to avoid duplicating the title.
     */
    fun format(seriesTitle: String, rawSeasonName: String, titles: Set<String>): String {
        val cleanSeason = rawSeasonName.trim().replace(episodeRangeRegex, "").trim()
        if (cleanSeason.isEmpty()) return seriesTitle

        val normalizedSeason = cleanSeason.normalize()
        val alreadyQualified = titles.any { it.isNotBlank() && it.normalize() in normalizedSeason }
        if (alreadyQualified) return cleanSeason

        if (cleanSeason.equals(FIRST_SEASON, ignoreCase = true)) return seriesTitle

        if (cleanSeason.matches(seasonRegex)) {
            val shortSeason = cleanSeason
                .replace(seasonReplaceRegex, " ")
                .replace(partReplaceRegex, "Part $1")
                .trim()
            return "$seriesTitle $shortSeason"
        }

        return "$seriesTitle $cleanSeason"
    }

    /** Label of the season that does not need to be repeated in the title. */
    private const val FIRST_SEASON = "Saison 1"
}
