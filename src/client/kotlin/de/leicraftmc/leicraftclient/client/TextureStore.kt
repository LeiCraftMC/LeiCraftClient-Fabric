package de.leicraftmc.leicraftclient.client

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Registers downloaded PNGs with Minecraft's texture manager and hands back the
 * [Identifier] to draw them with.
 *
 * Keys are asset ids (SHA-256 of the file). Assets are content-addressed, so a
 * key is uploaded once per game session and never needs replacing -- a changed
 * cape or cosmetic texture arrives under a new id. Every player wearing the
 * same item shares the one GPU texture.
 *
 * Uploading touches the GPU, so it is always deferred to the render thread.
 */
object TextureStore {

    private val registered = ConcurrentHashMap<String, Identifier>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Identifier>>()

    /** The identifier for [key] if it has already been uploaded. */
    fun identifierFor(key: String): Identifier? = registered[key]

    /**
     * Uploads [png] under [key], or returns the existing registration.
     *
     * The returned future completes on the render thread. Callers on a
     * background thread may block on it; callers on the render thread must not.
     */
    fun upload(key: String, png: ByteArray): CompletableFuture<Identifier> {
        registered[key]?.let { return CompletableFuture.completedFuture(it) }

        return inFlight.computeIfAbsent(key) {
            val future = CompletableFuture<Identifier>()
            val client = Minecraft.getInstance()
            client.execute {
                try {
                    val existing = registered[key]
                    if (existing != null) {
                        future.complete(existing)
                        return@execute
                    }
                    val image = NativeImage.read(png)
                    val id = Identifier.fromNamespaceAndPath(LeiCraftClient.MOD_ID, sanitise(key))
                    client.textureManager.register(id, DynamicTexture({ key }, image))
                    registered[key] = id
                    future.complete(id)
                } catch (e: Exception) {
                    LeiCraftClient.LOGGER.warn("Could not decode texture {}", key, e)
                    future.completeExceptionally(e)
                } finally {
                    inFlight.remove(key)
                }
            }
            future
        }
    }

    /** Identifier paths only allow `[a-z0-9/._-]`; asset ids already fit, this is a guard. */
    private fun sanitise(key: String): String {
        val cleaned = key.lowercase().map { ch ->
            if (ch in 'a'..'z' || ch in '0'..'9' || ch == '/' || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("")
        return "asset/$cleaned"
    }
}
