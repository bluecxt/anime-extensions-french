
package fr.bluecxt.core.anilist

import eu.kanade.tachiyomi.animesource.model.SAnime
import fr.bluecxt.core.anilist.dto.AnilistMediaDto
import fr.bluecxt.core.utils.parseStatus

/** Nombre maximal de noms de staff conservés. */
private const val MAX_RELEVANT_STAFF = 4

enum class AnilistFormat {
    TV,
    TV_SHORT,
    MOVIE,
    SPECIAL,
    OVA,
    ONA,
    MUSIC,
}

data class AnilistTag(
    val name: String,
    val description: String?,
    val rank: Int,
    val isSpoiler: Boolean,
)

data class AnilistTrailer(
    val id: String,
    val site: String,
    val thumbnailUrl: String?,
) {
    val watchUrl: String?
        get() = when (site.lowercase()) {
            "youtube" -> "https://www.youtube.com/watch?v=$id"
            "dailymotion" -> "https://www.dailymotion.com/video/$id"
            else -> null
        }
}

data class AnilistNextAiring(
    val episode: Int,
    val airingAtEpochSeconds: Long,
    val timeUntilAiringSeconds: Long,
)

data class AnilistMetadata(
    val anilistId: Int,
    val malId: Int?,
    val titleRomaji: String?,
    val titleEnglish: String?,
    val titleNative: String?,
    val synonyms: List<String>,
    val description: String?,
    val format: AnilistFormat?,
    val status: Int,
    val rawStatus: String?,
    val episodes: Int?,
    val durationMinutes: Int?,
    val season: String?,
    val seasonYear: Int?,
    val coverUrl: String?,
    val coverColorHex: String?,
    val bannerUrl: String?,
    val genres: List<String>,
    val tags: List<AnilistTag>,
    val averageScore: Int?,
    val meanScore: Int?,
    val popularity: Int?,
    val animationStudios: List<String>,
    val producers: List<String>,
    val authorsAndDirectors: List<String>,
    val trailer: AnilistTrailer?,
    val nextAiring: AnilistNextAiring?,
    val siteUrl: String?,
) {
    val preferredTitle: String
        get() = titleRomaji ?: titleEnglish ?: titleNative ?: ""

/*
    /**
 * Map directement compatible avec `addTrackersEnd(metadata.trackers)`.
 */
    val trackers: Map<Int, Trackers>
        get() = buildMap {
            put(anilistId, Trackers.AniList)
            malId?.let { put(it, Trackers.MyAnimeList) }
        }
 */

    /**
     * Retourne les tags triés par pertinence (rank décroissant), sans spoilers par défaut.
     */
    fun getFilteredTags(
        includeSpoilers: Boolean = false,
        minRank: Int = 60,
        limit: Int = 8,
    ): List<AnilistTag> = tags
        .asSequence()
        .filter { (includeSpoilers || !it.isSpoiler) && it.rank >= minRank }
        .sortedByDescending { it.rank }
        .take(limit)
        .toList()

    /**
     * Construit une description enrichie avec métadonnées de diffusion, note et trailer.
     */
    fun buildFormattedDescription(includeHeaderStats: Boolean = true): String {
        val baseDescription = description?.takeIf { it.isNotBlank() } ?: ""
        if (!includeHeaderStats) return baseDescription

        val stats = buildList {
            averageScore?.let { add("Note : $it%") }
            format?.let { add("Format : ${it.name}") }
            episodes?.let { add("Épisodes : $it") }
            if (season != null && seasonYear != null) {
                add("Saison : $season $seasonYear")
            } else if (seasonYear != null) {
                add("Année : $seasonYear")
            }
        }

        return buildString {
            if (stats.isNotEmpty()) {
                append(stats.joinToString(" • "))
                append("\n\n")
            }
            if (baseDescription.isNotEmpty()) {
                append(baseDescription)
            }
            trailer?.watchUrl?.let { url ->
                if (isNotEmpty()) append("\n\n")
                append("Trailer : $url")
            }
        }.trim()
    }
}

/**
 * Applique les métadonnées AniList directement sur une instance [SAnime].
 */
fun SAnime.applyAnilistMetadata(
    metadata: AnilistMetadata,
    overrideThumbnail: Boolean = true,
    includeTagsInGenre: Boolean = true,
    includeStatsInDescription: Boolean = true,
): SAnime = apply {
    if (overrideThumbnail || thumbnail_url.isNullOrBlank()) {
        metadata.coverUrl?.let { thumbnail_url = it }
    }

    if (metadata.status != SAnime.UNKNOWN) {
        status = metadata.status
    }

    val studiosStr = metadata.animationStudios.ifEmpty { metadata.producers }.joinToString(", ")
    if (studiosStr.isNotEmpty()) {
        artist = studiosStr
    }

    val staffStr = metadata.authorsAndDirectors.joinToString(", ")
    if (staffStr.isNotEmpty()) {
        author = staffStr
    } else if (author.isNullOrBlank() && studiosStr.isNotEmpty()) {
        author = studiosStr
    }

    val combinedGenres = buildList {
        addAll(metadata.genres)
        if (includeTagsInGenre) {
            addAll(metadata.getFilteredTags().map { it.name })
        }
    }.distinct()

    if (combinedGenres.isNotEmpty()) {
        genre = combinedGenres.joinToString(", ")
    }

    val formattedDesc = metadata.buildFormattedDescription(includeStatsInDescription)
    if (formattedDesc.isNotEmpty()) {
        description = formattedDesc
    }
}

internal fun AnilistMediaDto.toMetadata(): AnilistMetadata {
    val cleanDesc = description?.let(::sanitizeAnilistDescription)

    val animStudios = studios?.nodes
        .orEmpty()
        .filter { it.isAnimationStudio }
        .map { it.name }
        .distinct()

    val prodStudios = studios?.nodes
        .orEmpty()
        .filterNot { it.isAnimationStudio }
        .map { it.name }
        .distinct()

    val relevantRoles = setOf(
        "original creator",
        "original story",
        "director",
        "chief director",
        "series composition",
        "script",
    )

    val extractedStaff = staff?.edges
        .orEmpty()
        .filter { edge ->
            val r = edge.role?.lowercase() ?: return@filter false
            relevantRoles.any { r.contains(it) }
        }
        .mapNotNull { it.node?.name?.full }
        .distinct()
        .take(MAX_RELEVANT_STAFF)

    val mappedTags = tags.map {
        AnilistTag(
            name = it.name,
            description = it.description,
            rank = it.rank ?: 0,
            isSpoiler = it.isMediaSpoiler || it.isGeneralSpoiler,
        )
    }.sortedByDescending { it.rank }

    val mappedTrailer = trailer?.let { t ->
        val id = t.id
        val site = t.site
        if (!id.isNullOrBlank() && !site.isNullOrBlank()) {
            AnilistTrailer(id = id, site = site, thumbnailUrl = t.thumbnail)
        } else {
            null
        }
    }

    val mappedNextAiring = nextAiringEpisode?.let {
        AnilistNextAiring(
            episode = it.episode,
            airingAtEpochSeconds = it.airingAt,
            timeUntilAiringSeconds = it.timeUntilAiring,
        )
    }

    val resolvedYear = seasonYear ?: startDate?.year

    return AnilistMetadata(
        anilistId = id,
        malId = idMal,
        titleRomaji = title?.romaji,
        titleEnglish = title?.english,
        titleNative = title?.native,
        synonyms = synonyms,
        description = cleanDesc,
        format = format?.let { runCatching { AnilistFormat.valueOf(it) }.getOrNull() },
        status = status?.parseStatus()?.takeIf { it != SAnime.UNKNOWN } ?: if (status == "NOT_YET_RELEASED") SAnime.ONGOING else SAnime.UNKNOWN,
        rawStatus = status,
        episodes = episodes,
        durationMinutes = duration,
        season = season,
        seasonYear = resolvedYear,
        coverUrl = coverImage?.extraLarge ?: coverImage?.large ?: coverImage?.medium,
        coverColorHex = coverImage?.color,
        bannerUrl = bannerImage,
        genres = genres,
        tags = mappedTags,
        averageScore = averageScore ?: meanScore,
        meanScore = meanScore,
        popularity = popularity,
        animationStudios = animStudios,
        producers = prodStudios,
        authorsAndDirectors = extractedStaff,
        trailer = mappedTrailer,
        nextAiring = mappedNextAiring,
        siteUrl = siteUrl,
    )
}

private val HTML_BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
private val MULTI_NEWLINE_REGEX = Regex("""\n{3,}""")
private val ANILIST_SOURCE_FOOTER_REGEX = Regex("""\n*\(Source:\s*[^)]+\)\s*$""", RegexOption.IGNORE_CASE)

internal fun sanitizeAnilistDescription(raw: String): String = raw
    .replace(HTML_BR_REGEX, "\n")
    .replace(HTML_TAG_REGEX, "")
    .replace("&quot;", "\"")
    .replace("&amp;", "&")
    .replace("&#039;", "'")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&mdash;", "—")
    .replace("&ndash;", "–")
    .replace(ANILIST_SOURCE_FOOTER_REGEX, "")
    .replace(MULTI_NEWLINE_REGEX, "\n\n")
    .trim()
