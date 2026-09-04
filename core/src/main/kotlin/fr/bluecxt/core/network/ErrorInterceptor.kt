package fr.bluecxt.core.network

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import fr.bluecxt.core.monitoring.ErrorWebhook
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.http2.StreamResetException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

const val INTERCEPTOR_VERSION = "1.0"

/**
 * Network error monitoring interceptor.
 *
 * Captures HTTP non-2xx responses and unhandled [IOException] instances, filtering out
 * transient client-side network state drops (such as Wi-Fi/Cellular switching or airplane mode toggles),
 * and dispatches diagnostic error reports to [ErrorWebhook].
 *
 * @property sourceName Optional human-readable name of the originating extension.
 * @property sourceVersion Optional current version string of the originating extension.
 * @property isCustomDomain Optional lambda returning true if the current source domain is user-customized.
 */
class ErrorInterceptor(
    private val sourceName: String? = null,
    private val sourceVersion: String? = null,
    private val isCustomDomain: (() -> Boolean)? = null,
) : Interceptor {

    /**
     * Checks whether the device currently possesses an active and validated internet connection.
     *
     * Queries Android's [ConnectivityManager] via Injekt's application context to verify that the
     * active network has both [NetworkCapabilities.NET_CAPABILITY_INTERNET] and
     * [NetworkCapabilities.NET_CAPABILITY_VALIDATED].
     *
     * @return `true` if internet connectivity is confirmed or if status cannot be determined; `false` otherwise.
     */
    private fun isDeviceOnline(): Boolean {
        return try {
            val app = Injekt.get<Application>()
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Intercepts HTTP requests to monitor for failures, filter transient client disconnects,
     * and log error telemetries.
     *
     * @param chain The OkHttp interceptor chain.
     * @return The HTTP response if successfully processed.
     * @throws IOException If the request fails or network error occurs.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Bypass completely if the user is running a customized base URL
        if (isCustomDomain?.invoke() == true) {
            return chain.proceed(request)
        }

        try {
            val response = chain.proceed(request)

            val code = response.code
            if (!response.isSuccessful && code != 404 && code !in 300..399 && code !in 502..504) {
                val responseBody = response.peekBody(512).string().take(200).ifBlank { null }
                ErrorWebhook.sendWebhook(
                    baseUrl = request.url.host,
                    url = request.url.toString(),
                    additionalContext = listOfNotNull(
                        "HTTP_ERROR_$code",
                        "method=${request.method}",
                        responseBody?.let { "body=$it" },
                    ),
                    extensionName = sourceName,
                    extensionVersion = sourceVersion,
                )
            }

            return response
        } catch (e: IOException) {
            // 1. If the device itself is offline -> Ignore the webhook
            if (!isDeviceOnline()) throw e

            // 2. Filter transient network/socket exceptions based on their specific exception types
            val isTransientOrLocal = when {
                e is StreamResetException -> true

                e is InterruptedIOException -> e !is SocketTimeoutException

                e is SocketException -> {
                    val msg = e.message.orEmpty().lowercase()
                    msg.contains("socket closed") ||
                        msg.contains("connection reset") ||
                        msg.contains("broken pipe") ||
                        msg.contains("shutdown")
                }

                e.message?.contains("canceled", ignoreCase = true) == true -> true

                else -> false
            }

            if (isTransientOrLocal) throw e

            // 3. Categorize true server-side/domain failures
            val errorType = when (e) {
                is UnknownHostException -> "DNS_FAILURE"
                is SSLException -> "SSL_ERROR"
                is SocketTimeoutException -> "TIMEOUT"
                else -> "NETWORK_ERROR"
            }

            ErrorWebhook.sendWebhook(
                baseUrl = request.url.host,
                url = request.url.toString(),
                additionalContext = listOfNotNull(
                    errorType,
                    e.message,
                    e.cause?.message,
                ),
                extensionName = sourceName,
                extensionVersion = sourceVersion,
            )

            throw e
        }
    }
}
