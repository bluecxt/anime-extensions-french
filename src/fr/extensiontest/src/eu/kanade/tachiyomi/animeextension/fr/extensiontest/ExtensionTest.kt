// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.extensiontest

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.EXTENSIONTEST_LOG
import fr.bluecxt.core.Source
import fr.bluecxt.core.extractors.UqloadExtractor
import fr.bluecxt.core.monitoring.SourceAuditor
import fr.bluecxt.core.utils.JsoupExtensions
import keiyoushi.utils.parallelCatchingFlatMap
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class ExtensionTest :
    Source(),
    CommonPreferences,
    JsoupExtensions {
    override val name = "Extension-Test"
    override val defaultBaseUrl = "https://example.com"
    override val supportedServers = listOf(
        "Embed4me", "Filemoon", "GoogleDrive", "Minochinos", "Sendvid",
        "Sibnet", "Vidmoly", "Vk", "Waveplayer", "Okru", "Doodstream", "Voe",
        "Vidoza", "Uqload", "Lulu", "Streamtape", "SouthTV", "Cda", "Mp4upload",
        "Streamup", "Vidguard", "Lycoris", "Pixeldrain", "Abstream", "Rumble",
        "Abyss", "Buzz", "Earnvid", "Hqq", "Dailymotion",
        "Upstream", "StreamHide", "StreamVid", "StreamHub", "StreamDav",
    )

    override val baseUrl: String get() = defaultBaseUrl
    override val lang = "fr"
    override val supportsLatest = false

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(
        listOf(
            SAnime.create().apply {
                title = "Test Observabilité & Monitoring (Tous les types d'erreurs)"
                url = "/test-monitoring"
                thumbnail_url = "https://github.com/bluecxt/anime-extensions-french/raw/refs/heads/main/assets/repo_logo.svg"
            },
            SAnime.create().apply {
                title = "Test Extracteurs & Hosters (Lazy / Non-Lazy)"
                url = "/test-extractors"
                thumbnail_url = "https://github.com/bluecxt/anime-extensions-french/raw/refs/heads/main/assets/repo_logo.svg"
            },
        ),
        false,
    )

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime.apply {
        description = if (anime.url == "/test-monitoring") {
            "Scénarios complets pour tester l'ensemble des types d'erreurs remontés vers l'Observability Hub (n8n):\n" +
                "• HTTP 500 / 403\n" +
                "• DNS_FAILURE\n" +
                "• SSL_ERROR\n" +
                "• TIMEOUT\n" +
                "• SELECTOR_ERROR (Jsoup)\n" +
                "• SourceAuditor (Données incomplètes)\n" +
                "• Custom Diagnostic (sendErrorWebhook)\n" +
                "• Rafale complète (Batch)"
        } else {
            buildString {
                val char1 = "✦"
                val char2 = "⫻"
                append("Uqload $char1 802p $char2 23.98fps\n")
                append("Sendvid $char1 1080p $char2 60fps\n")
                append("Sibnet $char1 720p $char2 60fps\n")
                append("Vidmoly $char1 1080p\n")
                append("Vk $char1 60fps")
            }
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = when (anime.url) {
        "/test-monitoring" -> listOf(
            SEpisode.create().apply {
                name = "1. Erreur HTTP 500 (Internal Server Error)"
                url = "/error-http-500"
                episode_number = 1f
            },
            SEpisode.create().apply {
                name = "2. Erreur HTTP 403 (Forbidden / Cloudflare)"
                url = "/error-http-403"
                episode_number = 2f
            },
            SEpisode.create().apply {
                name = "3. Échec DNS (DNS_FAILURE - Domaine inexistant)"
                url = "/error-dns-failure"
                episode_number = 3f
            },
            SEpisode.create().apply {
                name = "4. Erreur SSL (SSL_ERROR - Certificat auto-signé)"
                url = "/error-ssl"
                episode_number = 4f
            },
            SEpisode.create().apply {
                name = "5. Timeout Réseau (TIMEOUT - Délai dépassé)"
                url = "/error-timeout"
                episode_number = 5f
            },
            SEpisode.create().apply {
                name = "6. Erreur Sélecteur Jsoup (SELECTOR_ERROR)"
                url = "/error-selector"
                episode_number = 6f
            },
            SEpisode.create().apply {
                name = "7. Audit Métadonnées (SourceAuditor Incomplétude)"
                url = "/error-auditor"
                episode_number = 7f
            },
            SEpisode.create().apply {
                name = "8. Erreur Custom avec Exception (sendErrorWebhook direct)"
                url = "/error-custom"
                episode_number = 8f
            },
            SEpisode.create().apply {
                name = "9. 🔥 DÉCLENCHER TOUT EN RAFALE (Batch All Errors)"
                url = "/error-batch-all"
                episode_number = 9f
            },
        )

        else -> listOf(
            SEpisode.create().apply {
                name = "Épisode 1 - Test Hosters LAZY (Délais 3s à la demande)"
                url = "/episode-lazy"
                episode_number = 1f
            },
            SEpisode.create().apply {
                name = "Épisode 2 - Test Hosters NON-LAZY (Délais 3s immédiat)"
                url = "/episode-non-lazy"
                episode_number = 2f
            },
        )
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        if (episode.url.startsWith("/error-")) {
            val errorKey = episode.url.removePrefix("/error-")
            return listOf(
                Hoster(
                    hosterName = "⚡ Déclencher l'erreur : $errorKey",
                    internalData = errorKey,
                    lazy = false,
                ),
            )
        }

        val isLazy = episode.url == "/episode-lazy"
        return listOf(
            Hoster(
                hosterName = "Serveur Rapide (Sibnet)",
                internalData = "fast",
                lazy = isLazy,
            ),
            Hoster(
                hosterName = "Serveur Lent 1 (DoodStream - 3s delay)",
                internalData = "slow_1",
                lazy = isLazy,
            ),
            Hoster(
                hosterName = "Serveur Lent 2 (Voe - 4s delay)",
                internalData = "slow_2",
                lazy = isLazy,
            ),
            Hoster(
                hosterName = "Serveur Très Lent (Filemoon - 6s delay)",
                internalData = "slow_3",
                lazy = isLazy,
            ),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        Log.d("ExtensionTest", "getVideoList appelé pour : ${hoster.hosterName} (internalData=${hoster.internalData})")

        // Traitement des tests d'observabilité / erreurs
        val errorResult = when (hoster.internalData) {
            "http-500" -> triggerHttp500()
            "http-403" -> triggerHttp403()
            "dns-failure" -> triggerDnsFailure()
            "ssl" -> triggerSslError()
            "timeout" -> triggerTimeout()
            "selector" -> triggerSelectorError()
            "auditor" -> triggerSourceAuditor()
            "custom" -> triggerCustomError()
            "batch-all" -> triggerBatchAll()
            else -> null
        }

        if (errorResult != null) {
            Log.i("ExtensionTest", "Résultat test erreur [${hoster.internalData}] : $errorResult")
            error("✅ $errorResult")
        }

        // Simulation de délais réseau pour tests extracteurs
        when (hoster.internalData) {
            "fast" -> {
                kotlinx.coroutines.delay(300)
                return listOf(Video(videoUrl = "https://example.com/fast.mp4", videoTitle = "Sibnet 720p (300ms)"))
            }

            "slow_1" -> {
                Log.d("ExtensionTest", "Début extraction DoodStream (3s)...")
                kotlinx.coroutines.delay(3000)
                Log.d("ExtensionTest", "Fin extraction DoodStream")
                return listOf(Video(videoUrl = "https://example.com/slow1.mp4", videoTitle = "DoodStream 1080p (3s)"))
            }

            "slow_2" -> {
                Log.d("ExtensionTest", "Début extraction Voe (4s)...")
                kotlinx.coroutines.delay(4000)
                Log.d("ExtensionTest", "Fin extraction Voe")
                return listOf(Video(videoUrl = "https://example.com/slow2.mp4", videoTitle = "Voe HD (4s)"))
            }

            "slow_3" -> {
                Log.d("ExtensionTest", "Début extraction Filemoon (6s)...")
                kotlinx.coroutines.delay(6000)
                Log.d("ExtensionTest", "Fin extraction Filemoon")
                return listOf(Video(videoUrl = "https://example.com/slow3.mp4", videoTitle = "Filemoon 1080p (6s)"))
            }

            else -> {
                kotlinx.coroutines.delay(1000)
                return listOf(Video(videoUrl = "https://example.com/default.mp4", videoTitle = "Défaut 720p"))
            }
        }
    }

    // ====================== Méthodes de déclenchement d'erreurs ======================

    private fun triggerHttp500(): String = try {
        client.newCall(Request.Builder().url("https://httpbin.org/status/500").build()).execute().close()
        "HTTP 500 intercepté et envoyé au webhook"
    } catch (e: Exception) {
        "HTTP 500 déclenché (${e.message})"
    }

    private fun triggerHttp403(): String = try {
        client.newCall(Request.Builder().url("https://httpbin.org/status/403").build()).execute().close()
        "HTTP 403 intercepté et envoyé au webhook"
    } catch (e: Exception) {
        "HTTP 403 déclenché (${e.message})"
    }

    private fun triggerDnsFailure(): String = try {
        client.newCall(Request.Builder().url("https://invalid-monitoring-dns-test.bluecxt/").build()).execute().close()
        "DNS Failure (inattendu: réussite)"
    } catch (e: Exception) {
        "DNS_FAILURE intercepté et envoyé au webhook (${e.javaClass.simpleName})"
    }

    private fun triggerSslError(): String = try {
        client.newCall(Request.Builder().url("https://self-signed.badssl.com/").build()).execute().close()
        "SSL Error (inattendu: réussite)"
    } catch (e: Exception) {
        "SSL_ERROR intercepté et envoyé au webhook (${e.javaClass.simpleName})"
    }

    private fun triggerTimeout(): String = try {
        client.newBuilder()
            .callTimeout(50, TimeUnit.MILLISECONDS)
            .build()
            .newCall(Request.Builder().url("https://httpbin.org/delay/2").build())
            .execute().close()
        "Timeout (inattendu: réussite)"
    } catch (e: Exception) {
        "TIMEOUT intercepté et envoyé au webhook (${e.javaClass.simpleName})"
    }

    private fun triggerSelectorError(): String {
        Jsoup.parse("<html><body><div id='real'>Contenu</div></body></html>")
            .selectFirstLog("div.inexistant-selector#missing-id")
        return "SELECTOR_ERROR envoyé au webhook via JsoupExtensions"
    }

    private fun triggerSourceAuditor(): String {
        val dummyAnime = SAnime.create().apply {
            title = "Test Incomplet Auditor"
            url = "/incomplet-test"
        }
        SourceAuditor.run {
            dummyAnime.checkAndReportIncompleteness(baseUrl, "/incomplet-test", currentName, currentVersion)
            listOf<SEpisode>().checkAndReportEpisodeIssues(baseUrl, "/incomplet-test", "Test Incomplet Auditor", currentName, currentVersion)
            listOf(Video("invalid_video_url_test", "Test Video")).checkAndReportVideoIssues(baseUrl, "/incomplet-test", "HosterTest", currentName, currentVersion)
        }
        return "SourceAuditor rapports envoyés au webhook"
    }

    private fun triggerCustomError(): String {
        sendErrorWebhook(
            url = "$baseUrl/test-custom-diagnostic",
            context = "Test diagnostic direct déclenché depuis ExtensionTest",
            exception = IllegalStateException("Test exception diagnostic avec stacktrace"),
        )
        return "Custom Diagnostic envoyé au webhook"
    }

    private fun triggerBatchAll(): String {
        val results = listOf(
            triggerHttp500(),
            triggerHttp403(),
            triggerDnsFailure(),
            triggerSslError(),
            triggerTimeout(),
            triggerSelectorError(),
            triggerSourceAuditor(),
            triggerCustomError(),
        )
        return "Rafale terminée (${results.size} types d'erreurs déclenchés)"
    }

    // Dummy implementations for unused methods
    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)
    override fun latestUpdatesRequest(page: Int): Request = Request.Builder().url(defaultBaseUrl).build()
    override fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = Request.Builder().url(defaultBaseUrl).build()
}
