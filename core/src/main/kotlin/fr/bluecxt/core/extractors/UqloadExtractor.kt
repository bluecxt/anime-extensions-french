// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.UQLOAD_LOG
import fr.bluecxt.core.model.ContentUnavailableException
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.model.ExtractionException
import fr.bluecxt.core.utils.awaitSuccessOrUnavailable
import fr.bluecxt.core.utils.defaultHeaders
import fr.bluecxt.core.utils.unpacker.autoUnpacker
import fr.bluecxt.core.utils.video.PlaylistUtils
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

/**
 * Fabrique centralisée des échecs d'extraction, pour limiter le nombre de `throw` par fonction.
 */
private fun extractionFailure(message: String): Nothing = throw ExtractionException(message)

// writed using https://github.com/skoruppa/docchi-players/blob/main/uqload.py

class UqloadExtractor(private val client: OkHttpClient) {

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        // Uqload refuse un Referer venu d'un autre domaine que le sien : la page répond alors
        // « Video embed restricted for this domain ». Sans Referer, la page complète est servie.
        val headers = defaultHeaders(
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        )

        val streamingHeaders = defaultHeaders()

        // Le script JWPlayer vit désormais sur la page `embed-…` ; les anciens liens gardaient
        // leurs sources sur la page sans ce préfixe, donc les deux sont essayées.
        val candidates = listOf(url, url.replace("embed-", "")).distinct()
        var playerPage: Document? = null
        var script = ""

        for (candidate in candidates) {
            val (page, foundScript) = loadPlayerPage(candidate, headers)
            playerPage = page
            if (foundScript.isNotEmpty()) {
                script = foundScript
                break
            }
        }

        if (script.isEmpty()) extractionFailure("Could not find script with video data in Uqload")

        val unpackedJs = autoUnpacker(script) ?: extractionFailure("Could not unpack script in Uqload")

        val m3u8Master = Regex("""sources:\s*\[\{\s*file:\s*"([^"]+)"""").find(unpackedJs)?.groupValues?.get(1)

        if (m3u8Master != null) {
            return try {
                PlaylistUtils(client).extractFromHls(
                    playlistUrl = m3u8Master,
                    masterHeaders = streamingHeaders,
                    videoHeaders = streamingHeaders,
                )
            } catch (e: ContentUnavailableException) {
                throw e
            } catch (e: Exception) {
                Log.e(UQLOAD_LOG, "Error parsing HLS playlist", e)
                listOf(
                    ExtractedSource(
                        url = m3u8Master,
                        headers = streamingHeaders,
                    ),
                )
            }
        }

        // Repli MP4 pour les anciennes pages.
        val mp4Regex = Regex("""sources:\s*\["(https?.*?\.mp4)"\]""")
        val scripts = playerPage?.select("script") ?: emptyList()
        for (scriptTag in scripts) {
            val content = scriptTag.data()
            if (content.contains("sources:")) {
                mp4Regex.find(content)?.groupValues?.get(1)?.let { mp4Url ->
                    return listOf(
                        ExtractedSource(
                            url = mp4Url,
                            headers = streamingHeaders,
                        ),
                    )
                }
            }
        }

        extractionFailure("Uqload: No video sources found (HLS or MP4)")
    }

    /**
     * Charge une page de lecteur et en extrait le script JWPlayer.
     *
     * @param url page à charger.
     * @param headers en-têtes de la requête.
     * @return la page et le script packé, vide quand la page n'en contient pas.
     */
    private suspend fun loadPlayerPage(url: String, headers: Headers): Pair<Document, String> {
        val response = client.newCall(GET(url, headers)).awaitSuccessOrUnavailable(url)
        val finalUrl = response.request.url
        val path = finalUrl.encodedPath
        if (path == "/" || path.isEmpty()) {
            throw ContentUnavailableException("Uqload: Video not found (redirected to host homepage: ${finalUrl.host})")
        }
        val page = response.useAsJsoup()
        if (page.text().contains(RESTRICTED_MARKER, ignoreCase = true)) {
            throw ContentUnavailableException("Uqload: embed restricted for this domain")
        }
        val script = page.selectFirst("script:containsData(eval):containsData(m3u8)")?.data().orEmpty()
        return page to script
    }

    /** Texte servi par Uqload quand le Referer ne lui plaît pas. */
    private companion object {
        const val RESTRICTED_MARKER = "embed restricted"
    }
}
