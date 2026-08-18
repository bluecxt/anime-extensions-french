// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.tvdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val status: String? = null,
    @SerialName("first_air_time") val firstAirTime: String? = null,
    val year: String? = null,
)

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

@Serializable
internal data class TvdbExtendedResponse(
    val status: String = "",
    val data: TvdbExtendedData? = null,
)

@Serializable
internal data class TvdbSeasonDto(
    val id: Long = 0,
    val number: Int = 0,
    val image: String? = null,
)

@Serializable
internal data class TvdbExtendedData(
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
)

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
