package fr.bluecxt.core.utils

import fr.bluecxt.core.Source
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

const val JSOUP_EXTENSIONS_VERSION = "1.0"

/**
 * Interface providing JSoup logging helper extensions.
 *
 * Must be implemented by a subclass of [fr.bluecxt.core.Source].
 */
interface JsoupExtensions {

    /**
     * Access the parent Source instance implementing this interface.
     */
    private val sourceSelf: Source
        get() = this as? Source
            ?: throw IllegalStateException("JsoupExtensions interface must only be implemented by a subclass of Source")

    /**
     * JSoup selectFirst wrapper that logs a webhook if the selector returns null.
     *
     * @param cssSelector The CSS selector query to find.
     * @return The matched [Element], or `null` if no match was found.
     */
    fun Element.selectFirstLog(cssSelector: String): Element? {
        val element = this.selectFirst(cssSelector)
        if (element == null) {
            val src = sourceSelf
            src.sendErrorWebhook(
                url = this.baseUri().ifBlank { src.baseUrl },
                context = "Selector failed: '$cssSelector'",
            )
        }
        return element
    }

    /**
     * JSoup select wrapper that logs a webhook if the selection results in an empty list.
     *
     * @param cssSelector The CSS selector query to find.
     * @return The matched [Elements].
     */
    fun Element.selectLog(cssSelector: String): Elements {
        val elements = this.select(cssSelector)
        if (elements.isEmpty()) {
            val src = sourceSelf
            src.sendErrorWebhook(
                url = this.baseUri().ifBlank { src.baseUrl },
                context = "Selector returned empty: '$cssSelector'",
            )
        }
        return elements
    }
}
