package de.leicraftmc.leicraftclient.client.api

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Caps how many backend requests are in flight at once, and keeps the mod off
 * routes that answered `429`.
 *
 * Joining a busy server makes every visible player miss the cache in the same
 * tick. Lookups are batched, but asset downloads for a crowd still arrive in a
 * burst; queueing it costs nothing visible because everything is cached
 * afterwards.
 *
 * Rate limits are per route on the API (the link endpoint allows far fewer
 * calls than the lookup), so a back-off only blocks the route that asked for it.
 */
object BackendGate {

    private const val MAX_CONCURRENT_REQUESTS = 6

    /** Used when a `429` arrives without a `Retry-After` header. */
    const val DEFAULT_BACKOFF_SECONDS: Long = 30

    private val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)

    /** Route key (`"POST /v1/players/lookup"`) to the time it may be called again. */
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    suspend fun <T> withPermit(block: suspend () -> T): T = semaphore.withPermit { block() }

    /** Milliseconds until [route] may be called again; 0 when it is open. */
    fun backoffRemaining(route: String, now: Long = System.currentTimeMillis()): Long {
        val until = blockedUntil[route] ?: return 0
        if (now >= until) {
            blockedUntil.remove(route, until)
            return 0
        }
        return until - now
    }

    /** Blocks [route] for [seconds] (or the default when the API sent none). */
    fun backOff(route: String, seconds: Long?, now: Long = System.currentTimeMillis()) {
        val delay = (seconds ?: DEFAULT_BACKOFF_SECONDS).coerceAtLeast(1) * 1000
        blockedUntil.merge(route, now + delay) { old, new -> maxOf(old, new) }
    }

    /** The key [backoffRemaining] and [backOff] use: method and path, without the query. */
    fun routeKey(method: String, path: String): String = "$method ${path.substringBefore('?')}"
}
