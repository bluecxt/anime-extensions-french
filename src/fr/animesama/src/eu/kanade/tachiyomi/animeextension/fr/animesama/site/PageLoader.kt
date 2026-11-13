// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama.site

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Small document cache in front of the network.
 *
 * A season is parsed three times per user action (details, season list, episode list), and the
 * work page is shared by every season of the same work: caching for a few seconds removes those
 * duplicate requests without hiding a site update for long.
 *
 * Misses are cached too (negative caching), so a work with no season yet does not hammer the
 * site while the user navigates. Concurrent calls for the same path share a single request.
 *
 * Expiry is measured on the monotonic clock, so a system time change cannot keep a page alive
 * forever nor evict a fresh one.
 *
 * @param fetch performs the actual request and returns `null` on a network or HTTP failure.
 * @param maxEntries maximum number of documents kept in memory.
 * @param ttl how long a document stays valid, successful or not.
 */
internal class PageLoader(
    private val fetch: suspend (path: String) -> Document?,
    private val maxEntries: Int = MAX_ENTRIES,
    private val ttl: Duration = cacheLifetime,
) {
    /**
     * One cached page.
     *
     * @property document parsed page, `null` when the last attempt failed.
     * @property expireMark monotonic instant after which the entry is stale.
     */
    private data class Entry(
        val document: Document?,
        val expireMark: TimeMark,
    ) {
        /** Tells whether the entry outlived its time to live. */
        val isExpired: Boolean get() = expireMark.hasPassedNow()
    }

    /** Access-ordered map bounded to [maxEntries]: the least recently used page leaves first. */
    private val entries = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
        },
    )

    /** One lock per path, so that concurrent callers share a single request. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Returns the document of a path, from the cache when still valid.
     *
     * @param path absolute path, for example `/catalogue/07-ghost`.
     * @return the parsed document, or `null` when the page could not be loaded.
     */
    suspend fun load(path: String): Document? {
        validEntry(path)?.let { return it.document }

        val lock = locks.computeIfAbsent(path) { Mutex() }
        return try {
            lock.withLock {
                // A concurrent caller may have filled the cache, or cached a failure, meanwhile.
                validEntry(path)?.let { return@withLock it.document }

                val document = fetch(path)
                put(path, document)
                document
            }
        } finally {
            locks.remove(path, lock)
        }
    }

    /** Forgets a cached page, used when a token must be renewed. */
    fun invalidate(path: String) {
        entries.remove(path)
    }

    /**
     * Reads a still valid entry, dropping it when expired.
     *
     * @param path absolute path.
     * @return the entry, or `null` when it is absent or stale.
     */
    private fun validEntry(path: String): Entry? {
        val entry = entries[path] ?: return null
        if (entry.isExpired) {
            entries.remove(path)
            return null
        }
        return entry
    }

    /**
     * Stores a page, successful or not.
     *
     * @param path absolute path.
     * @param document parsed page, `null` for a failed attempt.
     */
    private fun put(path: String, document: Document?) {
        entries[path] = Entry(document, TimeSource.Monotonic.markNow() + ttl)
    }

    private companion object {
        /** Maximum number of cached pages. */
        const val MAX_ENTRIES = 32

        /** Initial capacity of the cache map. */
        const val INITIAL_CAPACITY = 16

        /** Load factor of the cache map. */
        const val LOAD_FACTOR = 0.75f

        /** Lifetime of a cached page, successful or not. */
        val cacheLifetime = 30.seconds
    }
}
