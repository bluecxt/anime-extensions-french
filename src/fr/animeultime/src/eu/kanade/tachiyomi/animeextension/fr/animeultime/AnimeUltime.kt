package eu.kanade.tachiyomi.animeextension.fr.animeultime

import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.animeultime.dto.AlbumResponse
import eu.kanade.tachiyomi.animeextension.fr.animeultime.dto.Categories
import eu.kanade.tachiyomi.animeextension.fr.animeultime.dto.PlayList
import eu.kanade.tachiyomi.animeextension.fr.animeultime.dto.SearchResponse
import eu.kanade.tachiyomi.animeextension.fr.animeultime.dto.SearchResponseItem
import eu.kanade.tachiyomi.animeextension.fr.animeultime.dto.Track
import eu.kanade.tachiyomi.animeextension.fr.animeultime.dto.UrlContent
import eu.kanade.tachiyomi.animeextension.fr.animeultime.dto.VideoPlayerResponse
import eu.kanade.tachiyomi.animeextension.fr.animeultime.dto.decodeFromStringFixed
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.ANIMEULTIME_LOG
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.Source
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.safeRelativePath
import keiyoushi.utils.get
import keiyoushi.utils.parallelFlatMap
import keiyoushi.utils.parallelMap
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.post
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup.parse
import uy.kohesive.injekt.injectLazy

private const val ITEMS_PER_PAGE = 100
private val CACHE_EXPIRATION = 24 * 60 * 60 * 1000L

class AnimeUltime :
    Source(),
    CommonPreferences {

    override val name = "Anime-Ultime"
    override val defaultBaseUrl: String = "https://v5.anime-ultime.net"
    override val lang = "fr"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("X-Requested-With", "XMLHttpRequest")

    override fun getAnimeUrl(anime: SAnime): String {
        val jsonUrl = json.decodeFromString<UrlContent>(anime.url)
        return jsonUrl.url
    }

    private val searchCache = java.util.Collections.synchronizedMap(HashMap<String, SearchCacheEntry>())

    private data class SearchCacheEntry(
        val page: AnimesPage,
        val timestamp: Long,
    )

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage = getSearch(page)

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        Log.d(ANIMEULTIME_LOG, "Fetching latest updates for page $page")
        val animeItems: List<SearchResponseItem> = Categories.entries.parallelMapNotNull { categorie ->
            val response = client.get("$baseUrl/$categorie.html")
            val document = response.asJsoup()

            document.select("div.slides li").mapNotNull { element ->
                SearchResponseItem(
                    id = element.selectFirst("div.box-hover")?.attr("onmousemove")?.filter { it.isDigit() }?.toIntOrNull() ?: return@mapNotNull null,
                    title = element.selectFirst(".box-text p")?.text() ?: element.selectFirst("p")?.text() ?: "",
                    img_url = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotEmpty() } ?: "",
                    url = element.selectFirst("a")?.attr("abs:href") ?: "",
                    searchType = categorie,
                )
            }
        }.flatten()
        val animePage = parseAnimes(animeItems)
        return paginateResult(animePage, page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = getSearch(page, query)

    // ============================ Utils =============================

    private suspend fun getSearch(page: Int, query: String = ""): AnimesPage {
        val now = System.currentTimeMillis()
        val cachedEntry = searchCache[query]

        if (cachedEntry != null && (now - cachedEntry.timestamp) < CACHE_EXPIRATION) {
            Log.d(ANIMEULTIME_LOG, "Cache HIT pour la recherche : '$query'")
            return paginateResult(cachedEntry.page, page)
        }

        val animeItems: List<SearchResponseItem> = Categories.entries.parallelFlatMap { categorie ->
            val formBody = FormBody.Builder().apply {
                if (categorie != Categories.OST) {
                    add("format[Episode]", "true")
                    add("format[Film]", "true")
                    add("format[OAV]", "true")
                }
                add("type", categorie.name)
                add("search", query)
            }.build()
            val response = client.post("$baseUrl/SeriesResults.html", headers, formBody)
            val responseBody = response.body.string()
            Log.d(ANIMEULTIME_LOG, "recherche dans $categorie réussie au post body = $responseBody")
            val searchResponse = json.decodeFromStringFixed<List<SearchResponse>>(responseBody)
            searchResponse.map { responseItem ->
                SearchResponseItem(
                    id = responseItem.id,
                    title = responseItem.title,
                    img_url = responseItem.img_url,
                    url = responseItem.url,
                    searchType = categorie,
                )
            }
        }

        val animePage = parseAnimes(animeItems)

        searchCache[query] = SearchCacheEntry(animePage, now)

        return paginateResult(animePage, page)
    }

    private fun paginateResult(fullPage: AnimesPage, page: Int): AnimesPage {
        val animes = fullPage.animes
        val fromIndex = (page - 1) * ITEMS_PER_PAGE

        if (fromIndex >= animes.size) {
            return AnimesPage(emptyList(), false)
        }

        val toIndex = minOf(fromIndex + ITEMS_PER_PAGE, animes.size)
        val hasNextPage = toIndex < animes.size

        val pagedAnimes = animes.subList(fromIndex, toIndex)

        return AnimesPage(pagedAnimes, hasNextPage)
    }

    private suspend fun parseAnimes(animeList: List<SearchResponseItem>): AnimesPage {
        val animes = animeList.parallelMapNotNull { animeItem ->
            val jsonUrl = json.encodeToString(UrlContent(id = animeItem.id, url = animeItem.url.safeRelativePath(baseUrl), searchType = animeItem.searchType))
            SAnime.create().apply {
                url = jsonUrl
                thumbnail_url = animeItem.img_url
                title = if (animeItem.searchType != Categories.OST) animeItem.title else "${animeItem.title} OST"
            }
        }
        return AnimesPage(animes, false)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val jsonUrl = json.decodeFromString<UrlContent>(anime.url)
        val id = jsonUrl.id

        val formBody = FormBody.Builder().apply {
            add("id", id.toString())
        }.build()
        val response = client.post("$baseUrl/SerieOverview.html", headers, formBody)
        val document = response.asJsoup()

        val productYear = document.selectFirst("li > span.alignleft:contains(Année de production) + span")?.text()?.trim() ?: ""
        val description = document.selectFirst("p")?.text()?.trim() ?: ""
        Log.d(ANIMEULTIME_LOG, "description = $description, productYear = $productYear")

        return anime.apply {
            anime.description = buildString {
                if (!productYear.isBlank()) append("Date de sortie : $productYear\n")
                if (!description.isBlank()) append(description)
            }
            anime.genre = document.selectFirst("li > span.alignleft:contains(Genre) + span")?.text()?.split("|")?.map { it.trim().lowercase() }?.joinToString() ?: ""
            anime.author = document.selectFirst("li > span.alignleft:contains(Studio) + span")?.text()?.trim() ?: ""
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val jsonUrl = json.decodeFromString<UrlContent>(anime.url)
        val id = jsonUrl.id
        val searchType = jsonUrl.searchType

        return (
            if (searchType == Categories.OST) {
                getEpisodeListAlbum(id)
            } else {
                getEpisodeListVideo(id)
            }
            ).asReversed()
    }

    private suspend fun getEpisodeListVideo(id: Int): List<SEpisode> {
        val formBody = FormBody.Builder().apply {
            add("idserie", id.toString())
        }.build()

        val response = client.post("$baseUrl/VideoPlayer.html", headers, formBody)

        val data = json.decodeFromStringFixed<PlayList>(response.body.string())

        val episodesGroupes = data.playlist.groupBy { it.title }

        return episodesGroupes.entries.parallelMap { (title, groupe) ->
            val idsFusionnes = groupe.map { "${it.id}:${it.quality}:$id" }.joinToString("|")
            SEpisode.create().apply {
                name = title
                url = "video#" + idsFusionnes
                preview_url = groupe[0].image
                summary = groupe[0].duration
            }
        }
    }

    private suspend fun getEpisodeListAlbum(id: Int): List<SEpisode> {
        val formBody = FormBody.Builder().apply {
            add("mode", "ost")
            add("id", id.toString())
        }.build()

        val response = client.post("$baseUrl/AudioPlayer.html", headers, formBody)

        val responseBody = response.body.string()

        val data = json.decodeFromStringFixed<AlbumResponse>(responseBody)

        val title = data.title

        val tracks = data.files

        return tracks.parallelMap { track ->
            SEpisode.create().apply {
                name = track.music_title
                url = "ost#" + json.encodeToString(track)
                preview_url = track.image
                summary = track.duration + "\n" + title
            }
        }
    }

    // ============================ Hoster =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = (
        if (episode.url.substringBefore("#") == "ost") {
            getHosterListAlbum(episode)
        } else {
            getHosterListVideo(episode)
        }
        )

    private suspend fun getHosterListAlbum(episode: SEpisode): List<Hoster> = listOf(
        Hoster(
            hosterName = "track",
            internalData = episode.url.substringAfter("#"),
        ),
    )

    private suspend fun getHosterListVideo(episode: SEpisode): List<Hoster> {
        val urls = episode.url.substringAfter("#").split("|")
        Log.d(ANIMEULTIME_LOG, "urls = ${episode.url}, name = ${episode.name}")
        return urls.parallelMap { idsRaw ->
            Log.d(ANIMEULTIME_LOG, "ici")
            val ids = idsRaw.split(":")

            val fileId = ids[0]
            val quality = ids[1]
            val serieId = ids[2]

            val formBody = FormBody.Builder().apply {
                add("idserie", serieId)
                add("focusFile", fileId)
            }.build()

            val response = client.post("$baseUrl/VideoPlayer.html", headers, formBody)

            val responseBody = response.body.string()

            val data = json.decodeFromStringFixed<VideoPlayerResponse>(responseBody)

            val fansub = parse(data.fansub_link).selectFirst("a")?.text()

            Hoster(
                hosterName = if (!fansub.isNullOrBlank()) fansub else "Unknown",
                internalData = responseBody,
            )
        }
    }

    // ============================ Video =============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> = (
        if (hoster.hosterName == "track") {
            getVideoListAlbum(hoster)
        } else {
            getVideoListVideo(hoster)
        }
        )

    private suspend fun getVideoListAlbum(hoster: Hoster): List<Video> {
        val track = json.decodeFromStringFixed<Track>(hoster.internalData)
        return listOf(
            Video(
                videoUrl = track.url,
                videoTitle = track.music_title,
                preferred = true,
            ),
        )
    }

    private suspend fun getVideoListVideo(hoster: Hoster): List<Video> {
        val fansub = hoster.hosterName
        val data = json.decodeFromStringFixed<VideoPlayerResponse>(hoster.internalData)

        val mp4Url = data.videoData.mp4?.url ?: ""
        Log.d(ANIMEULTIME_LOG, "url = $mp4Url")

        val extractedSource = ExtractedSource(
            url = mp4Url,
            quality = data.quality,
        )

        return listOf(extractedSource.buildFromSource(null, "AnimeUltime"))
    }
}
