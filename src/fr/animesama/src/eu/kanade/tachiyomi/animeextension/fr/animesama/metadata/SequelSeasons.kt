// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.metadata

import fr.bluecxt.core.utils.normalize

/**
 * Works the site split into their own entry while TVDB stores them as a later season.
 *
 * For some sequels the site creates a separate work and labels its only panel "Saison 1":
 * `cyberpunk-edgerunners-2` is TVDB season 2 of `Cyberpunk: Edgerunners`. Without a translation
 * the second work inherits the poster, the summary and the episode titles of the first season.
 *
 * The mapping is deliberately explicit rather than guessed from a trailing number. A title
 * ending with a digit proves nothing (`Mob Psycho 100`, `Psycho-Pass 2` or `SAO: Alicization 2`
 * are first seasons for the site), and a wrong guess would silently move a work to another
 * season. Adding a case is one line in [overrides]; if the site renames the work, the key has to
 * follow.
 */
internal object SequelSeasons {
    /**
     * Normalised work titles mapped to the TVDB season they really are.
     *
     * Keys are produced by [normalize], so case, accents and punctuation do not matter. The title
     * and every alternate title of the work are looked up.
     */
    private val overrides = mapOf(
        "cyberpunk edgerunners 2" to 2,
    )

    /**
     * Returns the TVDB season label to use instead of the one written by the site.
     *
     * @param title title displayed for the work.
     * @param alternateTitles alternate titles of the work.
     * @param seasonLabel season label written by the site.
     * @return a label such as `Saison 2`, or `null` when the work has no override.
     */
    fun seasonLabel(title: String, alternateTitles: Set<String>, seasonLabel: String?): String? {
        // A season the site announces as anything but the first one is always trusted.
        if (!seasonLabel.equals(FIRST_SEASON, ignoreCase = true)) return null

        val season = (sequenceOf(title) + alternateTitles.asSequence())
            .map { it.normalize() }
            .firstNotNullOfOrNull { overrides[it] }
            ?: return null
        return "Saison $season"
    }

    /** Label the site uses for a first season. */
    private const val FIRST_SEASON = "Saison 1"
}
