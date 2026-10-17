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
 * Uploading touches the GPU, so it is always deferred to the render thread. A
 * key is only uploaded once -- cosmetic textures are shared between every player
 * wearing that cosmetic, and capes between everyone wearing the same cape.
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
                    LeiCraftClient.LOGGER.warn("Could not decode texture for {}", key, e)
                    future.completeExceptionally(e)
                } finally {
                    inFlight.remove(key)
                }
            }
            future
        }
    }

    /**
     * Forgets every registration so the next fetch uploads afresh.
     *
     * The GPU textures are left registered: re-uploading under the same key
     * replaces the entry in the texture manager, which avoids a window where a
     * render type resolves an identifier that no longer exists.
     */
    fun clear() {
        registered.clear()
        inFlight.clear()
    }

    /** Identifier paths only allow `[a-z0-9/._-]`, but keys come from URLs and names. */
    private fun sanitise(key: String): String {
        val cleaned = key.lowercase().map { ch ->
            if (ch in 'a'..'z' || ch in '0'..'9' || ch == '/' || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("")
        return "dynamic/$cleaned"
    }
}
