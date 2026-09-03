// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.monitoring

import android.app.Application
import fr.bluecxt.core.network.INTERCEPTOR_VERSION
import fr.bluecxt.core.utils.JSOUP_EXTENSIONS_VERSION
import keiyoushi.core.BuildConfig
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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
    val caller: String? = null,
    val details: List<String> = emptyList(),
)

private data class CallerAndBuildInfo(
    val extensionName: String,
    val version: String,
    val buildType: String,
    val isDev: Boolean,
    val isDebug: Boolean,
    val callerDetails: String,
)

object ErrorWebhook {
    private val client by lazy {
        Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client
    }

    private val json = Json { encodeDefaults = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private val sentWebhooksCache: MutableMap<Int, Long> = object : LinkedHashMap<Int, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>): Boolean = size > MAX_CACHE_SIZE
    }.let { java.util.Collections.synchronizedMap(it) }
    private val webhookMutex = Mutex()
    private const val CACHE_LIFETIME_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val MAX_CACHE_SIZE = 500

    /**
     * Ultra-fast 32-bit FNV-1a non-cryptographic hash for zero-allocation payload hashing.
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
        if (lastSent != null && (now - lastSent) < CACHE_LIFETIME_MS) {
            return false
        }
        sentWebhooksCache[hashKey] = now
        return true
    }

    private fun getCallerAndBuildInfo(): CallerAndBuildInfo {
        val stackTrace = Thread.currentThread().stackTrace

        // 1. Prefer caller from actual extension package
        val extCaller = stackTrace.firstOrNull { element ->
            element.className.contains("animeextension")
        }

        // 2. Fallback to bluecxt core caller
        val caller = extCaller ?: stackTrace.firstOrNull { element ->
            val cls = element.className
            cls.contains("bluecxt") &&
                !cls.contains("ErrorWebhook") &&
                !cls.contains("ErrorInterceptor") &&
                !cls.contains("JsoupExtensions") &&
                !cls.contains("SourceAuditor")
        }

        val extensionClass = caller?.className?.substringAfterLast(".") ?: "UnknownExtension"
        val pkgName = extCaller?.className?.substringBeforeLast(".") ?: caller?.className?.substringBeforeLast(".").orEmpty()
        val callerMethod = caller?.let { "${it.methodName}(${it.fileName}:${it.lineNumber})" } ?: "UnknownMethod"

        val (version, buildType, isDev, isDebug) = resolveBuildInfo(pkgName, caller?.className)

        return CallerAndBuildInfo(
            extensionName = extensionClass,
            version = version,
            buildType = buildType,
            isDev = isDev,
            isDebug = isDebug,
            callerDetails = callerMethod,
        )
    }

    private fun resolveBuildInfo(pkgName: String, callerClassName: String? = null): Tuple4<String, String, Boolean, Boolean> {
        var version = "Unknown"
        var buildType = "release"
        var isDebug = false

        val app = try {
            Injekt.get<Application>()
        } catch (_: Exception) {
            null
        }

        // 1. Try resolving version via ExtensionResources
        if (app != null && callerClassName != null) {
            try {
                val clazz = Class.forName(callerClassName)
                val ver = fr.bluecxt.core.utils.ExtensionResources.getVersionName(app, clazz)
                if (!ver.isNullOrBlank()) version = ver
            } catch (_: Exception) {}
        }

        // 2. Inspect BuildConfig of extension
        if (pkgName.isNotBlank()) {
            try {
                val buildConfigCls = Class.forName("$pkgName.BuildConfig")
                (buildConfigCls.getField("VERSION_NAME").get(null) as? String)?.takeIf { it.isNotBlank() }?.let { version = it }
                (buildConfigCls.getField("BUILD_TYPE").get(null) as? String)?.takeIf { it.isNotBlank() }?.let { buildType = it.lowercase() }
                (buildConfigCls.getField("DEBUG").get(null) as? Boolean)?.let { isDebug = it }
            } catch (_: Exception) {}
        }

        // Fallback to core BuildConfig if buildType remains default
        if (buildType == "release") {
            try {
                val coreBuildType = BuildConfig.BUILD_TYPE.lowercase()
                if (coreBuildType.isNotBlank()) buildType = coreBuildType
                if (BuildConfig.DEBUG) isDebug = true
            } catch (_: Exception) {}
        }

        // 3. Fallback PackageManager for version
        if (version == "Unknown" && app != null && pkgName.isNotBlank()) {
            try {
                val ver = app.packageManager.getPackageInfo(pkgName, 0).versionName
                    ?: app.packageManager.getPackageInfo(app.packageName, 0).versionName
                if (!ver.isNullOrBlank()) version = ver
            } catch (_: Exception) {}
        }

        val isDev = buildType == "dev"
        val effectiveIsDebug = isDebug && !isDev

        return Tuple4(version, buildType, isDev, effectiveIsDebug)
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /**
     * Resolves the target n8n webhook URL.
     * Uses `webhook-test` for debug builds, and `webhook` for dev/release builds.
     */
    private fun getWebhookEndpoint(isDebug: Boolean): String {
        val cleanBase = WEBHOOK_URL.trimEnd('/')
        val endpoint = if (isDebug) "webhook-test/extension-error" else "webhook/extension-error"
        return if (cleanBase.contains("/webhook")) {
            if (isDebug) {
                cleanBase.replace("/webhook/", "/webhook-test/")
            } else {
                cleanBase.replace("/webhook-test/", "/webhook/")
            }
        } else {
            "$cleanBase/$endpoint"
        }
    }

    /**
     * Dispatch an error webhook notification for the given base URL and target URL.
     */
    fun sendWebhook(
        baseUrl: String,
        url: String,
        additionalContext: List<String>,
        extensionName: String? = null,
        extensionVersion: String? = null,
    ) {
        if (WEBHOOK_URL.isBlank()) return

        val httpCode = additionalContext.firstOrNull { it.startsWith("HTTP_ERROR_") }
            ?.removePrefix("HTTP_ERROR_")
            ?.toIntOrNull()

        val errorType = additionalContext.firstOrNull {
            it.startsWith("HTTP_ERROR_") || it in listOf("DNS_FAILURE", "SSL_ERROR", "TIMEOUT", "NETWORK_ERROR", "SELECTOR_ERROR")
        } ?: if (httpCode != null) "HTTP_$httpCode" else "GENERIC_ERROR"

        // Compute cache key early — before the expensive stacktrace inspection
        val rawKey = "${extensionName.orEmpty()}:$baseUrl:$url:$errorType:${additionalContext.joinToString("|")}"
        val hashKey = fastHash(rawKey)

        dispatchPayload(hashKey, baseUrl, url, additionalContext, httpCode, errorType, extensionName, extensionVersion)
    }

    /**
     * Overload to dispatch an error webhook notification with a single context message and optional exception.
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
            details.add("Exception: ${exception::class.java.simpleName} - ${exception.message}")
        }
        sendWebhook(baseUrl, url, details, extensionName, extensionVersion)
    }

    @OptIn(DelicateCoroutinesApi::class)
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
        GlobalScope.launch(Dispatchers.IO) {
            // Cache check first — before any expensive stacktrace/reflection work
            val shouldProceed = webhookMutex.withLock { shouldSend(hashKey) }
            if (!shouldProceed) return@launch

            // Only now resolve caller info via stacktrace & reflection
            val info = getCallerAndBuildInfo()
            val effectiveName = extensionName?.takeIf { it.isNotBlank() } ?: info.extensionName
            val effectiveVersion = extensionVersion?.takeIf { it.isNotBlank() } ?: info.version

            val payload = MonitoringErrorPayload(
                timestamp = System.currentTimeMillis(),
                extensionName = effectiveName,
                extensionVersion = effectiveVersion,
                buildType = info.buildType,
                isDev = info.isDev,
                isDebug = info.isDebug,
                domain = baseUrl,
                url = url,
                errorType = errorType,
                httpCode = httpCode,
                caller = info.callerDetails,
                details = additionalContext,
            )

            try {
                val targetUrl = getWebhookEndpoint(payload.isDebug)
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("X-Monitoring-Secret", WEBHOOK_SECRET)
                    .post(json.encodeToString(MonitoringErrorPayload.serializer(), payload).toRequestBody(mediaType))
                    .build()

                client.newCall(request).execute().close()
            } catch (_: Exception) {
                // Fail silently to avoid interrupting scraper/application flow
            }
        }
    }
}
