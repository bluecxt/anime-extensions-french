// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.dessinanime.dto

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable

@Serializable
data class SearchItemDto(
    val title: String,
    val slug: String,
    val mediaType: String,
    val posterPath: String? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        title = this@SearchItemDto.title
        url = "/${mediaType.lowercase()}/$slug"
        thumbnail_url = if (!posterPath.isNullOrEmpty() && posterPath != "null") posterPath else null
    }
}

@Serializable
data class CatalogueDto(
    val id: Int,
    val slug: String,
    val title: String,
    val releaseYear: Int,
    val posterPath: String,
    val mediaType: String,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        title = this@CatalogueDto.title
        url = "/${mediaType.lowercase()}/$slug"
        thumbnail_url = posterPath
    }
}

@Serializable
data class DessinAnimePosterDto(
    val src: String,
    val alt: String = "",
    val variant: String? = null,
)

@Serializable
data class DessinAnimeMediaDto(
    val id: Long,
    val title: String,
    val slug: String,
    val tmdbId: Long? = null,
    val mediaType: String? = null,
)

@Serializable
data class EpisodeItemDto(
    val id: Long,
    val episodeNumber: Int,
    val title: String? = null,
    val runtime: Int? = null,
    val stillPath: String? = null,
)

@Serializable
data class SeasonItemDto(
    val id: Long,
    val seasonNumber: Int,
    val title: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val customTitle: String? = null,
    val episodes: List<EpisodeItemDto> = emptyList(),
)

@Serializable
data class SeriesDataDto(
    val slug: String,
    val seasons: List<SeasonItemDto>,
) {
    fun toEpisodeList(animeUrl: String, targetSeason: Int? = null): List<SEpisode> {
        val filteredSeasons = if (targetSeason != null) {
            seasons.filter { it.seasonNumber == targetSeason }
        } else {
            seasons
        }
        val isMultiSeason = seasons.size > 1 && targetSeason == null

        val totalCapacity = filteredSeasons.sumOf { it.episodes.size }
        return buildList(totalCapacity) {
            for (season in filteredSeasons) {
                val sNum = season.seasonNumber
                for (ep in season.episodes) {
                    add(
                        SEpisode.create().apply {
                            url = "$animeUrl/$sNum/${ep.episodeNumber}"
                            episode_number = ep.episodeNumber.toFloat()
                            name = buildString {
                                if (isMultiSeason) append("[S$sNum] ")
                                append("Épisode ${ep.episodeNumber}")
                                ep.title?.takeIf { it.isNotBlank() && !it.contains("Episode", ignoreCase = true) }?.let {
                                    append(" - $it")
                                }
                            }
                            ep.stillPath?.takeIf { it.isNotBlank() }?.let { preview_url = it }
                        },
                    )
                }
            }
        }.asReversed()
    }
}

@Serializable
data class HosterItemDto(
    val serverName: String,
    val url: String,
    val language: String = "VF",
)

@Serializable
data class HosterPayloadDto(
    val items: List<HosterItemDto>,
)

@Serializable
data class PlayerItemDto(
    val id: Long,
    val name: String,
    val url: String,
    val direct: Boolean = false,
    val language: String? = null,
    val host: String? = null,
    val embedId: String? = null,
) {
    fun toHoster(): Hoster {
        val langLabel = language?.takeIf { it.isNotBlank() } ?: "VF"
        val displayName = "$name ($langLabel)"
        val payload = HosterPayloadDto(
            listOf(HosterItemDto(serverName = name, url = url, language = langLabel)),
        )
        return Hoster(
            hosterName = displayName,
            hosterUrl = url,
            internalData = payload.toJsonString(),
        )
    }
}

@Serializable
data class PlayerEmbedDto(
    val title: String? = null,
    val playbackKey: String? = null,
    val players: List<PlayerItemDto>,
) {
    fun toHosterList(): List<Hoster> = players.map { it.toHoster() }
}
