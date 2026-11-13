// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.site

import fr.bluecxt.core.utils.parseStatus
import org.jsoup.nodes.Document

/**
 * TheMovieDB reference linked from a work page.
 *
 * @property id TMDB numeric id.
 * @property isMovie `true` for a `/movie/` link, `false` for a `/tv/` link.
 */
internal data class TmdbRef(
    val id: Long,
    val isMovie: Boolean,
)

/**
 * One season or film declared by a work page.
 *
 * @property label label written by the site (`Saison 1`, `Film`, `Saison 1 Remake 2026`…).
 * @property path absolute path of the season, language included, for example
 *   `/catalogue/07-ghost/saison1/vostfr`.
 */
internal data class SeasonRef(
    val label: String,
    val path: String,
)

/**
 * Metadata and season list of a work page ("hub").
 *
 * @property title title of the work as displayed by the site.
 * @property altTitles alternate titles, used to improve the TVDB match.
 * @property year release year, or `null`.
 * @property format broadcast format (`Série TV`, `Film`…), or `null`.
 * @property studio studio name, or `null`.
 * @property status the parsed `SAnime` status, `SAnime.UNKNOWN` when not advertised.
 * @property synopsis synopsis of the work, empty when absent.
 * @property genres genres listed by the page.
 * @property posterUrl poster image, or `null`.
 * @property backdropUrl background image, or `null`.
 * @property tmdb TheMovieDB link, or `null`.
 * @property seasons seasons and films declared by the page, in site order.
 */
internal data class HubInfo(
    val title: String?,
    val altTitles: Set<String>,
    val year: String?,
    val format: String?,
    val studio: String?,
    val status: Int,
    val synopsis: String,
    val genres: List<String>,
    val posterUrl: String?,
    val backdropUrl: String?,
    val tmdb: TmdbRef?,
    val seasons: List<SeasonRef>,
)

/**
 * Parser for a work page.
 *
 * The page mixes real content with commented-out JavaScript: the site keeps the panels of
 * seasons that are not out yet inside comments. Stripping comments before looking for
 * `panneauAnime(...)` is therefore mandatory, or the season list grows phantom entries
 * (18 declared panels against 1 real one on `07-ghost`).
 */
internal object HubPage {
    private val blockCommentRegex = Regex("""/\*[\s\S]*?\*/""")

    /** Line comments, but not the `//` of a URL. */
    private val lineCommentRegex = Regex("""(?<!:)//[^\n]*""")

    private val panelRegex = Regex("""panneauAnime\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)""")

    private val altTitlesRegex = Regex("""\s*[,;]\s*|\s+/\s+""")

    private val tmdbRegex = Regex("""themoviedb\.org/(tv|movie)/(\d+)""")

    /**
     * Parses a work page.
     *
     * @param document the work page.
     * @param hubPath absolute path of the work, used to build absolute season paths.
     */
    fun parse(document: Document, hubPath: String): HubInfo {
        val meta = metaParts(document)
        return HubInfo(
            title = document.selectFirst("h1.oe-titre")?.text()?.takeIf { it.isNotBlank() },
            altTitles = altTitles(document),
            year = meta.year,
            format = meta.format,
            studio = meta.studio,
            status = meta.status,
            synopsis = document.selectFirst("p#synopsisText")?.text().orEmpty(),
            genres = document.select("div.oe-genres a").map { it.text().trim() }.filter { it.isNotBlank() },
            posterUrl = document.selectFirst("div.oe-affiche img")?.attr("abs:src")?.takeIf { it.isNotBlank() },
            backdropUrl = document.selectFirst("div.oe-fond img")?.attr("abs:src")?.takeIf { it.isNotBlank() },
            tmdb = tmdb(document),
            seasons = seasons(document, hubPath),
        )
    }

    /**
     * Extracts the seasons and films declared by the work page.
     *
     * Seasons sharing the same root (`saison1/vostfr` and `saison1/vf`) are merged: the language
     * is chosen later, from the season page itself, which lists every available language.
     *
     * @param document the work page.
     * @param hubPath absolute path of the work.
     * @return the declared seasons, deduplicated by season root.
     */
    fun seasons(document: Document, hubPath: String): List<SeasonRef> {
        val scripts = document.select("script").joinToString("\n") { it.html() }
        val uncommented = lineCommentRegex.replace(blockCommentRegex.replace(scripts, ""), "")

        return panelRegex.findAll(uncommented)
            .mapNotNull { match ->
                val label = match.groupValues[1].trim()
                val relativePath = match.groupValues[2].trim().trim('/')
                if (relativePath.isBlank()) return@mapNotNull null
                SeasonRef(label, SitePaths.normalize("$hubPath/$relativePath"))
            }
            .distinctBy { SitePaths.seasonPath(it.path) }
            .toList()
    }

    /**
     * Reads the alternate titles advertised under the main title.
     *
     * @param document the work page.
     */
    private fun altTitles(document: Document): Set<String> = document.selectFirst("p#titreAlter")
        ?.text()
        ?.split(altTitlesRegex)
        ?.map { it.trim() }
        ?.filter { it.length >= MIN_TITLE_LENGTH }
        ?.toSet()
        .orEmpty()

    /**
     * Reads the one-line metadata summary.
     *
     * The page renders `year · format · studio · status`, for example
     * `2009 · Série TV · Studio DEEN · Terminé`.
     */
    private fun metaParts(document: Document): MetaParts {
        val parts = document.selectFirst("p.oe-meta")
            ?.text()
            ?.split("·")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val hasFullLine = parts.size >= FULL_META_PARTS
        return MetaParts(
            year = parts.getOrNull(0),
            format = parts.getOrNull(1),
            studio = if (hasFullLine) parts.getOrNull(STUDIO_INDEX) else infoValue(document, "Studio"),
            status = (if (hasFullLine) parts.getOrNull(STATUS_INDEX) else infoValue(document, "Statut"))
                .orEmpty()
                .parseStatus(),
        )
    }

    /**
     * Reads a value from the information table, for pages without the summary line.
     *
     * @param document the work page.
     * @param label row label, for example `Statut`.
     */
    private fun infoValue(document: Document, label: String): String? = document
        .selectFirst("dl.oe-infos dt:contains($label) + dd")
        ?.text()
        ?.takeIf { it.isNotBlank() }

    /**
     * Reads the TheMovieDB identifier linked from the page.
     *
     * @param document the work page.
     */
    private fun tmdb(document: Document): TmdbRef? {
        val href = document.selectFirst("a[href*='themoviedb.org/']")?.attr("href") ?: return null
        val match = tmdbRegex.find(href) ?: return null
        val id = match.groupValues[2].toLongOrNull() ?: return null
        return TmdbRef(id = id, isMovie = match.groupValues[1] == "movie")
    }

    /**
     * Metadata summary of a work page.
     *
     * @property year release year.
     * @property format broadcast format.
     * @property studio studio name.
     * @property status parsed status.
     */
    private data class MetaParts(
        val year: String?,
        val format: String?,
        val studio: String?,
        val status: Int,
    )

    /** Shortest alternate title worth keeping: shorter ones are usually noise. */
    private const val MIN_TITLE_LENGTH = 4

    /** Number of parts of a full summary line: year, format, studio, status. */
    private const val FULL_META_PARTS = 4

    /** Index of the studio in a summary line. */
    private const val STUDIO_INDEX = 2

    /** Index of the status in a summary line. */
    private const val STATUS_INDEX = 3
}
