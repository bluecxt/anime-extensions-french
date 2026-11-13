// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.site

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.nodes.Document

/**
 * One server column of the bdd script.
 *
 * @property index server number as declared by the site (`1` for `eps1`).
 * @property refs raw references, one per episode: index 0 is episode 1. A reference is either
 *   `lien:<id>` (resolved by the API) or a full embed URL (legacy rows).
 */
internal data class ServerTracks(
    val index: Int,
    val refs: List<String>,
)

/**
 * State the page attaches to a stored link.
 *
 * @property host hostname serving the link.
 * @property dead `true` when the site itself flags the link as removed. Those links are still
 *   listed in the script but can no longer be played.
 */
internal data class LinkState(
    val host: String,
    val dead: Boolean,
)

/**
 * Display information for a hoster, as published by the site.
 *
 * @property name name shown by the site (`Vidmoly`, `Earnvids`, `Embed4me`…).
 * @property dead `true` when the hoster is marked as broken site-wide.
 */
internal data class HostInfo(
    val name: String,
    val dead: Boolean,
)

/**
 * Content of the `<script data-source="bdd">` tag, which replaced `episodes.js` in November 2026.
 *
 * The tag declares one array per server, a map of link states, and a page token used to resolve
 * link references through `/api/lien.php`.
 *
 * @property token token required by the link API; it is page-scoped and time-bound.
 * @property ordered whether the site already sorted servers in its preferred order.
 * @property servers server columns, ordered by server number.
 * @property links state of every referenced link, keyed by its id.
 * @property hosts display name and availability of every known hoster, keyed by hostname.
 */
internal data class BddScript(
    val token: String,
    val ordered: Boolean,
    val servers: List<ServerTracks>,
    val links: Map<String, LinkState>,
    val hosts: Map<String, HostInfo>,
) {
    /** Number of episodes the richest server column declares. */
    val episodeCount: Int = servers.maxOfOrNull { it.refs.size } ?: 0

    /** Returns the hostname announced for a link id. */
    fun hostOf(id: String): String? = links[id]?.host

    /**
     * Tells whether a referenced link can still be played.
     *
     * Unknown ids (older rows, or a link missing from `epsMeta`) are considered alive: it is
     * better to try and fail than to silently hide a working link.
     */
    fun isAlive(id: String): Boolean = links[id]?.let { !it.dead } ?: true

    /** Tells whether the hoster itself is flagged as broken site-wide. */
    fun isHostDead(host: String): Boolean = hosts[host]?.dead == true

    /** Returns the name the site shows for a hostname, falling back to the hostname. */
    fun displayName(host: String): String = hosts[host]?.name ?: host
}

/**
 * Parser for the bdd script.
 *
 * The script is plain JavaScript, so the arrays are read with a dedicated regex while the two
 * objects (`epsMeta`, `lecteursInfos`) are read with a brace-aware scan and then decoded as
 * JSON, which keeps the parser readable and avoids depending on the key order.
 */
internal object BddScriptParser {
    private val epsArrayRegex = Regex("""var\s+eps(\d+)\s*=\s*\[([^\]]*)]""")
    private val quotedRegex = Regex("""["']([^"']+)["']""")
    private val tokenRegex = Regex("""var\s+epsJeton\s*=\s*["']([^"']+)["']""")
    private val orderedRegex = Regex("""var\s+epsOrdreFinal\s*=\s*(true|false)""")

    /**
     * Parses the bdd script of a season page.
     *
     * @param document page of a season in one language.
     * @return the parsed script, or `null` when the page has no episodes (a season that is not
     *   out yet answers `200` with no bdd script, unlike a missing page that answers `404`).
     */
    fun parse(document: Document): BddScript? {
        val script = document.selectFirst("script[data-source=bdd]")?.html() ?: return null
        val token = tokenRegex.find(script)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return null

        val servers = epsArrayRegex.findAll(script)
            .map { match ->
                ServerTracks(
                    index = match.groupValues[1].toIntOrNull() ?: 0,
                    refs = quotedRegex.findAll(match.groupValues[2]).map { it.groupValues[1] }.toList(),
                )
            }
            .sortedBy { it.index }
            .toList()

        if (servers.none { it.refs.isNotEmpty() }) return null

        return BddScript(
            token = token,
            ordered = orderedRegex.find(script)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: true,
            servers = servers,
            links = parseLinkStates(script),
            hosts = parseHosts(document),
        )
    }

    /**
     * Reads `epsMeta`, which maps a link id to its hostname and optional dead state.
     *
     * `epsMeta["65268"] = ["ansembed.net"]` is playable; a second element (`1` or `2`) marks a
     * removed link.
     */
    private fun parseLinkStates(script: String): Map<String, LinkState> {
        val meta = extractObject(script, "var epsMeta") ?: return emptyMap()
        return meta.mapNotNull { (id, value) ->
            val parts = value as? JsonArray ?: return@mapNotNull null
            val host = (parts.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val state = (parts.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            id to LinkState(host = host, dead = state == DEAD || state == DEAD_OTHER)
        }.toMap()
    }

    /**
     * Reads `lecteursInfos`, which gives the real name of each hoster and flags the ones that
     * no longer work at all.
     */
    private fun parseHosts(document: Document): Map<String, HostInfo> {
        val infos = extractObject(document.html(), "var lecteursInfos") ?: return emptyMap()
        return infos.mapNotNull { (host, value) ->
            val info = value as? JsonObject ?: return@mapNotNull null
            val name = (info["nom"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val dead = (info["mort"] as? JsonPrimitive)?.booleanOrNull ?: false
            host to HostInfo(name = name, dead = dead)
        }.toMap()
    }

    /**
     * Extracts the first balanced JSON object following a marker.
     *
     * The search is brace-aware and string-aware, so nested objects (each entry of
     * `lecteursInfos` is itself an object) are read correctly.
     *
     * @param source JavaScript source to scan.
     * @param marker declaration to look for, for example `var epsMeta`.
     */
    private fun extractObject(source: String, marker: String): JsonObject? {
        val markerIndex = source.indexOf(marker)
        if (markerIndex < 0) return null

        val openIndex = source.indexOf('{', markerIndex)
        if (openIndex < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in openIndex until source.length) {
            val char = source[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true

                '{' -> depth++

                '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = source.substring(openIndex, index + 1)
                        return runCatching { Json.parseToJsonElement(candidate).jsonObject }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    /** `epsMeta` state of a removed link. */
    private const val DEAD = 1

    /** `epsMeta` state of a link removed for another reason. */
    private const val DEAD_OTHER = 2
}
