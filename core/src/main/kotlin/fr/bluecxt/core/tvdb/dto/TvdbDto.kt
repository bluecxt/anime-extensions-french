// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.tvdb.dto

import eu.kanade.tachiyomi.animesource.model.SAnime
import fr.bluecxt.core.tvdb.TvdbMetadata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// https://thetvdb.github.io/v4-api/

/**
 * /login
 */
@Serializable
internal data class TvdbAuthRequest(
    val apikey: String,
)

@Serializable
internal data class TvdbAuthResponse(
    val status: String = "",
    val data: TvdbAuthData? = null,
)

@Serializable
internal data class TvdbAuthData(
    val token: String = "",
)

/**
 * /search
 */
@Serializable
internal data class TvdbSearchResponse(
    val status: String = "",
    val data: List<TvdbSearchResult> = emptyList(),
)

@Serializable
internal data class TvdbSearchResult(
    @SerialName("tvdb_id") val tvdbId: String = "",
    @SerialName("objectID") val objectId: String = "",
    val name: String? = null,
    val slug: String? = null,
    val type: String? = null,
    val country: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("primary_language") val primaryLanguage: String? = null,
    val overview: String? = null,
    val overviews: Map<String, String> = emptyMap(),
    val translations: Map<String, String> = emptyMap(),
    val aliases: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val status: String? = null,
    @SerialName("first_air_time") val firstAirTime: String? = null,
    val year: String? = null,
)

/**
 * TODO: add the real url for that
 */
@Serializable
internal data class TvdbTranslationResponse(
    val status: String = "",
    val data: TvdbTranslationData? = null,
)

@Serializable
internal data class TvdbTranslationData(
    val name: String? = null,
    val overview: String? = null,
    val language: String? = null,
)

/**
 * TODO: add the real url for that
 */
@Serializable
internal data class TvdbEpisodesResponse(
    val status: String = "",
    val data: TvdbEpisodesData? = null,
)

@Serializable
internal data class TvdbEpisodesData(
    val episodes: List<TvdbEpisodeDto> = emptyList(),
)

@Serializable
internal data class TvdbEpisodeDto(
    val id: Long = 0,
    @SerialName("seriesId") val seriesId: Long = 0,
    val name: String? = null,
    val overview: String? = null,
    val image: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val number: Int = 0,
    @SerialName("absoluteNumber") val absoluteNumber: Int = 0,
    @SerialName("seasonNumber") val seasonNumber: Int = 0,
    val aired: String? = null,
) {
    val episodeImage: String? get() = image?.takeIf { it.isNotBlank() } ?: imageUrl?.takeIf { it.isNotBlank() }
}

@Serializable
internal data class TvdbStatusDto(
    val id: Int = 0,
    val name: String? = null,
    val recordType: String? = null,
)

@Serializable
internal data class TvdbGenreDto(
    val id: Int = 0,
    val name: String? = null,
)

@Serializable
internal data class TvdbCompanyDto(
    val id: Long = 0,
    val name: String? = null,
)

/**
 * TODO: add the real url for that
 */
@Serializable
internal data class TvdbExtendedResponse(
    val status: String = "",
    val data: TvdbExtendedData? = null,
)

@Serializable
internal data class TvdbSeasonTypeDto(
    val id: Int = 0,
    val name: String? = null,
    val altName: String? = null,
    val type: String? = null,
)

@Serializable
internal data class TvdbSeasonDto(
    val id: Long = 0,
    val number: Int = 0,
    val image: String? = null,
    val type: TvdbSeasonTypeDto? = null,
)

@Serializable
internal data class TvdbExtendedData(
    val name: String? = null,
    val image: String? = null,
    val firstAired: String? = null,
    val artworks: List<TvdbArtworkDto> = emptyList(),
    val seasons: List<TvdbSeasonDto> = emptyList(),
    val genres: List<TvdbGenreDto> = emptyList(),
    val companies: List<TvdbCompanyDto> = emptyList(),
    val status: TvdbStatusDto? = null,
)

@Serializable
internal data class TvdbArtworkDto(
    val type: Int = 0,
    val image: String? = null,
    val language: String? = null,
    val score: Long = 0,
)

/**
 * /seasons/{id}/extended
 */
@Serializable
internal data class TvdbSeasonExtendedResponse(
    val status: String = "",
    val data: TvdbSeasonExtendedData? = null,
)

@Serializable
internal data class TvdbSeasonExtendedData(
    val id: Long = 0,
    val image: String? = null,
    val artwork: List<TvdbArtworkDto> = emptyList(),
)

/**
 * /search/remoteid/{remoteId}
 */
@Serializable
internal data class TvdbRemoteIdResponse(
    val status: String = "",
    val data: List<TvdbRemoteIdResult> = emptyList(),
)

@Serializable
internal data class TvdbRemoteIdResult(
    val series: TvdbRemoteMedia? = null,
    val movie: TvdbRemoteMedia? = null,
) {
    val media: TvdbRemoteMedia?
        get() = series ?: movie

    val isMovie: Boolean
        get() = when {
            movie != null -> true
            else -> false
        }
}

@Serializable
internal data class TvdbRemoteMedia(
    val id: Long,
    val name: String? = null,
    val slug: String? = null,
    val image: String? = null,
    val year: String? = null,
    val firstAired: String? = null,
    val aliases: List<TvdbAlias> = emptyList(),
    val isMovie: Boolean = false,
)

@Serializable
internal data class TvdbAlias(
    val name: String? = null,
)

@Serializable
internal data class TvdbSeriesExtendedResponse(
    val status: String = "",
    val data: TvdbSeriesExtendedData? = null,
)

@Serializable
internal data class TvdbSeriesExtendedData(
    val id: Long = 0,
    val name: String? = null,
    val image: String? = null,
    val firstAired: String? = null,
    val lastAired: String? = null,
    val nextAired: String? = null,
    val score: Double? = null,
    val overview: String? = null,
    val status: TvdbStatusDto? = null,
    val genres: List<TvdbGenreDto> = emptyList(),
    val companies: List<TvdbCompanyDto> = emptyList(),
    val artworks: List<TvdbArtworkDto> = emptyList(),
    val seasons: List<TvdbSeasonDto> = emptyList(),
    val translations: TvdbTranslationsExtendedDto? = null,
) {
    /**
     * Synopsis prioritized by language: French -> English -> root series overview -> first non-blank available.
     */
    val localizedOverview: String?
        get() {
            val list = translations?.overviewTranslations
            return list?.firstOrNull { it.language == "fra" && !it.overview.isNullOrBlank() }?.overview
                ?: list?.firstOrNull { it.language == "eng" && !it.overview.isNullOrBlank() }?.overview
                ?: overview?.takeIf { it.isNotBlank() }
                ?: list?.firstOrNull { !it.overview.isNullOrBlank() }?.overview
        }

    /**
     * Title prioritized by language: French -> root title -> English -> first non-blank available.
     */
    val localizedName: String?
        get() {
            val list = translations?.nameTranslations
            return list?.firstOrNull { it.language == "fra" && !it.name.isNullOrBlank() }?.name
                ?: name?.takeIf { it.isNotBlank() }
                ?: list?.firstOrNull { it.language == "eng" && !it.name.isNullOrBlank() }?.name
                ?: list?.firstOrNull { !it.name.isNullOrBlank() }?.name
        }

    /**
     * Full poster image URL, prioritized by highest score artwork (Type 2 = Series Poster)
     * with fallback to root image, prefixed with TVDB artwork base URL when relative.
     */
    val fullPosterUrl: String?
        get() {
            val bestPoster = artworks
                .filter { it.type == 2 && !it.image.isNullOrBlank() }
                .maxByOrNull { it.score }
                ?.image
                ?: image?.takeIf { it.isNotBlank() }

            return bestPoster?.let {
                if (it.startsWith("http")) it else "https://artworks.thetvdb.com$it"
            }
        }

    /**
     * Fanart / Backdrop image URL (Type 3 = Series Background).
     */
    val backdropUrl: String?
        get() = artworks.firstOrNull { it.type == 3 && !it.image.isNullOrBlank() }?.image?.let {
            if (it.startsWith("http")) it else "https://artworks.thetvdb.com$it"
        }
}

@Serializable
internal data class TvdbMovieExtendedResponse(
    val status: String = "",
    val data: TvdbMovieExtendedData? = null,
)

/**
 * GET /movies/{id}/extended?meta=translations
 */
@Serializable
internal data class TvdbMovieExtendedData(
    val id: Long = 0,
    val name: String? = null,
    val image: String? = null,
    val year: String? = null,
    val runtime: Int? = null,
    val score: Double? = null,
    @SerialName("first_release") val firstRelease: TvdbReleaseDto? = null,
    val status: TvdbStatusDto? = null,
    val genres: List<TvdbGenreDto> = emptyList(),
    val artworks: List<TvdbArtworkDto> = emptyList(),
    val studios: List<TvdbCompanyDto> = emptyList(),
    val companies: TvdbMovieCompaniesDto? = null,
    val translations: TvdbTranslationsExtendedDto? = null,
) {
    val releaseDate: String?
        get() = firstRelease?.date ?: year

    val studioCompanies: List<TvdbCompanyDto>
        get() = studios.ifEmpty { companies?.studio.orEmpty() }

    val overview: String?
        get() {
            val overviews = translations?.overviewTranslations ?: return null
            return overviews.firstOrNull { it.language == "fra" && !it.overview.isNullOrBlank() }?.overview
                ?: overviews.firstOrNull { it.language == "eng" && !it.overview.isNullOrBlank() }?.overview
                ?: overviews.firstOrNull { !it.overview.isNullOrBlank() }?.overview
        }

    val localizedName: String?
        get() {
            val list = translations?.nameTranslations
            return list?.firstOrNull { it.language == "fra" && !it.name.isNullOrBlank() }?.name
                ?: name?.takeIf { it.isNotBlank() }
                ?: list?.firstOrNull { it.language == "eng" && !it.name.isNullOrBlank() }?.name
                ?: list?.firstOrNull { !it.name.isNullOrBlank() }?.name
        }

    fun toTvdbMetadata(): TvdbMetadata {
        val bestPoster = artworks
            .filter { (it.type == 2 || it.type == 14) && !it.image.isNullOrBlank() }
            .maxByOrNull { it.score }
            ?.image
            ?: image?.takeIf { it.isNotBlank() }

        val fullPosterUrl = bestPoster?.let {
            if (it.startsWith("http")) it else "https://artworks.thetvdb.com$it"
        }
        return TvdbMetadata(
            title = localizedName,
            summary = overview,
            releaseDate = releaseDate,
            mainPosterUrl = fullPosterUrl,
            seasonPosterUrl = fullPosterUrl,
            backdropUrl = artworks.find { it.type == 15 || it.type == 3 }
                ?.image
                ?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("http")) it else "https://artworks.thetvdb.com$it" },
            author = null,
            artist = studioCompanies
                .mapNotNullTo(linkedSetOf()) { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", "),
            status = SAnime.COMPLETED,
            genre = genres.mapNotNull { genre -> genre.name?.trim()?.takeIf { it.isNotBlank() } }.joinToString(", ").takeIf { it.isNotBlank() },
            episodeSummaries = emptyMap(),
            seriesId = id,
        )
    }
}

@Serializable
internal data class TvdbReleaseDto(
    val country: String? = null,
    val date: String? = null,
    val detail: String? = null,
)

@Serializable
internal data class TvdbMovieCompaniesDto(
    val studio: List<TvdbCompanyDto> = emptyList(),
    val network: List<TvdbCompanyDto> = emptyList(),
    val production: List<TvdbCompanyDto> = emptyList(),
    val distributor: List<TvdbCompanyDto> = emptyList(),
)

@Serializable
internal data class TvdbTranslationsExtendedDto(
    val overviewTranslations: List<TvdbTranslationRecordDto> = emptyList(),
    val nameTranslations: List<TvdbTranslationRecordDto> = emptyList(),
)

@Serializable
internal data class TvdbTranslationRecordDto(
    val language: String? = null,
    val name: String? = null,
    val overview: String? = null,
)
