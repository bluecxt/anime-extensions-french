package fr.bluecxt.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element

fun Element.safeRelativePath(): String = this.attr("abs:href").toHttpUrlOrNull()?.encodedPath ?: ""
