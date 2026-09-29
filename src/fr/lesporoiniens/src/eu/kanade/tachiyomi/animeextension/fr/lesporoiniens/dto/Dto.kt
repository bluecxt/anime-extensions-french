package eu.kanade.tachiyomi.animeextension.fr.lesporoiniens.dto

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import fr.bluecxt.core.tvdb.TvdbMetadata
import fr.bluecxt.core.utils.parallelCatchingCancellableMapNotNull
import fr.bluecxt.core.utils.parseStatus
import fr.bluecxt.core.utils.runCatchingCancellable
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

val EPISODE_REGEX = Regex("""(?i)\b(?:[ée]pisodes?|eps?)\b""")
val HTML_TAG_REGEX = Regex("""<[^>]+>""")

@Serializable
data class MediaListDto(
    @SerialName("ENV") val env: String,
    @SerialName("URL_API_IMGCHEST") val urlApiImgchest: String,
    @SerialName("LOCAL_SERIES_FILES") val localSeriesFiles: Set<String>,
) {
    suspend fun toMediaList(baseUrl: String, client: OkHttpClient, json: Json): MediaList {
        val blockedFiles: Set<String> = runCatchingCancellable {
            val access = client.get("$baseUrl/data/access.json").parseAs<AccessDto>(json)
            access.vip + access.admin
        }.getOrDefault(emptySet())

        val localSeries: Set<Media> = localSeriesFiles
            .filterNot { it in blockedFiles }
            .parallelCatchingCancellableMapNotNull { serieFile ->
                client.get("$baseUrl/data/series/$serieFile").parseAs<Media>(json).apply {
                    mediaUrl = serieFile
                }
            }.toSet()
        return MediaList(urlApiImgchest, localSeries)
    }
}

data class MediaList(
    val urlApiImgchest: String,
    val localSeries: Set<Media>,
)

@Serializable
data class AccessDto(
    @SerialName("VIP") val vip: Set<String> = emptySet(),
    @SerialName("ADMIN") val admin: Set<String> = emptySet(),
)

@Serializable
data class Media(
    @SerialName("title") val mediaTitle: String,
    @SerialName("description") val mediaDescription: String,
    @SerialName("artist") val mediaArtist: String,
    val cover: String,
    @SerialName("manga_type") val mangaType: String,
    val magazine: String,
    val tags: List<String>,
    @SerialName("alternative_titles") val alternativeTitles: List<String>,
    @SerialName("release_status") val releaseStatus: String,
    val episodes: List<Episode>? = null,
    val anime: List<Anime>? = null,
) {
    var mediaUrl: String = ""
    fun toSAnime(): SAnime? {
        if (anime.isNullOrEmpty()) return null
        val firstAnime = anime.first()
        val lastAnime = anime.last()
        return SAnime.create().apply {
            url = mediaUrl
            title = mediaTitle
            artist = mediaArtist
            author = firstAnime.studios.joinToString(", ")
            description = buildString {
                append("Date de sortie : ")
                append(firstAnime.dateStart)
                append("\n")
                append(firstAnime.animeDescription.replace(HTML_TAG_REGEX, ""))
            }
            genre = firstAnime.animeTags.joinToString(", ")
            status = lastAnime.animeStatus.parseStatus()
            thumbnail_url = firstAnime.cover
            initialized = true
        }
    }

    suspend fun toListOfSEpisode(tvdbFetcher: suspend (title: String, season: Int) -> TvdbMetadata?): List<SEpisode> {
        if (episodes.isNullOrEmpty()) return emptyList()

        val tvdbSeason1 = tvdbFetcher(mediaTitle, 1)
        val hasHalfEpisodes = episodes.any { (it.epNum.times(10).toInt()) % 10 == 5 }
        val tvdbSeason0 = if (hasHalfEpisodes) tvdbFetcher(mediaTitle, 0) else null

        var specialCounter = 0

        return episodes.map { episode ->
            val epNumFloat = episode.epNum
            val isHalfEpisode = ((epNumFloat * 10).toInt() % 10) == 5

            val tvdbMeta = if (isHalfEpisode) {
                specialCounter++
                tvdbSeason0?.episodeSummaries?.get(specialCounter)
            } else {
                tvdbSeason1?.episodeSummaries?.get(epNumFloat.toInt())
            }

            val epTitle = episode.titleEp.takeUnless { it.isBlank() || it.contains(EPISODE_REGEX) }
                ?: tvdbMeta?.title

            val prefix = if (isHalfEpisode) "[OVA] " else ""

            SEpisode.create().apply {
                url = EpisodeData(episode.id, episode.type).toJsonString()
                name = buildString {
                    append(prefix)
                    append("Épisode ")
                    append(episode.epNum.toString().removeSuffix(".0"))
                    if (!epTitle.isNullOrBlank()) append(" - $epTitle")
                }
                date_upload = (episode.timeStamp.toLongOrNull() ?: 0L) * 1000L
                episode_number = epNumFloat
                preview_url = tvdbMeta?.thumbnail
                summary = tvdbMeta?.summary
            }
        }
    }
}

@Serializable
data class EpisodeData(
    val id: String,
    val type: String,
)

@Serializable
data class Episode(
    val type: String,
    val id: String,
    @SerialName("indice_ep") val epNum: Float,
    @SerialName("date_ep") val timeStamp: String,
    @SerialName("title_ep") val titleEp: String,
)

@Serializable
data class Anime(
    @SerialName("cover_an") val cover: String,
    @SerialName("type_an") val animeType: String,
    @SerialName("status_an") val animeStatus: String,
    @SerialName("studios_an") val studios: List<String>,
    @SerialName("date_start_an") val dateStart: String,
    @SerialName("description") val animeDescription: String,
    @SerialName("tags") val animeTags: List<String>,
)
