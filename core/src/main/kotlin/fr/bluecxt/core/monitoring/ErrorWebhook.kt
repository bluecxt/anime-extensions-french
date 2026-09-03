// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.monitoring

import fr.bluecxt.core.network.INTERCEPTOR_VERSION
import fr.bluecxt.core.utils.JSOUP_EXTENSIONS_VERSION
import keiyoushi.core.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val WEBHOOK_URL = BuildConfig.WEBHOOK_URL
private val WEBHOOK_SECRET = BuildConfig.WEBHOOK_SECRET

@Serializable
data class MonitoringErrorPayload(
    val timestamp: Long = System.currentTimeMillis(),
    val extensionName: String,
    val extensionVersion: String,
    val interceptorVersion: String = INTERCEPTOR_VERSION,
    val jsoupVersion: String = JSOUP_EXTENSIONS_VERSION,
    val buildType: String,
    val isDev: Boolean = false,
    val isDebug: Boolean = false,
    val domain: String,
    val url: String,
    val errorType: String,
    val httpCode: Int? = null,
    val details: List<String> = emptyList(),
)

object ErrorWebhook {
    private val client by lazy {
        Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client
    }

    private val json = Json { encodeDefaults = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    // LRU cache bounded at MAX_CACHE_SIZE; webhookMutex provides exclusive access
    private val sentWebhooksCache = object : LinkedHashMap<Int, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>): Boolean = size > MAX_CACHE_SIZE
    }
    private val webhookMutex = Mutex()
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val CACHE_LIFETIME_MS = 24 * 60 * 60 * 1000L
    private const val MAX_CACHE_SIZE = 500

    // Resolved once at object init — no runtime reflection or stacktrace inspection
    private val buildType = BuildConfig.BUILD_TYPE.lowercase()
    private val isDev = buildType == "dev"
    private val isDebug = BuildConfig.DEBUG && !isDev

    // Webhook target URL computed once at startup
    private val webhookEndpoint = "${WEBHOOK_URL.trimEnd('/')}/webhook/extension-error"

    /**
     * Ultra-fast 32-bit FNV-1a non-cryptographic hash for zero-allocation payload deduplication.
     */
    private fun fastHash(data: String): Int {
        var hash = -2128831035
        for (char in data) {
            hash = (hash xor char.code) * 16777619
        }
        return hash
    }

    private fun shouldSend(hashKey: Int): Boolean {
        val now = System.currentTimeMillis()
        val lastSent = sentWebhooksCache[hashKey]
        if (lastSent != null && (now - lastSent) < CACHE_LIFETIME_MS) return false
        sentWebhooksCache[hashKey] = now
        return true
    }

    /**
     * Dispatches an error webhook notification for the given base URL and target URL.
     */
    fun sendWebhook(
        baseUrl: String,
        url: String,
        additionalContext: List<String>,
        extensionName: String? = null,
        extensionVersion: String? = null,
    ) {
        if (WEBHOOK_URL.isBlank() || isDebug) return

        val httpCode = additionalContext.firstOrNull { it.startsWith("HTTP_ERROR_") }
            ?.removePrefix("HTTP_ERROR_")
            ?.toIntOrNull()

        val errorType = additionalContext.firstOrNull {
            it.startsWith("HTTP_ERROR_") || it in listOf("DNS_FAILURE", "SSL_ERROR", "TIMEOUT", "NETWORK_ERROR", "SELECTOR_ERROR")
        } ?: "GENERIC_ERROR"

        val rawKey = "${extensionName.orEmpty()}:$baseUrl:$url:$errorType:${additionalContext.joinToString("|")}"
        val hashKey = fastHash(rawKey)

        dispatchPayload(hashKey, baseUrl, url, additionalContext, httpCode, errorType, extensionName, extensionVersion)
    }

    /**
     * Overload with a single context string and optional exception.
     */
    fun sendWebhook(
        baseUrl: String,
        url: String,
        context: String,
        exception: Throwable? = null,
        extensionName: String? = null,
        extensionVersion: String? = null,
    ) {
        val details = mutableListOf(context)
        if (exception != null) {
            details.add("${exception::class.java.simpleName}: ${exception.message}")
        }
        sendWebhook(baseUrl, url, details, extensionName, extensionVersion)
    }

    private fun dispatchPayload(
        hashKey: Int,
        baseUrl: String,
        url: String,
        additionalContext: List<String>,
        httpCode: Int?,
        errorType: String,
        extensionName: String?,
        extensionVersion: String?,
    ) {
        monitoringScope.launch {
            val shouldProceed = webhookMutex.withLock { shouldSend(hashKey) }
            if (!shouldProceed) return@launch

            val payload = MonitoringErrorPayload(
                extensionName = extensionName.takeIf { !it.isNullOrBlank() } ?: "Unknown",
                extensionVersion = extensionVersion.takeIf { !it.isNullOrBlank() } ?: "Unknown",
                buildType = buildType,
                isDev = isDev,
                isDebug = isDebug,
                domain = baseUrl,
                url = url,
                errorType = errorType,
                httpCode = httpCode,
                details = additionalContext,
            )

            try {
                val request = Request.Builder()
                    .url(webhookEndpoint)
                    .header("X-Monitoring-Secret", WEBHOOK_SECRET)
                    .post(json.encodeToString(MonitoringErrorPayload.serializer(), payload).toRequestBody(mediaType))
                    .build()

                client.newCall(request).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
                })
            } catch (_: Exception) {
                // Fail silently — never interrupt the scraper flow
            }
        }
    }
}
