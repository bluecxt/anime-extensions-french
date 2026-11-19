package fr.bluecxt.core.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class AnilistGraphQlRequest(
    val query: String,
    val variables: JsonObject,
)

@Serializable
internal data class AnilistGraphQlResponse<T>(
    val data: T? = null,
    val errors: List<AnilistGraphQlError>? = null,
)

@Serializable
internal data class AnilistGraphQlError(
    val message: String = "Unknown GraphQL error",
    val status: Int? = null,
    val locations: List<AnilistErrorLocation>? = null,
)

@Serializable
internal data class AnilistErrorLocation(
    val line: Int? = null,
    val column: Int? = null,
)

@Serializable
internal data class AnilistMediaQueryData(
    @SerialName("Media")
    val media: AnilistMediaDto? = null,
)

@Serializable
internal data class AnilistPageQueryData(
    @SerialName("Page")
    val page: AnilistPageDto? = null,
)

@Serializable
internal data class AnilistPageDto(
    val media: List<AnilistMediaDto> = emptyList(),
)

@Serializable
internal data class AnilistMediaDto(
    val id: Int,
    val idMal: Int? = null,
    val title: AnilistTitleDto? = null,
    val synonyms: List<String> = emptyList(),
    val description: String? = null,
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val startDate: AnilistDateDto? = null,
    val endDate: AnilistDateDto? = null,
    val coverImage: AnilistCoverImageDto? = null,
    val bannerImage: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<AnilistTagDto> = emptyList(),
    val averageScore: Int? = null,
    val meanScore: Int? = null,
    val popularity: Int? = null,
    val studios: AnilistStudioConnectionDto? = null,
    val staff: AnilistStaffConnectionDto? = null,
    val trailer: AnilistTrailerDto? = null,
    val nextAiringEpisode: AnilistAiringScheduleDto? = null,
    val siteUrl: String? = null,
)

@Serializable
internal data class AnilistTitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
internal data class AnilistDateDto(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
)

@Serializable
internal data class AnilistCoverImageDto(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
    val color: String? = null,
)

@Serializable
internal data class AnilistTagDto(
    val name: String,
    val description: String? = null,
    val rank: Int? = null,
    val isMediaSpoiler: Boolean = false,
    val isGeneralSpoiler: Boolean = false,
)

@Serializable
internal data class AnilistStudioConnectionDto(
    val nodes: List<AnilistStudioDto> = emptyList(),
)

@Serializable
internal data class AnilistStudioDto(
    val id: Int,
    val name: String,
    val isAnimationStudio: Boolean = false,
)

@Serializable
internal data class AnilistStaffConnectionDto(
    val edges: List<AnilistStaffEdgeDto> = emptyList(),
)

@Serializable
internal data class AnilistStaffEdgeDto(
    val role: String? = null,
    val node: AnilistStaffNodeDto? = null,
)

@Serializable
internal data class AnilistStaffNodeDto(
    val name: AnilistStaffNameDto? = null,
)

@Serializable
internal data class AnilistStaffNameDto(
    val full: String? = null,
    val native: String? = null,
)

@Serializable
internal data class AnilistTrailerDto(
    val id: String? = null,
    val site: String? = null,
    val thumbnail: String? = null,
)

@Serializable
internal data class AnilistAiringScheduleDto(
    val episode: Int,
    val airingAt: Long,
    val timeUntilAiring: Long,
)
