package de.leicraftmc.leicraftclient.client.profiles

import de.leicraftmc.leicraftclient.client.api.ApiModels
import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * The local player, as far as profiles are concerned.
 *
 * In-world, the local player's UUID is normally the account's, but an
 * offline-mode server hands out a name-based one instead. Both are covered so
 * the wardrobe preview and post-save refreshes reach the player wherever it is
 * drawn.
 */
object OwnPlayer {

    /**
     * The entity id on the wardrobe preview's render state when there is no
     * player entity (title screen), so render layers know whose state it is.
     * Real entity ids are never negative.
     */
    const val PREVIEW_ENTITY_ID: Int = Int.MIN_VALUE

    fun uuids(): Set<UUID> {
        val minecraft = Minecraft.getInstance()
        val ids = linkedSetOf(minecraft.user.profileId)
        minecraft.player?.let { ids += it.uuid }
        return ids
    }

    fun name(): String = Minecraft.getInstance().user.name

    /** Fetches the own profile again soon (after linking, unlinking or saving). */
    fun refreshProfile() {
        val name = name()
        for (uuid in uuids()) PlayerProfiles.refresh(uuid, name)
    }

    /** Shows [loadout] at once, before the refresh it also triggers comes back. */
    fun applyLoadout(loadout: ApiModels.Loadout) {
        val name = name()
        for (uuid in uuids()) {
            val previous = PlayerProfiles.profile(uuid, name)
            PlayerProfiles.put(uuid, name, profileOf(uuid, name, loadout, previous?.usesClient ?: true))
        }
    }

    /** The render-ready profile a loadout amounts to. */
    fun profileOf(uuid: UUID, name: String, loadout: ApiModels.Loadout, usesClient: Boolean = true): ApiModels.PlayerProfile =
        profileOf(uuid, name, loadout.cape, loadout.cosmetics.map { it.product to it.variantId }, usesClient)

    fun profileOf(
        uuid: UUID,
        name: String,
        cape: ApiModels.Product?,
        cosmetics: List<Pair<ApiModels.Product, Int?>>,
        usesClient: Boolean = true,
    ): ApiModels.PlayerProfile = ApiModels.PlayerProfile(
        uuid = uuid,
        name = name,
        usesClient = usesClient,
        cape = cape?.texture?.let { ApiModels.ProfileCape(cape.id, it) },
        cosmetics = cosmetics.map { (product, variantId) ->
            ApiModels.ProfileCosmetic(product.id, variantId, product.variants)
        },
    )
}
