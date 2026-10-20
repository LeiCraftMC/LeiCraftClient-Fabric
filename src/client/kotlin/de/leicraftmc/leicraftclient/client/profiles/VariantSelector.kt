package de.leicraftmc.leicraftclient.client.profiles

import de.leicraftmc.leicraftclient.client.api.ApiModels

/**
 * Picks which variant of a cosmetic to draw on a player.
 *
 * The API's rule: the pinned `variant_id` if there is one; otherwise the first
 * variant made for the wearer's skin model (`"slim"` / `"wide"`); otherwise the
 * first `"any"` variant; otherwise the first variant at all.
 */
object VariantSelector {

    const val SLIM: String = "slim"
    const val WIDE: String = "wide"
    const val ANY: String = "any"

    fun pick(variants: List<ApiModels.Variant>, variantId: Int?, skinModel: String): ApiModels.Variant? {
        if (variants.isEmpty()) return null
        if (variantId != null) {
            // A pinned variant that no longer exists falls through to the automatic choice.
            variants.firstOrNull { it.id == variantId }?.let { return it }
        }
        return variants.firstOrNull { it.skinModel.equals(skinModel, ignoreCase = true) }
            ?: variants.firstOrNull { it.skinModel.equals(ANY, ignoreCase = true) }
            ?: variants.first()
    }
}
