package fr.bluecxt.core.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TmdbSearchResponse(
    val results: List<TmdbSearchResult> = emptyList(),
)

@Serializable
internal data class TmdbSearchResult(
    val id: Int,
    val name: String? = null,
    val title: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
)

@Serializable
internal data class TmdbDetailResponse(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val status: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("original_language") val originalLanguage: String? = "ja",
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
    @SerialName("created_by") val createdBy: List<TmdbCreator> = emptyList(),
    val credits: TmdbCredits? = null,
    val seasons: List<TmdbSeasonSummary> = emptyList(),
)

@Serializable
internal data class TmdbSeasonSummary(
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
internal data class TmdbGenre(
    val name: String = "",
)

@Serializable
internal data class TmdbCompany(
    val name: String = "",
)

@Serializable
internal data class TmdbCreator(
    val name: String = "",
)

@Serializable
internal data class TmdbCredits(
    val crew: List<TmdbCrewMember> = emptyList(),
)

@Serializable
internal data class TmdbCrewMember(
    val name: String = "",
    val job: String? = null,
)

@Serializable
internal data class TmdbAlternativeTitlesResponse(
    val results: List<TmdbAlternativeTitle> = emptyList(),
)

@Serializable
internal data class TmdbAlternativeTitle(
    val title: String = "",
)

@Serializable
internal data class TmdbSeasonResponse(
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val episodes: List<TmdbEpisodeDto> = emptyList(),
)

@Serializable
internal data class TmdbEpisodeDto(
    @SerialName("episode_number") val episodeNumber: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
)

@Serializable
internal data class TmdbSeasonImagesResponse(
    val posters: List<TmdbPosterDto> = emptyList(),
)

@Serializable
internal data class TmdbPosterDto(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("iso_639_1") val iso6391: String? = null,
)
