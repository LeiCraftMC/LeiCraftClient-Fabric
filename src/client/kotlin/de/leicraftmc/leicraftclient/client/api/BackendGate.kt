package de.leicraftmc.leicraftclient.client.api

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Caps how many backend requests are in flight at once.
 *
 * Joining a busy server makes every visible player miss the cache in the same
 * tick, which would otherwise fire one profile request and one cape request per
 * player simultaneously. Results are cached for minutes afterwards, so queueing
 * the burst costs nothing visible and keeps the backend from being hammered.
 */
object BackendGate {

    private const val MAX_CONCURRENT_REQUESTS = 6

    private val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)

    suspend fun <T> withPermit(block: () -> T): T = semaphore.withPermit { block() }
}
