package fr.bluecxt.core.network

import android.app.Application
import keiyoushi.core.BuildConfig
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val WEBHOOK_URL = BuildConfig.WEBHOOK_URL

@Serializable
private data class WebhookPayload(
    val baseUrl: String,
    val url: String,
    val extensionName: String,
    val extensionVersion: String,
    val additionalContext: List<String>,
)

object ErrorWebhook {
    private val client by lazy {
        Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client
    }

    private val json = Json { encodeDefaults = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private fun getCallerInfo(): Triple<String, String, String> {
        val stackTrace = Thread.currentThread().stackTrace
        val caller = stackTrace.firstOrNull { element ->
            element.className.contains("animeextension") || element.className.contains("bluecxt")
        }
        return if (caller != null) {
            val extensionClass = caller.className.substringAfterLast(".")
            val version = try {
                val pkgName = caller.className.substringBeforeLast(".")
                val app = Injekt.get<Application>()
                app.packageManager.getPackageInfo(pkgName, 0).versionName ?: "Unknown"
            } catch (_: Exception) {
                "Unknown"
            }
            Triple(extensionClass, version, "${caller.methodName}(${caller.fileName}:${caller.lineNumber})")
        } else {
            Triple("UnknownExtension", "Unknown", "UnknownMethod")
        }
    }

    fun sendWebhook(
        baseUrl: String,
        url: String,
        additionalContext: List<String>,
    ) {
        val (extensionName, extensionVersion, callerDetails) = getCallerInfo()
        val enrichedContext = additionalContext + "Caller: $callerDetails"
        sendWebhook(baseUrl, url, extensionName, extensionVersion, enrichedContext)
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun sendWebhook(
        baseUrl: String,
        url: String,
        extensionName: String,
        extensionVersion: String,
        additionalContext: List<String>,
    ) = GlobalScope.launch(Dispatchers.IO) {
        try {
            val payload = WebhookPayload(
                baseUrl = baseUrl,
                url = url,
                extensionName = extensionName,
                extensionVersion = extensionVersion,
                additionalContext = additionalContext,
            )

            val request = Request.Builder()
                .url(WEBHOOK_URL)
                .post(json.encodeToString(WebhookPayload.serializer(), payload).toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().close()
        } catch (_: Exception) {
            // Fail silently to avoid interrupting scraper/application flow
        }
    }
}
