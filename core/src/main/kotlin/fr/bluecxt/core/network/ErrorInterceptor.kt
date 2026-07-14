package fr.bluecxt.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class ErrorInterceptor(
    private val extensionName: String,
    private val extensionVersion: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        try {
            val response = chain.proceed(request)

            val code = response.code
            if (!response.isSuccessful && code != 404 && code !in 300..399) {
                val responseBody = response.peekBody(512).string().take(200).ifBlank { null }
                ErrorWebhook.sendWebhook(
                    baseUrl = request.url.host,
                    url = url,
                    extensionName = extensionName,
                    extensionVersion = extensionVersion,
                    additionalContext = listOfNotNull(
                        "HTTP_ERROR_$code",
                        "method=${request.method}",
                        responseBody?.let { "body=$it" },
                    ),
                )
            }

            return response
        } catch (e: IOException) {
            val errorType = when (e) {
                is java.net.SocketTimeoutException -> "TIMEOUT"
                is java.net.UnknownHostException -> "DNS_FAILURE"
                else -> "NETWORK_ERROR"
            }

            ErrorWebhook.sendWebhook(
                baseUrl = request.url.host,
                url = url,
                extensionName = extensionName,
                extensionVersion = extensionVersion,
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
