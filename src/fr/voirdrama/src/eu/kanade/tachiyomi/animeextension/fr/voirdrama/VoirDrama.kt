// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.voirdrama

import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.VOIRDRAMA_LOG
import fr.bluecxt.core.tvdb.fetchTvdbMetadata
import fr.bluecxt.core.utils.safeRelativePath
import keiyoushi.utils.get
import keiyoushi.utils.head
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class VoirDrama : Madara("VoirDrama", "https://voirdrama.to", "fr") {

    override val supportedServers = listOf("Vidmoly", "Mymail", "Voe")
    override val supportedVoices: Array<String> = arrayOf("VF", "VOSTFR")

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = super.getFilterList() + listOf(
        select(
            "Format",
            "type",
            arrayOf(
                "Tous" to "",
                "TV" to "TV",
                "Movie" to "MOVIE",
                "TV Short" to "TV SHORT",
                "OVA" to "OVA",
                "ONA" to "ONA",
                "Special" to "SPECIAL",
            ),
        ),
        select(
            "Langue",
            "lang",
            arrayOf(
                "Tous" to "",
                "VF" to "vf",
                "VOSTFR" to "vostfr",
            ),
        ),
        select(
            "Pays",
            "country",
            arrayOf(
                "Tous" to "",
                "Chine" to "China",
                "Hong Kong" to "Hong Kong",
                "Indonésie" to "Indonesia",
                "Japon" to "Japan",
                "Philippines" to "Philippines",
                "Singapour" to "Singapore",
                "Corée du Sud" to "South Korea",
                "Taïwan" to "Taiwan",
                "Thaïlande" to "Thailand",
                "Vietnam" to "Vietnam",
            ),
        ),
        separator,
        group("Genres", "genre[]", GENRE_LIST),
    )

    // ============================== Parsing ===============================
    override fun parseAnime(document: Document, animesSelector: String, nameSelector: String): AnimesPage {
        val page = super.parseAnime(document, animesSelector, nameSelector)
        val cleanedAnimes = page.animes.map { it.removeFrench() }.distinctBy { it.url }
        return AnimesPage(cleanedAnimes, page.hasNextPage)
    }

    // ============================== Anime Details ===============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = super.getAnimeDetails(anime).apply {
        val cleanTitle = title.dropLastWhile { it.isDigit() }.trim()
        val seasonNumber = title.takeLastWhile { it.isDigit() }.toIntOrNull() ?: 1
        val metadata = fetchTvdbMetadata(cleanTitle, seasonNumber) ?: return@apply

        if (description.isNullOrBlank() && !metadata.summary.isNullOrBlank()) {
            description = buildString {
                metadata.releaseDate?.takeIf { it.isNotBlank() }?.let { date ->
                    append("Date de sortie : ")
                    appendLine(date)
                    appendLine()
                }
                append(metadata.summary)
            }
        }

        if (thumbnail_url.isNullOrBlank()) {
            thumbnail_url = metadata.seasonPosterUrl ?: metadata.mainPosterUrl
        }
        if (genre.isNullOrBlank()) {
            genre = metadata.genre
        }
        if (author.isNullOrBlank()) {
            author = metadata.author
        }
        if (artist.isNullOrBlank()) {
            artist = metadata.artist
        }
        if (status == SAnime.UNKNOWN && metadata.status != 0) {
            status = metadata.status
        }
    }

    // ============================== Episodes ===============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = with(anime) {
        val document = client.get("$baseUrl$url", headers).asJsoup()
        val hasVf = itHasVf(url)

        parseEpisodes(document, hasVf)
    }

    private fun parseEpisodes(document: Document, hasVf: Boolean): List<SEpisode> = document.select("li.wp-manga-chapter").mapNotNull { element ->
        val link = element.selectFirst("> a")?.safeRelativePath() ?: return@mapNotNull null
        val episodeNumber = element.selectFirst("> a")?.text()?.substringAfterLast("-")?.trim() ?: ""
        val lang = if (hasVf) "VOSTFR, VF" else "VOSTFR"
        SEpisode.create().apply {
            url = link
            name = buildString {
                append("Épisode ")
                append(episodeNumber)
            }
            scanlator = lang
            date_upload = element.selectFirst(".chapter-release-date i")?.text()
                ?.let { dateFormat.tryParse(it) } ?: 0L
        }
    }

    private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US)

    // ============================== Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = with(episode) {
        return scanlator?.split(",")?.mapNotNull { lang ->
            Log.d(VOIRDRAMA_LOG, "lang = $lang")
            Hoster(
                hosterUrl = when (lang.trim()) {
                    "VOSTFR" -> url
                    "VF" -> url.replace("-vostfr", "-vf").replaceFirst(dramaSlugRegex, "$1-vf")
                    else -> return@mapNotNull null
                },
                hosterName = lang.trim(),
            )
        } ?: emptyList()
    }

    // ============================== Videos ===============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> = with(hoster) {
        val html = client.get("$baseUrl$hosterUrl", headers).body.string()
        Log.d(VOIRDRAMA_LOG, "url = $baseUrl$hosterUrl")

        sourcesRegex.find(html)?.let { match ->
            iframeSrcRegex.findAll(match.groupValues[1])
                .toList()
                .parallelCatchingFlatMap {
                    val capturedUrl = it.groupValues[1].replace("\\/", "/")
                    extractVideos(capturedUrl, hosterName, supportedServers)
                }
        } ?: emptyList()
    }

    // ============================== Utils ===============================
    private fun SharedPreferences.getBooleanOrNull(key: String): Boolean? = if (contains(key)) getBoolean(key, false) else null

    private suspend fun itHasVf(url: String, vararg bonusString: String): Boolean = preferences.getBooleanOrNull("$HAS_VF_KEY$url")
        ?: true.takeIf { url.contains("-vf", ignoreCase = true) }
        ?: true.takeIf { bonusString.any { it.contains("(VF)", ignoreCase = true) } }
        ?: client.head("$baseUrl${url.removeSuffix("/")}-vf", headers).use { it.isSuccessful }
            .also { it.setVf(url) }

    private fun Boolean.setVf(url: String): Unit = preferences.edit().putBoolean("$HAS_VF_KEY$url", this).apply()

    private fun SAnime.removeFrench(): SAnime = apply {
        if (url.contains("-vf", ignoreCase = true) || title.contains("(VF)", ignoreCase = true)) {
            url = url.replace("-vf", "", ignoreCase = true)
            title = title.replace("(VF)", "", ignoreCase = true).trim()
            true.setVf(url)
        }
    }

    companion object {
        private val sourcesRegex = Regex("""var\s+thisChapterSources\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        private val iframeSrcRegex = Regex("""src=\\?["'](.+?)\\?["']""")

        private val dramaSlugRegex = Regex("""(/drama/[^/]+)""")

        const val HAS_VF_KEY = "meta_has_vf_"
        private val scope = CoroutineScope(Dispatchers.IO)

        private val GENRE_LIST = listOf(
            "Action" to "action", "Affaires" to "affaires", "Amitié" to "amitie", "Arts martiaux" to "arts-martiaux",
            "Aventure" to "aventure", "Comédie" to "comedie", "Contexte scolaire" to "contexte-scolaire", "Crime" to "crime",
            "Culinaire" to "culinaire", "Documentaire" to "documentaire", "Drame" to "drame", "Famille" to "famille",
            "Fantastique" to "fantastique", "Guerre" to "guerre", "Historique" to "historique", "Horreur" to "horreur",
            "Jeunesse" to "jeunesse", "Judiciaire" to "judiciaire", "Mature" to "mature", "Médical" to "medical",
            "Mélodrame" to "melodrame", "Militaire" to "militaire", "Musique" to "musique", "Mystère" to "mystere",
            "Politique" to "politique", "Psychologique" to "psychologique", "Romance" to "romance", "SF" to "sf",
            "Sitcom" to "sitcom", "Sport" to "sport", "Surnaturel" to "surnaturel", "Thriller" to "thriller",
            "Tokusatsu" to "tokusatsu", "Vie quotidienne" to "vie-quotidienne", "Wuxia" to "wuxia",
        )
    }
}
