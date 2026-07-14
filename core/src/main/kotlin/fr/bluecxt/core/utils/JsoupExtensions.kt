package fr.bluecxt.core.utils

import android.app.Application
import fr.bluecxt.core.network.ErrorWebhook
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Helper trace analyzer to automatically identify which extension and method called the helper.
 */
private fun getCallerInfo(): Triple<String, String, String> {
    val stackTrace = Thread.currentThread().stackTrace
    val caller = stackTrace.firstOrNull { element ->
        element.className.contains("animeextension") || element.className.contains("bluecxt")
    }
    return if (caller != null) {
        val extensionClass = caller.className.substringAfterLast(".")
        val methodName = caller.methodName
        val version = try {
            val pkgName = caller.className.substringBeforeLast(".")
            val app = Injekt.get<Application>()
            app.packageManager.getPackageInfo(pkgName, 0).versionName ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
        Triple(extensionClass, "$methodName(${caller.fileName}:${caller.lineNumber})", version)
    } else {
        Triple("UnknownExtension", "UnknownMethod", "Unknown")
    }
}

/**
 * JSoup selectFirst wrapper that logs a webhook if the selector returns null.
 */
fun Element.selectFirstLog(cssSelector: String): Element? {
    val element = this.selectFirst(cssSelector)
    if (element == null) {
        val (extensionName, callerDetails, version) = getCallerInfo()
        ErrorWebhook.sendWebhook(
            baseUrl = this.baseUri(),
            url = this.baseUri(),
            extensionName = extensionName,
            extensionVersion = version,
            additionalContext = listOf("Selector failed: '$cssSelector'", "Caller: $callerDetails"),
        )
    }
    return element
}

/**
 * JSoup select wrapper that logs a webhook if the selection results in an empty list.
 */
fun Element.selectLog(cssSelector: String): Elements {
    val elements = this.select(cssSelector)
    if (elements.isEmpty()) {
        val (extensionName, callerDetails, version) = getCallerInfo()
        ErrorWebhook.sendWebhook(
            baseUrl = this.baseUri(),
            url = this.baseUri(),
            extensionName = extensionName,
            extensionVersion = version,
            additionalContext = listOf("Selector returned empty: '$cssSelector'", "Caller: $callerDetails"),
        )
    }
    return elements
}
