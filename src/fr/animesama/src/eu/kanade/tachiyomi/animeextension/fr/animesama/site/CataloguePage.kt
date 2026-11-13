// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.site

import eu.kanade.tachiyomi.animeextension.fr.animesama.model.MediaUrl
import org.jsoup.nodes.Document

/**
 * One work listed by the catalogue.
 *
 * The parser stops at plain data so it can run outside the Android runtime and stay testable;
 * building the library entry is left to the source.
 *
 * @property title title displayed by the site.
 * @property posterUrl poster image, or `null` when the card has none.
 * @property media payload to store in the entry URL.
 */
internal data class CatalogueCard(
    val title: String,
    val posterUrl: String?,
    val media: MediaUrl,
)

/**
 * Parser for catalogue and search result pages.
 *
 * Every card carries its own data attributes (`data-url`, `data-nom`, `data-img`), which is both
 * simpler and more stable than reading the nested markup.
 */
internal object CataloguePage {
    private val pageNumberRegex = Regex("""[?&]page=(\d+)""")
    private val slugSeparatorRegex = Regex("""-""")

    /**
     * Reads the works of a catalogue page.
     *
     * Cards flagged as scan or webtoon without any video badge are dropped, since this extension
     * only handles video content. A card with no badge at all is kept: the latest-updates feed
     * does not always render one, and hiding a work would be worse than showing a dead entry.
     *
     * @param document catalogue or search result page.
     */
    fun entries(document: Document): List<CatalogueCard> = document.select(CARD_SELECTOR).mapNotNull { card ->
        val path = card.attr("data-url").trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val badges = card.select(".as-carte-badges .as-badge").map { it.className() }
        // Only cards that are explicitly scan or webtoon are dropped: a missing badge must not
        // hide a work, since the latest-updates feed does not always render one.
        val playable = badges.any { it.contains(VIDEO_BADGE) || it.contains(OTHER_BADGE) }
        val readingOnly = badges.any { it.contains(SCAN_BADGE) || it.contains(WEBTOON_BADGE) }
        if (!playable && readingOnly) return@mapNotNull null

        val name = card.attr("data-nom").ifBlank { card.selectFirst(".as-carte-titre")?.text().orEmpty() }
        val poster = card.attr("data-img").ifBlank {
            card.selectFirst(".as-carte-image img")?.attr("abs:src").orEmpty()
        }
        // A card without any title cannot be matched later; the slug is the last resort.
        val slugTitle = path.substringAfterLast('/').replace(slugSeparatorRegex, " ")

        CatalogueCard(
            title = name.ifBlank { slugTitle },
            posterUrl = poster.takeIf { it.isNotBlank() },
            media = MediaUrl(
                url = path,
                titles = setOfNotNull(name.takeIf { it.isNotBlank() }, slugTitle),
            ),
        )
    }

    /**
     * Decides whether a page announces a following page.
     *
     * The pagination block renders the highest reachable page twice: in its "go to page" field,
     * which is authoritative because it already accounts for the active filters, and as numeric
     * links, which are used as a fallback when the field is absent.
     *
     * @param document catalogue or search result page.
     * @param currentPage page that was just loaded.
     */
    fun hasNextPage(document: Document, currentPage: Int): Boolean {
        // The "go to page" field carries the real bound, filters included, and is rendered even
        // when the numeric links are truncated.
        val declaredLastPage = document.selectFirst(".pagi-aller input[max]")?.attr("max")?.toIntOrNull()
        val lastPage = declaredLastPage ?: document.select(".pagi a[href]")
            .mapNotNull { pageNumberRegex.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()
            ?: return false
        return currentPage < lastPage
    }

    /** Selector of a catalogue card; the data attributes hold everything the card describes. */
    private const val CARD_SELECTOR = "div.as-carte[data-url]"

    /** Badge class of an anime entry. */
    private const val VIDEO_BADGE = "as-badge-anime"

    /** Badge class of a non-anime but playable entry (film, OAV…). */
    private const val OTHER_BADGE = "as-badge-autre"

    /** Badge class of a scan entry. */
    private const val SCAN_BADGE = "as-badge-scan"

    /** Badge class of a webtoon entry. */
    private const val WEBTOON_BADGE = "as-badge-webtoon"
}
