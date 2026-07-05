package eu.kanade.tachiyomi.animeextension.fr.movixanime.dto

import kotlinx.serialization.Serializable

@Serializable
data class AnimeItem(
    val url: String,
    val name: String,
    val image: String? = null,
    val alternative_names: List<String> = emptyList(),
    val alternative_names_string: String? = null,
    val seasons: List<Season> = emptyList(),
)

@Serializable
data class Season(
    val name: String,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class Episode(
    val name: String,
    val serie_name: String? = null,
    val season_name: String? = null,
    val index: Int,
    val streaming_links: List<StreamingLink> = emptyList(),
)

@Serializable
data class StreamingLink(
    val language: String,
    val players: List<String> = emptyList(),
)

@Serializable
data class Top10Response(
    val top10: List<Top10Item> = emptyList(),
)

@Serializable
data class Top10Item(
    val title: String,
    val posterPath: String? = null,
    val contentId: String? = null,
)

@Serializable
data class TmdbDiscoverResponse(
    val results: List<TmdbDiscoverResult> = emptyList(),
)

@Serializable
data class TmdbDiscoverResult(
    val name: String? = null,
    val title: String? = null,
    val poster_path: String? = null,
)
