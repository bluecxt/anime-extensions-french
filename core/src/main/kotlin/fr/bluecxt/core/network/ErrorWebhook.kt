package fr.bluecxt.core.network

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
