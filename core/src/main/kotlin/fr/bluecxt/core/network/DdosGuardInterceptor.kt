// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.network

import android.util.Log
import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.GET
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * DDoS-Guard bypass interceptor.
 *
 * When a response is a DDoS-Guard challenge (403 + `Server: ddos-guard`), it fetches the
 * `__ddg2_` cookie from `check.ddos-guard.net/check.js` and replays the original request
 * with that cookie attached.
 *
 * Ported from yuzono/anime-extensions `lib/voeextractor/DdosGuardInterceptor.kt`.
 */
class DdosGuardInterceptor(private val client: OkHttpClient) : Interceptor {

    private val cookieManager by lazy { CookieManager.getInstance() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        // Only react to DDoS-Guard challenges
        if (response.code !in ERROR_CODES || response.header("Server") !in SERVER_CHECK) {
            return response
        }

        Log.d(TAG, "DDoS-Guard challenge on ${originalRequest.url}")
        response.close()

        val oldCookies = currentCookies(originalRequest.url)
        if (oldCookies.any { it.name == DDG_COOKIE && it.value.isNotEmpty() }) {
            return chain.proceed(originalRequest)
        }

        val newCookie = getNewCookie(originalRequest.url) ?: return chain.proceed(originalRequest)
        val cookieHeader = (oldCookies + newCookie).joinToString("; ") { "${it.name}=${it.value}" }

        Log.d(TAG, "Retrying ${originalRequest.url} with ${newCookie.name}")
        return chain.proceed(
            originalRequest.newBuilder().addHeader("cookie", cookieHeader).build(),
        )
    }

    fun getNewCookie(url: HttpUrl): Cookie? {
        currentCookies(url)
            .firstOrNull { it.name == DDG_COOKIE && it.value.isNotEmpty() }
            ?.let { return it }

        val wellKnown = runCatching {
            client.newCall(GET(WELL_KNOWN_URL)).execute().use { it.body.string() }
        }.getOrNull()
            ?.substringAfter("'", "")
            ?.substringBefore("'", "")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val checkUrl = "${url.scheme}://${url.host}$wellKnown"
        return runCatching {
            client.newCall(GET(checkUrl)).execute().use { it.header("set-cookie") }
        }.getOrNull()?.let { Cookie.parse(url, it) }
    }

    private fun currentCookies(url: HttpUrl): List<Cookie> {
        val cookies = cookieManager.getCookie(url.toString()) ?: return emptyList()
        return cookies.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }

    companion object {
        private const val TAG = "DdosGuard"
        private const val DDG_COOKIE = "__ddg2_"
        private const val WELL_KNOWN_URL = "https://check.ddos-guard.net/check.js"
        private val ERROR_CODES = listOf(403)
        private val SERVER_CHECK = listOf("ddos-guard")
    }
}
