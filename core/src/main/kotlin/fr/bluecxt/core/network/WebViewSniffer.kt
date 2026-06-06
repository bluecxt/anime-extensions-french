package fr.bluecxt.core.network

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import fr.bluecxt.core.WEBVIEW_SNIFFER
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object WebViewSniffer {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun sniff(client: OkHttpClient, url: String, targetPredicate: (String) -> Boolean, timeoutSeconds: Long = 60): String? {
        val latch = CountDownLatch(1)
        var interceptedUrl: String? = null
        var webView: WebView? = null

        Log.d(WEBVIEW_SNIFFER, "--- Sniffing Start: $url ---")

        handler.post {
            try {
                val view = WebView(context)
                webView = view

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

                val settings = view.settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                settings.mediaPlaybackRequiresUserGesture = false

                view.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        Log.d(WEBVIEW_SNIFFER, "[JS] ${consoleMessage?.message()}")
                        return true
                    }
                }

                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val requestUrl = request?.url?.toString() ?: ""

                        // On attrape le m3u8 dès qu'il sort du PoW
                        if (targetPredicate(requestUrl)) {
                            Log.d(WEBVIEW_SNIFFER, "!!! TARGET MATCHED !!! -> $requestUrl")
                            interceptedUrl = requestUrl
                            latch.countDown()
                        }

                        // On n'injecte QUE dans les pages HTML réelles (pas les API JSON)
                        val isPotentialHtml = (requestUrl.contains("/e/") || requestUrl.contains("/k8hn/") || requestUrl.contains("nzn3.org")) &&
                            !requestUrl.contains("/api/") && !requestUrl.contains("assets")

                        if (isPotentialHtml && requestUrl != url) {
                            try {
                                val reqHeaders = okhttp3.Headers.Builder()
                                request?.requestHeaders?.forEach { (k, v) -> reqHeaders.add(k, v) }
                                val response = client.newCall(Request.Builder().url(requestUrl).headers(reqHeaders.build()).build()).execute()

                                if (response.header("Content-Type")?.contains("text/html") == true) {
                                    val html = response.body.string()
                                    val injectedHtml = html.replace(
                                        "</body>",
                                        """
                                        <script>
                                            console.log("Sniffer: Clicker active in iframe");
                                            const interval = setInterval(() => {
                                                const btn = document.querySelector('button.captcha-gate__play') || document.querySelector('.play-button');
                                                if (btn) {
                                                    btn.click();
                                                    console.log("Sniffer: Play clicked");
                                                    clearInterval(interval);
                                                }
                                            }, 500);
                                        </script>
                                        </body>
                                        """.trimIndent(),
                                    )
                                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(injectedHtml.toByteArray()))
                                }
                            } catch (e: Exception) { }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                view.loadUrl(url)
            } catch (e: Exception) {
                latch.countDown()
            }
        }

        latch.await(timeoutSeconds, TimeUnit.SECONDS)
        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
        return interceptedUrl
    }
}
