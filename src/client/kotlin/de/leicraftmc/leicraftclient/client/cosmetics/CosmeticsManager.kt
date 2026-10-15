package de.leicraftmc.leicraftclient.client.cosmetics

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-player cosmetics, fetched lazily and cached.
 *
 * The render layer asks for a player's cosmetics every frame, so a lookup is a
 * plain map read; a miss or an expired entry starts a background refresh and
 * returns what is currently known (usually nothing on the first frame).
 *
 * Caching is per player name, which is what the backend is keyed by. Models and
 * textures are cached globally, so a server full of players wearing the same
 * cosmetic downloads and uploads it once.
 */
object CosmeticsManager {

    private const val UPLOAD_TIMEOUT_SECONDS = 15L

    /** A cosmetic that is parsed, uploaded and ready to draw. */
    class Loaded(val model: CpmModel, val texture: Identifier)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("leicraftclient-cosmetics"),
    )

    private val players = ConcurrentHashMap<String, PlayerCosmetics>()

    /** Parsed models, shared by every player wearing the same cosmetic. */
    private val models = ConcurrentHashMap<String, CpmModel>()

    private class PlayerCosmetics {
        @Volatile
        var loaded: List<Loaded> = emptyList()

        @Volatile
        var expiresAt: Long = 0

        val refreshing = AtomicBoolean(false)
    }

    /**
     * Cosmetics currently known for [username]; empty while the first fetch is
     * still in flight, or if the player has none.
     */
    fun cosmeticsFor(username: String): List<Loaded> {
        if (!ClientConfig.cosmeticsEnabled) return emptyList()

        val entry = players.computeIfAbsent(username.lowercase()) { PlayerCosmetics() }
        if (System.currentTimeMillis() >= entry.expiresAt) {
            refresh(username, entry)
        }
        return entry.loaded
    }

    /** Drops every cached profile so the next frame refetches. */
    fun invalidateAll() {
        players.clear()
        models.clear()
    }

    private fun refresh(username: String, entry: PlayerCosmetics) {
        if (!entry.refreshing.compareAndSet(false, true)) return

        scope.launch {
            try {
                entry.loaded = BackendGate.withPermit {
                    CapesApi.fetchCosmetics(username).mapNotNull { load(it) }
                }
            } catch (e: Exception) {
                LeiCraftClient.LOGGER.warn("Could not load cosmetics for {}", username, e)
            } finally {
                // Push the next retry out even on failure, so a backend that is
                // down does not get hammered once per frame.
                entry.expiresAt = System.currentTimeMillis() + ClientConfig.cacheSeconds * 1000
                entry.refreshing.set(false)
            }
        }
    }

    /** Fetches, parses and uploads one cosmetic; null if any part is unusable. */
    private fun load(entry: CapesApi.CosmeticEntry): Loaded? {
        val model = models.getOrElse(entry.modelPath) {
            val json = CapesApi.fetchModel(entry.modelPath) ?: return null
            val parsed = CpmParser.parse(json) ?: return null
            models[entry.modelPath] = parsed
            parsed
        }

        // A model may name its own texture; otherwise the profile entry decides.
        val texturePath = model.texturePath ?: entry.texturePath
        val png = CapesApi.fetchTexture(texturePath) ?: return null

        // We are on an IO thread, so waiting for the render thread to finish the
        // upload is fine and keeps the result simple.
        val texture = try {
            TextureStore.upload(texturePath, png).get(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            LeiCraftClient.LOGGER.warn("Texture upload failed for {}", texturePath, e)
            return null
        }

        return Loaded(model, texture)
    }
}
