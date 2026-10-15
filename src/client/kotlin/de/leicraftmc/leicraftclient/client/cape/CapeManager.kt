package de.leicraftmc.leicraftclient.client.cape

import de.leicraftmc.leicraftclient.client.LeiCraftClient
import de.leicraftmc.leicraftclient.client.TextureStore
import de.leicraftmc.leicraftclient.client.api.BackendGate
import de.leicraftmc.leicraftclient.client.api.CapesApi
import de.leicraftmc.leicraftclient.client.config.ClientConfig
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.minecraft.resources.Identifier
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-player capes, fetched lazily and cached.
 *
 * [capeFor] is called from the player's `getSkin()`, which runs once per player
 * per frame, so it must stay a cheap map read.
 */
object CapeManager {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("leicraftclient-capes"),
    )

    private val players = ConcurrentHashMap<String, PlayerCape>()

    private class PlayerCape {
        @Volatile
        var texture: Identifier? = null

        @Volatile
        var expiresAt: Long = 0

        val refreshing = AtomicBoolean(false)
    }

    /** The player's custom cape, or null if they have none (or it is still loading). */
    fun capeFor(username: String): Identifier? {
        if (!ClientConfig.capesEnabled) return null

        val entry = players.computeIfAbsent(username.lowercase()) { PlayerCape() }
        if (System.currentTimeMillis() >= entry.expiresAt) {
            refresh(username, entry)
        }
        return entry.texture
    }

    /** Drops every cached cape so the next frame refetches. */
    fun invalidateAll() {
        players.clear()
    }

    /** Short content hash, used so identical capes share one GPU texture. */
    private fun digest(png: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(png)
            .take(8)
            .joinToString("") { "%02x".format(it) }

    private fun refresh(username: String, entry: PlayerCape) {
        if (!entry.refreshing.compareAndSet(false, true)) return

        scope.launch {
            try {
                val png = BackendGate.withPermit { CapesApi.fetchCape(username) }
                entry.texture = if (png == null) {
                    null
                } else {
                    // Key by content, not by player: a server where everyone wears
                    // the same cape then uploads one texture instead of one each.
                    TextureStore.upload("capes/${digest(png)}", png).join()
                }
            } catch (e: Exception) {
                LeiCraftClient.LOGGER.warn("Could not load cape for {}", username, e)
            } finally {
                entry.expiresAt = System.currentTimeMillis() + ClientConfig.cacheSeconds * 1000
                entry.refreshing.set(false)
            }
        }
    }
}
