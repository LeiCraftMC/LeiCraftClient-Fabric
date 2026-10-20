package de.leicraftmc.leicraftclient.client.gui

import de.leicraftmc.leicraftclient.client.api.ApiModels

/**
 * The wardrobe's unsaved loadout: one cape (or none) and up to
 * [MAX_COSMETICS] cosmetics, each with an optional pinned variant.
 *
 * Kept free of Minecraft types so the rules are unit-tested; the screen only
 * draws it and sends [toUpdate] on save.
 */
class WardrobeSelection(initial: ApiModels.Loadout) {

    enum class Toggle { ADDED, REMOVED, LIMIT_REACHED }

    var cape: ApiModels.Product? = initial.cape
        private set

    /** Product id to the equipped cosmetic, in equip order. */
    private val cosmetics = LinkedHashMap<Int, ApiModels.LoadoutCosmetic>().apply {
        for (cosmetic in initial.cosmetics) put(cosmetic.product.id, cosmetic)
    }

    private val initialUpdate = toUpdate()

    val equippedCosmetics: List<ApiModels.LoadoutCosmetic> get() = cosmetics.values.toList()

    /** True when saving would change something. */
    val isDirty: Boolean get() = toUpdate() != initialUpdate

    fun selectCape(product: ApiModels.Product?) {
        cape = product
    }

    fun isCapeSelected(productId: Int?): Boolean = cape?.id == productId

    fun isEquipped(productId: Int): Boolean = cosmetics.containsKey(productId)

    /** Equips or removes [product]; refuses an eleventh cosmetic. */
    fun toggle(product: ApiModels.Product): Toggle {
        if (cosmetics.remove(product.id) != null) return Toggle.REMOVED
        if (cosmetics.size >= MAX_COSMETICS) return Toggle.LIMIT_REACHED
        cosmetics[product.id] = ApiModels.LoadoutCosmetic(product, null)
        return Toggle.ADDED
    }

    /** The pinned variant of an equipped cosmetic; null for "Auto" (or not equipped). */
    fun variantOf(productId: Int): ApiModels.Variant? {
        val cosmetic = cosmetics[productId] ?: return null
        val pinned = cosmetic.variantId ?: return null
        return cosmetic.product.variants.firstOrNull { it.id == pinned }
    }

    /**
     * Steps an equipped cosmetic through Auto, then each variant in order, then
     * back to Auto. Returns the new pinned variant (null for Auto).
     */
    fun cycleVariant(productId: Int): ApiModels.Variant? {
        val cosmetic = cosmetics[productId] ?: return null
        val variants = cosmetic.product.variants
        if (variants.isEmpty()) return null

        val index = variants.indexOfFirst { it.id == cosmetic.variantId }
        val next = if (index + 1 < variants.size) variants[index + 1] else null
        cosmetics[productId] = cosmetic.copy(variantId = next?.id)
        return next
    }

    /** The `PUT /v1/client/loadout` body for the current selection. */
    fun toUpdate(): ApiModels.LoadoutUpdate = ApiModels.LoadoutUpdate(
        capeProductId = cape?.id,
        cosmetics = cosmetics.values.map { it.product.id to it.variantId },
    )

    /** The selection as a loadout, which is what the preview renders. */
    fun toLoadout(): ApiModels.Loadout = ApiModels.Loadout(cape, cosmetics.values.toList())

    companion object {
        /** The API's limit on equipped cosmetics. */
        const val MAX_COSMETICS = 10
    }
}
