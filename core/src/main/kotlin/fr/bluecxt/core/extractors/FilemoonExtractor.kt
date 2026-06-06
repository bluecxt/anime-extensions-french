package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.FILEMOON_LOG
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.network.WebViewSniffer
import fr.bluecxt.core.utils.PlaylistUtils
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

class FilemoonExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, headers: Headers? = null): List<ExtractedSource> {
        val httpUrl = url.toHttpUrl()
        val host = httpUrl.host
        val id = httpUrl.pathSegments.last()

        val baseHeaders = (headers?.newBuilder() ?: Headers.Builder())
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
            .set("Referer", "https://$host/")
            .build()

        return try {
            // 1. On récupère les détails pour trouver l'URL de l'iframe réelle (nzn3.org, etc.)
            val detailsUrl = "https://$host/api/videos/$id/embed/details"
            val detailsResponse = client.newCall(GET(detailsUrl, baseHeaders)).execute()

            val targetUrl = if (detailsResponse.isSuccessful) {
                val json = JSONObject(detailsResponse.body.string())
                json.optString("embed_frame_url", url)
            } else {
                url
            }

            Log.d(FILEMOON_LOG, "Sniffing sur : $url")

            // 2. On lance le sniffing sur l'URL de base (le bouton Play y sera accessible via injection d'iframe)
            val m3u8Url = WebViewSniffer.sniff(client, url, {
                it.contains(".m3u8", ignoreCase = true) && !it.contains("iframes", ignoreCase = true)
            })

            if (m3u8Url != null) {
                Log.d(FILEMOON_LOG, "Succès ! m3u8 trouvé : $m3u8Url")

                // On utilise les headers du domaine de l'iframe pour le CDN
                val finalHeaders = baseHeaders.newBuilder()
                    .set("Origin", "https://${targetUrl.toHttpUrl().host}")
                    .set("Referer", "https://${targetUrl.toHttpUrl().host}/")
                    .build()

                playlistUtils.extractFromHls(
                    playlistUrl = m3u8Url,
                    referer = "https://${targetUrl.toHttpUrl().host}/",
                    masterHeaders = finalHeaders,
                    videoHeaders = finalHeaders,
                )
            } else {
                Log.e(FILEMOON_LOG, "Échec de l'interception m3u8 (Timeout)")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(FILEMOON_LOG, "Erreur Filemoon: ${e.message}")
            emptyList()
        }
    }
}
