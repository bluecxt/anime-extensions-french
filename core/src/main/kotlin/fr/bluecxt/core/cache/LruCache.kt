// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Facteur de charge par défaut des caches LRU. */
private const val DEFAULT_LOAD_FACTOR = 0.75f

/**
 * Cache Clé-Valeur polymorphique avec politique d'éviction LRU (capacité bornée) et expiration TTL.
 * Idéal pour les métadonnées d'APIs externes (AniList, TMDB, TVDB) ou les caches d'épisodes/vidéos.
 *
 * @param K Type de la clé (doit implémenter equals/hashCode).
 * @param V Type de la valeur mise en cache (nullable supporté pour le negative caching).
 * @param maxEntries Nombre maximum d'entrées avant éviction LRU.
 * @param defaultTtl Durée de rétention par défaut.
 */
class LruCache<K : Any, V>(
    private val maxEntries: Int = 300,
    private val defaultTtl: Duration = 6.hours,
) {
    private data class Entry<V>(
        val value: V?,
        val expireMark: TimeMark,
    ) {
        val isExpired: Boolean get() = expireMark.hasPassedNow()
    }

    private val mutexes = ConcurrentHashMap<K, Mutex>()

    private val map: MutableMap<K, Entry<V>> = Collections.synchronizedMap(
        object : LinkedHashMap<K, Entry<V>>(maxEntries, DEFAULT_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean = size > maxEntries
        },
    )

    /**
     * Retourne une paire (présent, valeur).
     * Permet de distinguer un miss complet d'une valeur null mise en cache (negative caching).
     */
    fun getWithStatus(key: K): Pair<Boolean, V?> {
        val entry = synchronized(map) {
            val found = map[key] ?: return false to null
            if (found.isExpired) {
                map.remove(key)
                return false to null
            }
            found
        }
        return true to entry.value
    }

    /**
     * Récupère la valeur associée à [key] si présente et non expirée.
     */
    fun get(key: K): V? = getWithStatus(key).second

    /**
     * Enregistre une valeur dans le cache avec un TTL spécifique ou par défaut.
     */
    fun put(key: K, value: V?, ttl: Duration = defaultTtl) {
        map[key] = Entry(value, TimeSource.Monotonic.markNow() + ttl)
    }

    /**
     * Récupère la valeur en cache ou exécute [loader] en protégeant les requêtes
     * concurrentes sur la même clé via un [Mutex].
     */
    suspend fun getOrPut(
        key: K,
        ttl: Duration = defaultTtl,
        loader: suspend () -> V?,
    ): V? {
        val (hit, cached) = getWithStatus(key)
        if (hit) return cached

        val lock = mutexes.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            try {
                val (innerHit, innerCached) = getWithStatus(key)
                if (innerHit) return innerCached

                val computed = loader()
                put(key, computed, ttl)
                computed
            } finally {
                mutexes.remove(key)
            }
        }
    }

    /**
     * Supprime une clé du cache et retourne son ancienne valeur.
     */
    fun remove(key: K): V? = map.remove(key)?.value

    /**
     * Vide complètement le cache.
     */
    fun clear() {
        map.clear()
        mutexes.clear()
    }

    /**
     * Nombre d'entrées actuelles dans le cache.
     */
    val size: Int get() = map.size
}
