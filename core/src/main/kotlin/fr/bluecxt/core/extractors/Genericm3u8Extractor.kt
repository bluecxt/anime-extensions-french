package fr.bluecxt.core.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import fr.bluecxt.core.GENERIC_M3U8_LOG
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Genericm3u8Extractor(private val client: OkHttpClient) {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, headers: Headers? = null): List<ExtractedSource> {
        val httpUrl = url.toHttpUrl()
        val host = httpUrl.host

        Log.d(GENERIC_M3U8_LOG, "🚀 [START] Début de l'extraction pour: $url")

        // 1. Sniffing du flux m3u8 via WebView
        val m3u8Url = sniffM3u8(url)

        return if (m3u8Url != null) {
            Log.d(GENERIC_M3U8_LOG, "✅ [MATCH] Flux intercepté: $m3u8Url")

            val finalHeaders = (headers?.newBuilder() ?: Headers.Builder())
                .set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:151.0) Gecko/20100101 Firefox/151.0")
                .set("Origin", "https://$host")
                .set("Referer", "https://$host/")
                .build()

            playlistUtils.extractFromHls(
                playlistUrl = m3u8Url,
                referer = "https://$host/",
                masterHeaders = finalHeaders,
                videoHeaders = finalHeaders,
            )
        } else {
            Log.e(GENERIC_M3U8_LOG, "❌ [ERROR] Échec de l'interception (Timeout ou blocage)")
            emptyList()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun sniffM3u8(url: String, timeoutSeconds: Long = 35): String? {
        val latch = CountDownLatch(1)
        var interceptedUrl: String? = null
        var webView: WebView? = null

        handler.post {
            try {
                val view = WebView(context)
                webView = view

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

                with(view.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                    mediaPlaybackRequiresUserGesture = false
                }

                view.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                        Log.d(GENERIC_M3U8_LOG, "🌐 [JS] ${cm?.message()}")
                        return true
                    }
                }

                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""

                        // Capture du graal (.m3u8)
                        if (reqUrl.contains(".m3u8") && !reqUrl.contains("iframes")) {
                            interceptedUrl = reqUrl
                            latch.countDown()
                            return super.shouldInterceptRequest(view, request)
                        }

                        // Debug des étapes de sécurité Filemoon
                        if (reqUrl.contains("access/challenge")) Log.d(GENERIC_M3U8_LOG, "🔐 [SEC] Calcul du PoW détecté...")
                        if (reqUrl.contains("access/attest")) Log.d(GENERIC_M3U8_LOG, "🔓 [SEC] PoW Validé par le serveur !")

                        // Injection du clic dans l'iframe au vol
                        val isPotentialHtml = (reqUrl.contains("/e/") || reqUrl.contains("/k8hn/") || reqUrl.contains("nzn3.org")) &&
                            !reqUrl.contains("/api/") && !reqUrl.contains("assets") &&
                            !reqUrl.endsWith(".js") && !reqUrl.endsWith(".css") && !reqUrl.endsWith(".png")

                        if (isPotentialHtml && reqUrl != url) {
                            try {
                                val response = client.newCall(Request.Builder().url(reqUrl).build()).execute()
                                if (response.header("Content-Type")?.contains("text/html") == true) {
                                    Log.d(GENERIC_M3U8_LOG, "💉 [INJECT] Iframe HTML identifiée : $reqUrl")
                                    val html = response.body.string()

                                    val injectedHtml = html.replace(
                                        "</body>",
                                        """
                                        <script>
                                            console.log("AutoClick: Scanning for play button...");
                                            const interval = setInterval(() => {
                                                const btn = document.querySelector('button.captcha-gate__play') || document.querySelector('.play-button');
                                                if (btn) {
                                                    console.log("AutoClick: Button found, clicking!");
                                                    btn.click();
                                                    clearInterval(interval);
                                                }
                                            }, 500);
                                        </script>
                                        </body>
                                        """.trimIndent(),
                                    )
                                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(injectedHtml.toByteArray()))
                                }
                            } catch (e: Exception) {
                                Log.e(GENERIC_M3U8_LOG, "⚠️ [INJECT] Erreur injection: ${e.message}")
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        Log.d(GENERIC_M3U8_LOG, "📄 [LOAD] Page principale chargée.")
                    }
                }
                view.loadUrl(url)
            } catch (e: Exception) {
                Log.e(GENERIC_M3U8_LOG, "💥 [CRASH] WebView: ${e.message}")
                latch.countDown()
            }
        }

        latch.await(timeoutSeconds, TimeUnit.SECONDS)
        handler.post {
            webView?.stopLoading()
            webView?.destroy()
        }
        return interceptedUrl
    }
}
