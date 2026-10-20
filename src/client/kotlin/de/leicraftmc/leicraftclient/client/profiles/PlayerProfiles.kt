package de.leicraftmc.leicraftclient.client.profiles

import de.leicraftmc.leicraftclient.client.LeiCraftClient
import de.leicraftmc.leicraftclient.client.api.ApiModels
import de.leicraftmc.leicraftclient.client.api.ApiResult
import de.leicraftmc.leicraftclient.client.api.BackendGate
import de.leicraftmc.leicraftclient.client.api.LeiCraftApi
import de.leicraftmc.leicraftclient.client.assets.AssetCache
import de.leicraftmc.leicraftclient.client.config.ClientConfig
import de.leicraftmc.leicraftclient.client.cosmetics.CpmModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.minecraft.resources.Identifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Every player's LeiCraft profile (cape, cosmetics, client usage), by UUID.
 *
 * Lookups happen while rendering -- per player, per frame -- so they are map
 * reads. A miss or an expired entry queues the player, and a background job
 * sends the queue every [FLUSH_INTERVAL_MS] as batched
 * `POST /v1/players/lookup` requests ([LookupBatch]). Players the API does not
 * know are cached as "no profile" for the same TTL, so nobody is asked about
 * twice per [ClientConfig.cacheSeconds].
 *
 * While the wardrobe is open, a preview profile can be laid over the own
 * player ([setPreview]); it wins over the fetched one until cleared.
 */
object PlayerProfiles {

    private const val FLUSH_INTERVAL_MS = 250L

    /** Retry delay after a failed lookup; shorter than the TTL, but never per frame. */
    private const val FAILURE_RETRY_MS = 60_000L

    /** Entries not looked at for this long are dropped, so the map does not grow forever. */
    private const val IDLE_EVICT_MS = 30 * 60_000L
    private const val EVICT_EVERY_MS = 60_000L

    private val LOOKUP_ROUTE = BackendGate.routeKey("POST", "/v1/players/lookup")

    /** A cosmetic that is parsed and ready to draw. */
    class Loaded(
        val model: CpmModel,
        /** The cosmetic's own texture; null when the model uses the wearer's skin. */
        val texture: Identifier?,
    )

    private class Entry {
        @Volatile
        var profile: ApiModels.PlayerProfile? = null

        /** 0 until the first answer arrives. */
        @Volatile
        var expiresAt: Long = 0

        @Volatile
        var name: String? = null

        @Volatile
        var lastAccess: Long = 0

        val queued = AtomicBoolean(false)
    }

    private val entries = ConcurrentHashMap<UUID, Entry>()
    private val queue = ConcurrentLinkedQueue<UUID>()
    private val previews = ConcurrentHashMap<UUID, ApiModels.PlayerProfile>()
    private val workerStarted = AtomicBoolean(false)

    @Volatile
    private var lastEviction = 0L

    /**
     * The player's profile, or null if they have none (or it is still loading).
     * [name] is only needed for offline-mode UUIDs; see [LookupBatch].
     */
    fun profile(uuid: UUID, name: String?): ApiModels.PlayerProfile? {
        previews[uuid]?.let { return it }

        val entry = entries[uuid] ?: entries.computeIfAbsent(uuid) { Entry() }
        val now = System.currentTimeMillis()
        entry.lastAccess = now
        if (name != null && entry.name == null) entry.name = name
        if (now >= entry.expiresAt) enqueue(uuid, entry)
        return entry.profile
    }

    /** Whether the player used the LeiCraft Client recently (the tab-list badge). */
    fun usesClient(uuid: UUID, name: String?): Boolean = profile(uuid, name)?.usesClient == true

    /** The player's LeiCraft cape texture, or null for none / still loading / capes off. */
    fun capeFor(uuid: UUID, name: String?): Identifier? {
        if (!ClientConfig.capesEnabled) return null
        val cape = profile(uuid, name)?.cape ?: return null
        return AssetCache.texture(cape.texture)
    }

    /**
     * The cosmetics to draw on a player whose skin model is [skinModel]
     * (`"slim"` / `"wide"`), each with the variant [VariantSelector] picks.
     * Cosmetics whose files are still loading are left out for now.
     */
    fun cosmeticsFor(uuid: UUID, name: String?, skinModel: String): List<Loaded> {
        if (!ClientConfig.cosmeticsEnabled) return emptyList()
        val cosmetics = profile(uuid, name)?.cosmetics ?: return emptyList()
        if (cosmetics.isEmpty()) return emptyList()

        val out = ArrayList<Loaded>(cosmetics.size)
        for (cosmetic in cosmetics) {
            val variant = VariantSelector.pick(cosmetic.variants, cosmetic.variantId, skinModel) ?: continue
            val model = AssetCache.model(variant.model) ?: continue
            if (model.usePlayerTexture) {
                out += Loaded(model, null)
            } else {
                val texture = AssetCache.texture(variant.texture) ?: continue
                out += Loaded(model, texture)
            }
        }
        return out
    }

    /** Shows [profile] for each of [uuids] instead of the fetched profile (wardrobe preview). */
    fun setPreview(uuids: Collection<UUID>, profile: ApiModels.PlayerProfile) {
        for (uuid in uuids) previews[uuid] = profile
    }

    fun clearPreview() {
        previews.clear()
    }

    /** Stores a known-good profile (e.g. right after saving the loadout) and re-fetches it. */
    fun put(uuid: UUID, name: String?, profile: ApiModels.PlayerProfile?) {
        val entry = entries.computeIfAbsent(uuid) { Entry() }
        if (name != null) entry.name = name
        entry.profile = profile
        entry.lastAccess = System.currentTimeMillis()
        refresh(uuid, name)
    }

    /** Fetches the player again soon, keeping the current profile on screen meanwhile. */
    fun refresh(uuid: UUID, name: String?) {
        val entry = entries.computeIfAbsent(uuid) { Entry() }
        if (name != null) entry.name = name
        entry.lastAccess = System.currentTimeMillis()
        entry.expiresAt = 0
        enqueue(uuid, entry)
    }

    /** Marks every profile stale; each is fetched again the next time it is drawn. */
    fun invalidateAll() {
        for (entry in entries.values) entry.expiresAt = 0
    }

    // -- batching -------------------------------------------------------------

    private fun enqueue(uuid: UUID, entry: Entry) {
        if (!entry.queued.compareAndSet(false, true)) return
        queue.add(uuid)
        ensureWorker()
    }

    private fun ensureWorker() {
        if (!workerStarted.compareAndSet(false, true)) return
        LeiCraftClient.scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                try {
                    flush()
                    evictIdle()
                } catch (e: Exception) {
                    LeiCraftClient.LOGGER.warn("Player lookup failed", e)
                }
            }
        }
    }

    private suspend fun flush() {
        if (queue.isEmpty()) return
        // Rate limited: leave everything queued until the API accepts calls again.
        if (BackendGate.backoffRemaining(LOOKUP_ROUTE) > 0) return

        val keys = mutableListOf<LookupBatch.Key>()
        while (true) {
            val uuid = queue.poll() ?: break
            val entry = entries[uuid] ?: continue
            keys += LookupBatch.Key(uuid, entry.name)
        }

        val now = System.currentTimeMillis()
        val ttl = ClientConfig.cacheSeconds * 1000

        // Players the API cannot know about (NPCs, nameless offline players).
        for (key in keys) {
            if (LookupBatch.modeFor(key.uuid, key.name) == LookupBatch.Mode.SKIP) {
                settle(key.uuid, null, now + ttl)
            }
        }

        val requests = LookupBatch.plan(keys)
        for ((index, request) in requests.withIndex()) {
            when (val result = LeiCraftApi.lookup(request.uuids, request.names)) {
                is ApiResult.Ok -> {
                    val answered = System.currentTimeMillis() + ttl
                    for ((uuid, profile) in LookupBatch.match(request, result.data)) {
                        settle(uuid, profile, answered)
                    }
                }
                is ApiResult.Error -> {
                    if (result.isRateLimited) {
                        // Put this and every later batch back; retried after the back-off.
                        for (later in requests.drop(index)) requeue(later.keys)
                        return
                    }
                    val retryAt = System.currentTimeMillis() + minOf(ttl, FAILURE_RETRY_MS)
                    for (key in request.keys) {
                        // Keep whatever was shown before; only push the next attempt out.
                        entries[key.uuid]?.let { it.expiresAt = retryAt; it.queued.set(false) }
                    }
                }
            }
        }
    }

    private fun settle(uuid: UUID, profile: ApiModels.PlayerProfile?, expiresAt: Long) {
        val entry = entries[uuid] ?: return
        entry.profile = profile
        entry.expiresAt = expiresAt
        entry.queued.set(false)
    }

    private fun requeue(keys: List<LookupBatch.Key>) {
        for (key in keys) queue.add(key.uuid)
    }

    private fun evictIdle() {
        val now = System.currentTimeMillis()
        if (now - lastEviction < EVICT_EVERY_MS) return
        lastEviction = now
        entries.entries.removeIf { (_, entry) -> !entry.queued.get() && now - entry.lastAccess > IDLE_EVICT_MS }
    }
}
