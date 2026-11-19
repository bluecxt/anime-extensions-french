// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Cache mono-ressource polymorphique avec stratégie Stale-While-Revalidate.
 * Idéal pour les bases de données complètes ou les catalogues JSON globaux (OrcoStream, AnimeSama, etc.).
 *
 * - **Cold cache :** Suspend et attend le premier chargement des données.
 * - **Fresh cache :** Retourne immédiatement la valeur en mémoire sans latence ni allocation.
 * - **Expired cache :** Retourne immédiatement la valeur périmée tout en déclenchant un
 *   rafraîchissement asynchrone non-bloquant en arrière-plan.
 *
 * @param T Type de la ressource mise en cache (ex: List<Anime>, JsonObject, etc.).
 * @param scope Portée de coroutine pour les tâches de fond.
 * @param ttl Durée de validité avant revalidation en tâche de fond.
 * @param loader Fonction suspendue chargeant la ressource fraîche.
 */
class TtlCache<T>(
    private val scope: CoroutineScope,
    private val ttl: Duration = 15.minutes,
    private val loader: suspend () -> T,
) {
    @Volatile
    private var cachedValue: T? = null

    @Volatile
    private var lastFetchMark: TimeMark? = null

    private val mutex = Mutex()
    private var ongoingRefresh: Deferred<T>? = null

    /**
     * Récupère la valeur en cache, déclenchant un fetch ou une revalidation en tâche de fond.
     */
    suspend operator fun invoke(): T {
        val current = cachedValue ?: return fetchAndSwap()
        val mark = lastFetchMark ?: return fetchAndSwap()

        if (mark.elapsedNow() < ttl) {
            return current
        }
        triggerBackgroundRefresh()
        return current
    }

    /**
     * Charge les données de manière synchronisée avec déduplication des requêtes concurrentes.
     */
    private suspend fun fetchAndSwap(): T {
        return mutex.withLock {
            cachedValue?.let { return it }

            val deferred = ongoingRefresh ?: scope.async(Dispatchers.IO) {
                loader()
            }.also { ongoingRefresh = it }

            try {
                val freshData = deferred.await()
                cachedValue = freshData
                lastFetchMark = TimeSource.Monotonic.markNow()
                freshData
            } finally {
                ongoingRefresh = null
            }
        }
    }

    /**
     * Déclenche un rafraîchissement asynchrone non-bloquant en arrière-plan.
     */
    private fun triggerBackgroundRefresh() {
        if (ongoingRefresh != null) return

        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (ongoingRefresh != null) return@withLock
                ongoingRefresh = scope.async { loader() }
            }

            try {
                val freshData = ongoingRefresh?.await()
                if (freshData != null) {
                    cachedValue = freshData
                    lastFetchMark = TimeSource.Monotonic.markNow()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                ongoingRefresh = null
            }
        }
    }

    /**
     * Précharge la ressource en tâche de fond de manière non-bloquante.
     * Peut être appelé depuis un contexte synchrone comme un bloc [init].
     */
    fun preload() {
        if (cachedValue != null || ongoingRefresh != null) return

        scope.launch(Dispatchers.IO) {
            try {
                fetchAndSwap()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Réinitialise le cache pour forcer un rechargement complet au prochain appel.
     */
    fun invalidate() {
        cachedValue = null
        lastFetchMark = null
    }
}
