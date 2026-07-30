package fr.bluecxt.core.network

import fr.bluecxt.core.monitoring.ErrorWebhook
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class ErrorInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        try {
            val response = chain.proceed(request)

            val code = response.code
            if (!response.isSuccessful && code != 404 && code !in 300..399 && code !in 502..504) {
                val responseBody = response.peekBody(512).string().take(200).ifBlank { null }
                ErrorWebhook.sendWebhook(
                    baseUrl = request.url.host,
                    url = url,
                    additionalContext = listOfNotNull(
                        "HTTP_ERROR_$code",
                        "method=${request.method}",
                        responseBody?.let { "body=$it" },
                    ),
                )
            }

            return response
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            val isCanceled = msg.contains("canceled", ignoreCase = true) ||
                msg.contains("cancelled", ignoreCase = true) ||
                msg.contains("socket closed", ignoreCase = true)

            if (isCanceled) throw e

            val errorType = when (e) {
                is java.net.SocketTimeoutException -> "TIMEOUT"
                is java.net.UnknownHostException -> "DNS_FAILURE"
                else -> "NETWORK_ERROR"
            }

            ErrorWebhook.sendWebhook(
                baseUrl = request.url.host,
                url = url,
                additionalContext = listOfNotNull(
                    errorType,
                    e.message,
                    e.cause?.message,
                ),
            )

            throw e
        }
    }
}
