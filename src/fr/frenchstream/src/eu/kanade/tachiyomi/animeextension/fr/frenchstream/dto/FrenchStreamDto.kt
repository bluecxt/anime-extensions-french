// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.frenchstream.dto

import eu.kanade.tachiyomi.animeextension.fr.frenchstream.MOVIE_EP_NUMBER
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.utils.safeRelativePath
import keiyoushi.utils.toJsonString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private const val SEARCH_PAGE_SIZE = 15

@Serializable
data class RawCatalogItemDto(
    @SerialName("t") val rawTitle: String,
    @SerialName("img") val rawPosterUrl: String,
    @SerialName("u") val rawUrl: String,
) {
    fun toCatalogItemDto(baseUrl: String): CatalogItemDto? = CatalogItemDto(
        title = rawTitle,
        posterUrl = rawPosterUrl,
        id = rawUrl.safeRelativePath(baseUrl)?.linkToId() ?: return null,
    )
}

data class CatalogItemDto(
    val title: String,
    val posterUrl: String,
    val id: String,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        title = this@CatalogItemDto.title
        url = this@CatalogItemDto.id
        thumbnail_url = this@CatalogItemDto.posterUrl
    }

    companion object {
        fun from(element: Element): CatalogItemDto? = with(element) {
            val title = selectFirst(".short-title, .search-title")?.text() ?: ""
            val posterUrl = selectFirst("img")?.attr("abs:src") ?: ""
            val id = selectFirst("a.short-poster")?.safeRelativePath()?.linkToId()
                ?: attr("onclick").takeIf { it.isNotBlank() }?.linkToId()
                ?: return null
            return CatalogItemDto(title, posterUrl, id)
        }
    }
}

@Serializable
data class CatalogDto(
    @SerialName("items")
    val rawItems: List<RawCatalogItemDto> = emptyList(),

    @SerialName("more")
    val hasMore: Boolean,
) {
    fun toAnimesPage(baseUrl: String): AnimesPage = AnimesPage(
        animes = this@CatalogDto.rawItems.mapNotNull { it.toCatalogItemDto(baseUrl)?.toSAnime() },
        hasNextPage = this@CatalogDto.hasMore,
    )

    companion object {
        fun from(document: Document, isSearch: Boolean = false): AnimesPage = with(document) {
            val rawItems = select("#dle-content > .short, .search-item").mapNotNull { CatalogItemDto.from(it)?.toSAnime() }
            val hasNextPage = selectFirst(".pnext a") != null ||
                (isSearch && rawItems.size >= SEARCH_PAGE_SIZE)
            return AnimesPage(rawItems, hasNextPage)
        }
    }
}

private fun String.linkToId(): String = if (contains("index.php")) substringAfterLast("=").trim('\'', '"') else substringAfter("/").substringBefore("-")

data class DetailsItemDto(
    val title: String?,
    val poster: String?,
    val releaseDate: String?,
    val genres: String?,
    val producer: String?,
    val actor: String?,
    val description: String?,
    val status: Int,
) {
    fun populate(anime: SAnime, datePrefix: String = "Date de sortie : "): SAnime = anime.apply {
        fun String?.orIfBlank(fallback: () -> String?): String? = if (isNullOrBlank()) fallback() else this

        title = title.ifBlank { this@DetailsItemDto.title.orEmpty() }
        thumbnail_url = this@DetailsItemDto.poster.orIfBlank { thumbnail_url }
        genre = genre.orIfBlank { this@DetailsItemDto.genres }
        artist = artist.orIfBlank { this@DetailsItemDto.actor }
        author = author.orIfBlank { this@DetailsItemDto.producer }

        description = description.orIfBlank {
            if (!this@DetailsItemDto.description.isNullOrBlank() && !releaseDate.isNullOrBlank()) {
                "$datePrefix$releaseDate\n${this@DetailsItemDto.description}"
            } else {
                null
            }
        }

        if (status == SAnime.UNKNOWN && this@DetailsItemDto.status != SAnime.UNKNOWN) {
            status = this@DetailsItemDto.status
        }
    }

    companion object {
        fun from(document: Document): DetailsItemDto = with(document) {
            fun selectJoined(query: String) = select(query)
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
                .ifBlank { null }

            val listNbrEp = selectFirst(".short-meta:contains(Ep), .episode-row")?.text()
                ?.split(" ")?.mapNotNull { it.toIntOrNull() }.orEmpty()

            DetailsItemDto(
                title = selectFirst("h1")?.text()?.trim(),
                poster = selectFirst("img.dvd-thumbnail")?.attr("abs:src")?.ifBlank { null }
                    ?: selectFirst("img.dvd-thumbnail")?.attr("src")?.ifBlank { null },
                releaseDate = selectFirst("li:contains(Date de sortie:) a, a[href*='annee-'], a[href*='xfsearch/year']")?.text()?.trim(),
                genres = selectJoined("li:contains(Genre:) a, a[href*='xfsearch/genre']"),
                producer = selectJoined("li:contains(Réalisateur:) a, a[href*='xfsearch/realisateur']"),
                actor = selectJoined("li:contains(Acteurs:) a, :containsOwn(Avec :) a, a[href*='xfsearch/acteur']"),
                description = selectFirst(".fdesc")?.apply { select(".desc-text").remove() }?.text()?.trim()?.ifBlank { null },
                status = when {
                    listNbrEp.size >= 2 && listNbrEp.first() == listNbrEp.last() -> SAnime.COMPLETED
                    listNbrEp.isNotEmpty() -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                },
            )
        }
    }
}

@Serializable
data class EpisodeInfoDto(
    val title: String? = null,
    val synopsis: String? = null,
    val poster: String? = null,
)

@Serializable
data class EpisodeUrlDto(
    val mediaId: String,
    val epNum: String,
    val langs: List<String>,
)

@Serializable
data class SeriesDataDto(
    val vf: Map<String, Map<String, String>> = emptyMap(),
    val vostfr: Map<String, Map<String, String>> = emptyMap(),
    val vo: Map<String, Map<String, String>> = emptyMap(),
    val vfq: Map<String, Map<String, String>> = emptyMap(),
    val info: Map<String, EpisodeInfoDto> = emptyMap(),
) {
    private val allLanguages: Map<String, Map<String, Map<String, String>>>
        get() = mapOf(
            "VF" to vf,
            "VOSTFR" to vostfr,
            "VO" to vo,
            "VFQ" to vfq,
        )

    val allEpisodes: List<String>
        get() = allLanguages.values
            .flatMap { it.keys }
            .distinct()
            .sortedBy { it.toIntOrNull() ?: 0 }

    val incompleteEpisodeData: Boolean
        get() = allEpisodes.any { epNum ->
            info[epNum]?.poster.isNullOrBlank() || info[epNum]?.synopsis.isNullOrBlank()
        }

    fun toEpisodeList(mediaId: String): List<SEpisode> = allEpisodes.map { epNum ->
        val availableLangs = allLanguages.filter { it.value.containsKey(epNum) }.map { it.key }

        SEpisode.create().apply {
            url = EpisodeUrlDto(mediaId, epNum, availableLangs).toJsonString()
            name = buildString {
                append("Épisode $epNum")
                info[epNum]?.title?.takeIf { it.isNotBlank() && !it.contains(EPISODE_REGEX) }?.let {
                    append(" - ")
                    append(info[epNum]!!.title)
                }
            }
            episode_number = epNum.toFloatOrNull() ?: 1F
            scanlator = availableLangs.joinToString(", ")
            info[epNum]?.poster?.takeIf { it.isNotBlank() }?.let { preview_url = it }
            info[epNum]?.synopsis?.takeIf { it.isNotBlank() }?.let { summary = it }
        }
    }.reversed()

    fun toHosterList(mediaId: String, epNum: String, langs: List<String>): List<Hoster> = langs.map { lang ->
        val links: List<String> = allLanguages[lang]?.get(epNum)?.values?.toList() ?: emptyList()
        Hoster(
            hosterName = lang,
            hosterUrl = links.toJsonString(),
        )
    }
    companion object {
        private val EPISODE_REGEX = Regex("[eé]pisode", RegexOption.IGNORE_CASE)
    }
}

@Serializable
data class MovieDto(
    val players: Map<String, MovieHostDto> = emptyMap(),
    val meta: MovieMetaDto? = null,
    val error: String? = null,
) {
    val availableLangs: List<String>
        get() = buildList {
            if (players.values.any { !it.vf.isNullOrBlank() }) add("VF")
            if (players.values.any { !it.vostfr.isNullOrBlank() }) add("VOSTFR")
            if (players.values.any { !it.vfq.isNullOrBlank() }) add("VFQ")
            if (players.values.any { !it.vo.isNullOrBlank() }) add("VO")
        }

    fun toEpisodeList(mediaId: String): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = EpisodeUrlDto(mediaId, MOVIE_EP_NUMBER.toString(), availableLangs).toJsonString()
            name = "Film"
            episode_number = 1F
            scanlator = availableLangs.joinToString(", ")
            (meta?.poster?.takeIf { it.isNotBlank() } ?: meta?.poster2?.takeIf { it.isNotBlank() })?.let {
                preview_url = it
            }
        },
    )

    fun toHosterList(mediaId: String, epNum: String, langs: List<String>): List<Hoster> = langs.map { lang ->
        val links = players.values.mapNotNull { it.getLink(lang) }
        Hoster(
            hosterName = lang,
            hosterUrl = links.toJsonString(),
        )
    }
}

@Serializable
data class MovieHostDto(
    @SerialName("default")
    val vf: String? = null,
    val vostfr: String? = null,
    val vfq: String? = null,
    val vo: String? = null,
) {
    fun getLink(lang: String): String? = when (lang) {
        "VF" -> vf
        "VOSTFR" -> vostfr
        "VFQ" -> vfq
        "VO" -> vo
        else -> null
    }?.takeIf { it.isNotBlank() }
}

@Serializable
data class MovieMetaDto(
    @SerialName("affiche") val poster: String? = null,
    @SerialName("affiche2") val poster2: String? = null,
    @SerialName("trailer") val trailer: String? = null,
    @SerialName("tagz") val tmdbId: String? = null,
    @SerialName("bkp") val cast: String? = null,
)
