// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.site

import org.jsoup.nodes.Document

/**
 * One language offered for a season.
 *
 * @property slug slug used in the URL, for example `vostfr`.
 * @property label label displayed by the site, for example `VOSTFR`.
 */
internal data class LanguageRef(
    val slug: String,
    val label: String,
)

/**
 * Everything a season page exposes.
 *
 * The metadata (poster, synopsis, genres, studio) moved to the work page, so a season page only
 * carries the work title, the season label, the available languages and the episode data.
 *
 * @property workTitle title of the work the season belongs to.
 * @property seasonLabel label of the season (`Saison 1`, `Film`…), or `null` when absent.
 * @property languages languages the season is available in, as declared by the page.
 * @property bdd parsed episode script, or `null` when the season has no episode yet.
 */
internal data class SeasonInfo(
    val workTitle: String?,
    val seasonLabel: String?,
    val languages: List<LanguageRef>,
    val bdd: BddScript?,
)

/**
 * Parser for a season page, in one language.
 *
 * The season label is not present as text in the markup: the page writes it through a small
 * jQuery call, which is the only place it can be read before the site renders it.
 */
internal object SeasonPage {
    /** Reads the inline `$("#avOeuvre").html("Saison 1")` call. */
    private val seasonLabelRegex = Regex("""\$\(["']#avOeuvre["']\)\.html\(["']([^"']*)["']\)""")

    /** Reads the list of languages the season is available in. */
    private val languagesRegex = Regex("""var\s+languesDispo\s*=\s*\[([^\]]*)]""")

    private val quotedRegex = Regex("""["']([^"']+)["']""")

    /**
     * Parses a season page.
     *
     * @param document page of a season in one language.
     */
    fun parse(document: Document): SeasonInfo = SeasonInfo(
        workTitle = document.selectFirst("h3#titreOeuvre")?.text()?.takeIf { it.isNotBlank() },
        seasonLabel = seasonLabel(document),
        languages = languages(document),
        bdd = BddScriptParser.parse(document),
    )

    /**
     * Reads the season label from the inline script.
     *
     * @param document page of a season.
     * @return the label, or `null` when the page does not declare it.
     */
    private fun seasonLabel(document: Document): String? = seasonLabelRegex.find(document.html())
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    /**
     * Reads the languages of a season.
     *
     * The language switcher is authoritative: it is rendered from the languages that actually
     * have a page. The `languesDispo` variable is used as a fallback for pages where the
     * switcher is not rendered.
     *
     * @param document page of a season.
     */
    fun languages(document: Document): List<LanguageRef> {
        val fromSwitcher = document.select("div.ct-langues a[href]").mapNotNull { anchor ->
            val slug = anchor.attr("href").trimEnd('/').substringAfterLast('/')
            if (slug.isBlank()) return@mapNotNull null
            val label = anchor.selectFirst("span")?.text()?.trim().orEmpty().ifBlank { slug.uppercase() }
            LanguageRef(slug, label)
        }
        if (fromSwitcher.isNotEmpty()) return fromSwitcher.distinctBy { it.slug }

        val declared = languagesRegex.find(document.html())?.groupValues?.get(1) ?: return emptyList()
        return quotedRegex.findAll(declared)
            .map { LanguageRef(it.groupValues[1], it.groupValues[1].uppercase()) }
            .toList()
    }
}
