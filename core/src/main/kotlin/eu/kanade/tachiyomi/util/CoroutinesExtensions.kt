package eu.kanade.tachiyomi.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Parallel implementation of [Iterable.map].
 */
suspend inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> = withContext(Dispatchers.IO) {
    map { async { f(it) } }.awaitAll()
}

/**
 * Thread-blocking parallel implementation of [Iterable.map].
 */
inline fun <A, B> Iterable<A>.parallelMapBlocking(crossinline f: suspend (A) -> B): List<B> = runBlocking { parallelMap(f) }

/**
 * Parallel implementation of [Iterable.mapNotNull].
 */
suspend inline fun <A, B> Iterable<A>.parallelMapNotNull(crossinline f: suspend (A) -> B?): List<B> = withContext(Dispatchers.IO) {
    map { async { f(it) } }.awaitAll().filterNotNull()
}

/**
 * Thread-blocking parallel implementation of [Iterable.mapNotNull].
 */
inline fun <A, B> Iterable<A>.parallelMapNotNullBlocking(crossinline f: suspend (A) -> B?): List<B> = runBlocking { parallelMapNotNull(f) }

/**
 * Parallel implementation of [Iterable.flatMap].
 */
suspend inline fun <A, B> Iterable<A>.parallelFlatMap(crossinline f: suspend (A) -> Iterable<B>): List<B> = withContext(Dispatchers.IO) {
    map { async { f(it) } }.awaitAll().flatten()
}

/**
 * Thread-blocking parallel implementation of [Iterable.flatMap].
 */
inline fun <A, B> Iterable<A>.parallelFlatMapBlocking(crossinline f: suspend (A) -> Iterable<B>): List<B> = runBlocking { parallelFlatMap(f) }

/**
 * Parallel implementation of [Iterable.flatMap], but running
 * the transformation function inside a try-catch block.
 */
suspend inline fun <A, B> Iterable<A>.parallelCatchingFlatMap(crossinline f: suspend (A) -> Iterable<B>): List<B> = withContext(Dispatchers.IO) {
    map {
        async {
            try {
                f(it)
            } catch (e: Throwable) {
                e.printStackTrace()
                emptyList()
            }
        }
    }.awaitAll().flatten()
}

/**
 * Thread-blocking parallel implementation of [Iterable.flatMap], but running
 * the transformation function inside a try-catch block.
 */
inline fun <A, B> Iterable<A>.parallelCatchingFlatMapBlocking(crossinline f: suspend (A) -> Iterable<B>): List<B> = runBlocking { parallelCatchingFlatMap(f) }
