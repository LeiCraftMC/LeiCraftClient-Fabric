package de.leicraftmc.leicraftclient.client.update

import de.leicraftmc.leicraftclient.client.LeiCraftClient
import de.leicraftmc.leicraftclient.client.api.ApiModels
import de.leicraftmc.leicraftclient.client.api.ApiResult
import de.leicraftmc.leicraftclient.client.api.LeiCraftApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Looks for a newer release of the mod once per game start, in the background.
 *
 * [available] stays null until one is found, and the options screen shows it.
 * A failed check is only logged: it is not worth an error on screen, and the
 * next start tries again.
 */
object UpdateCheck {

    private val mutableAvailable = MutableStateFlow<ApiModels.Release?>(null)

    /** The release to offer ([ReleaseSelector]), or null when there is none (yet). */
    val available: StateFlow<ApiModels.Release?> = mutableAvailable.asStateFlow()

    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        LeiCraftClient.scope.launch {
            when (val result = LeiCraftApi.releases()) {
                is ApiResult.Ok -> {
                    val newer = ReleaseSelector.newer(LeiCraftClient.version, result.data)
                    if (newer != null) {
                        LeiCraftClient.LOGGER.info("LeiCraftClient {} is available (running {})", newer.version, LeiCraftClient.version)
                    }
                    mutableAvailable.value = newer
                }
                is ApiResult.Error -> LeiCraftClient.LOGGER.info("Update check failed: {}", result.message)
            }
        }
    }
}
