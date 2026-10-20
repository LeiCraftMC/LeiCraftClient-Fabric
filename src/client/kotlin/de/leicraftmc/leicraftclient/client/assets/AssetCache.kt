package de.leicraftmc.leicraftclient.client.assets

import de.leicraftmc.leicraftclient.client.LeiCraftClient
import de.leicraftmc.leicraftclient.client.TextureStore
import de.leicraftmc.leicraftclient.client.api.ApiModels
import de.leicraftmc.leicraftclient.client.api.ApiResult
import de.leicraftmc.leicraftclient.client.api.LeiCraftApi
import de.leicraftmc.leicraftclient.client.cosmetics.CpmModel
import de.leicraftmc.leicraftclient.client.cosmetics.CpmParser
import kotlinx.coroutines.launch
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Capes, cosmetic models and cosmetic textures, by asset id.
 *
 * Asset ids are the SHA-256 of the file and the content behind a URL never
 * changes, so each id is downloaded at most once: after that it comes from the
 * disk cache (`<gameDir>/leicraftclient/cache/assets/<id>.<png|json>`) and,
 * within a session, from memory -- textures from [TextureStore], models parsed
 * by [CpmParser] once per id. Every byte is checked against its id, on download
 * and when read back from disk.
 *
 * [texture] and [model] are called while rendering, so they are map reads; a
 * miss starts a background load and returns null until it lands.
 */
object AssetCache {

    enum class Kind(val extension: String) {
        TEXTURE("png"),
        MODEL("json"),
    }

    /** Capes go up to 2048x1024; nothing legitimate comes close to this. */
    private const val MAX_ASSET_BYTES = 16 * 1024 * 1024

    private const val RETRY_AFTER_FAILURE_MS = 60_000L

    /** A download whose bytes did not match the id; the server would have to fix it. */
    private const val RETRY_AFTER_MISMATCH_MS = 10 * 60_000L

    private const val UPLOAD_TIMEOUT_SECONDS = 15L

    private val models = ConcurrentHashMap<String, CpmModel>()
    private val loading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val retryAt = ConcurrentHashMap<String, Long>()

    private val directory: Path by lazy {
        FabricLoader.getInstance().gameDir.resolve(LeiCraftClient.MOD_ID).resolve("cache").resolve("assets")
    }

    /** The uploaded texture for [ref], or null while it loads (or if it is unusable). */
    fun texture(ref: ApiModels.AssetRef): Identifier? {
        TextureStore.identifierFor(ref.id)?.let { return it }
        request(ref, Kind.TEXTURE)
        return null
    }

    /** The parsed cosmetic model for [ref], or null while it loads (or if it is unusable). */
    fun model(ref: ApiModels.AssetRef): CpmModel? {
        models[ref.id]?.let { return it }
        request(ref, Kind.MODEL)
        return null
    }

    /** Forgets failed loads so they are tried again (the options screen's reload). */
    fun clearFailures() {
        retryAt.clear()
    }

    private fun request(ref: ApiModels.AssetRef, kind: Kind) {
        val id = ref.id
        if (!AssetIntegrity.isValidId(id)) {
            if (retryAt.putIfAbsent(id, Long.MAX_VALUE) == null) {
                LeiCraftClient.LOGGER.warn("Ignoring asset with a malformed id: {}", id)
            }
            return
        }
        val blocked = retryAt[id]
        if (blocked != null && System.currentTimeMillis() < blocked) return
        if (!loading.add(id)) return

        LeiCraftClient.scope.launch {
            try {
                load(ref, kind)
            } catch (e: Exception) {
                LeiCraftClient.LOGGER.warn("Could not load asset {}", id, e)
                retryAt[id] = System.currentTimeMillis() + RETRY_AFTER_FAILURE_MS
            } finally {
                loading.remove(id)
            }
        }
    }

    private suspend fun load(ref: ApiModels.AssetRef, kind: Kind) {
        val bytes = readCached(ref.id, kind) ?: download(ref, kind) ?: return

        when (kind) {
            Kind.TEXTURE -> {
                // On an IO thread, so waiting for the render thread is fine.
                TextureStore.upload(ref.id, bytes).get(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            Kind.MODEL -> {
                val parsed = CpmParser.parse(String(bytes, StandardCharsets.UTF_8))
                if (parsed == null) {
                    // Same id, same bytes: it would fail again, so stop asking.
                    LeiCraftClient.LOGGER.warn("Cosmetic model {} could not be parsed", ref.id)
                    retryAt[ref.id] = Long.MAX_VALUE
                    return
                }
                models[ref.id] = parsed
            }
        }
        retryAt.remove(ref.id)
    }

    private fun fileFor(id: String, kind: Kind): Path = directory.resolve(AssetIntegrity.fileName(id, kind.extension))

    /** The cached file, if present and intact; a corrupt one is deleted. */
    private fun readCached(id: String, kind: Kind): ByteArray? {
        val file = fileFor(id, kind)
        if (!Files.isRegularFile(file)) return null
        return try {
            val bytes = Files.readAllBytes(file)
            if (AssetIntegrity.matches(id, bytes)) {
                bytes
            } else {
                LeiCraftClient.LOGGER.warn("Cached asset {} is corrupt; downloading it again", file.fileName)
                Files.deleteIfExists(file)
                null
            }
        } catch (e: Exception) {
            LeiCraftClient.LOGGER.debug("Could not read cached asset {}", file, e)
            null
        }
    }

    private suspend fun download(ref: ApiModels.AssetRef, kind: Kind): ByteArray? {
        val bytes = when (val result = LeiCraftApi.download(ref.url, MAX_ASSET_BYTES)) {
            is ApiResult.Ok -> result.data
            is ApiResult.Error -> {
                LeiCraftClient.LOGGER.debug("Asset {} download failed: {} {}", ref.id, result.status, result.message)
                retryAt[ref.id] = System.currentTimeMillis() + RETRY_AFTER_FAILURE_MS
                return null
            }
        }

        if (!AssetIntegrity.matches(ref.id, bytes)) {
            LeiCraftClient.LOGGER.warn("Rejected asset {}: content does not match its SHA-256 id", ref.id)
            retryAt[ref.id] = System.currentTimeMillis() + RETRY_AFTER_MISMATCH_MS
            return null
        }

        writeCached(ref.id, kind, bytes)
        return bytes
    }

    /** Written through a temp file, so a crash never leaves a half file under a valid name. */
    private fun writeCached(id: String, kind: Kind, bytes: ByteArray) {
        val target = fileFor(id, kind)
        try {
            Files.createDirectories(target.parent)
            val temp = Files.createTempFile(target.parent, "$id.", ".tmp")
            try {
                Files.write(temp, bytes)
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        } catch (e: Exception) {
            // The disk cache is an optimisation; the asset still works from memory.
            LeiCraftClient.LOGGER.debug("Could not cache asset {}", target, e)
        }
    }
}
