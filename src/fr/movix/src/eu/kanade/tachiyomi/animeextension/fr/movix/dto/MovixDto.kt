package eu.kanade.tachiyomi.animeextension.fr.movix.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ========================= TMDB =========================

@Serializable
data class TmdbMainResponse(
    val results: List<TmdbResult> = emptyList(),
    val page: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null,
)

@Serializable
data class TmdbResult(
    val id: Int? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable
data class TmdbDetailResponse(
    val id: Int? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val genres: List<TmdbGenre>? = null,
    val credits: TmdbCredits? = null,
    val seasons: List<TmdbSeason>? = null,
    val status: String? = null,
    @SerialName("origin_country") val originCountry: List<String>? = null,
)

@Serializable
data class TmdbGenre(val id: Int? = null, val name: String? = null)

@Serializable
data class TmdbCredits(val cast: List<TmdbCast>? = null)

@Serializable
data class TmdbCast(
    val name: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val character: String? = null,
)

@Serializable
data class TmdbSeason(
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
data class TmdbSeasonDetail(
    val episodes: List<TmdbEpisode>? = null,
)

@Serializable
data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val runtime: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
)

// ========================= MOVIX API =========================

@Serializable
data class MovixMovieLinksResponse(
    val data: MovixLinkData? = null,
)

@Serializable
data class MovixTvLinksResponse(
    val data: List<MovixLinkData>? = null,
)

@Serializable
data class MovixLinkData(
    val links: List<String>? = null,
)

@Serializable
data class MovixTmdbResponse(
    @SerialName("iframe_src") val iframeSrc: String? = null,
    @SerialName("player_links") val playerLinks: List<MovixPlayerLink>? = null,
    @SerialName("current_episode") val currentEpisode: MovixCurrentEpisode? = null,
)

@Serializable
data class MovixCurrentEpisode(
    @SerialName("iframe_src") val iframeSrc: String? = null,
    @SerialName("player_links") val playerLinks: List<MovixPlayerLink>? = null,
)

@Serializable
data class MovixPlayerLink(
    @SerialName("decoded_url") val decodedUrl: String? = null,
)

@Serializable
data class MovixPurstreamResponse(
    val sources: List<PurstreamSource>? = null,
)

@Serializable
data class PurstreamSource(
    val url: String? = null,
    val name: String? = null,
    val format: String? = null,
)

@Serializable
data class MovixWiflixResponse(
    val success: Boolean? = null,
    val episodes: Map<String, WiflixEpisode>? = null,
    val movie: Map<String, List<WiflixLink>>? = null,
)

@Serializable
data class WiflixEpisode(
    val vf: List<WiflixLink>? = null,
    val vostfr: List<WiflixLink>? = null,
)

@Serializable
data class WiflixLink(
    val name: String? = null,
    val url: String? = null,
    val type: String? = null,
)

@Serializable
data class MovixFstreamResponse(
    val links: Map<String, List<FstreamLink>>? = null,
    val episodes: Map<String, FstreamEpisode>? = null,
)

@Serializable
data class FstreamEpisode(
    val languages: Map<String, List<FstreamLink>>? = null,
)

@Serializable
data class FstreamLink(val url: String? = null)

@Serializable
data class MovixImdbResponse(val series: List<ImdbSeries>? = null)

@Serializable
data class ImdbSeries(val seasons: List<ImdbSeason>? = null)

@Serializable
data class ImdbSeason(val episodes: List<ImdbEpisode>? = null)

@Serializable
data class ImdbEpisode(
    val number: String? = null,
    val versions: Map<String, ImdbVersion>? = null,
)

@Serializable
data class ImdbVersion(val players: List<ImdbPlayer>? = null)

@Serializable
data class ImdbPlayer(val link: String? = null)

@Serializable
data class CpasmalRes(
    val links: Map<String, List<GenericSource>>? = null,
)

@Serializable
data class GenericSource(val url: String? = null)

@Serializable
data class MovixDramaResponse(
    val success: Boolean? = null,
    val data: List<DramaLink>? = null,
)

@Serializable
data class DramaLink(
    val name: String? = null,
    val link: String? = null,
)
