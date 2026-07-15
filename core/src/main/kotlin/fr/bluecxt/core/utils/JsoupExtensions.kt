package fr.bluecxt.core.utils

import fr.bluecxt.core.network.ErrorWebhook
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * JSoup selectFirst wrapper that logs a webhook if the selector returns null.
 */
fun Element.selectFirstLog(cssSelector: String): Element? {
    val element = this.selectFirst(cssSelector)
    if (element == null) {
        ErrorWebhook.sendWebhook(
            baseUrl = this.baseUri(),
            url = this.baseUri(),
            additionalContext = listOf("Selector failed: '$cssSelector'"),
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
        ErrorWebhook.sendWebhook(
            baseUrl = this.baseUri(),
            url = this.baseUri(),
            additionalContext = listOf("Selector returned empty: '$cssSelector'"),
        )
    }
    return elements
}
