package de.leicraftmc.leicraftclient.client.profiles

import de.leicraftmc.leicraftclient.client.Fixtures
import de.leicraftmc.leicraftclient.client.api.ApiModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VariantSelectorTest {

    private fun variant(id: Int, skinModel: String) = ApiModels.Variant(
        id = id,
        name = "v$id",
        skinModel = skinModel,
        model = ApiModels.AssetRef("m$id", "https://example.invalid/m$id.json"),
        texture = ApiModels.AssetRef("t$id", "https://example.invalid/t$id.png"),
        sortOrder = id,
    )

    @Test
    fun `a pinned variant wins over the skin model`() {
        val variants = listOf(variant(1, "wide"), variant(2, "slim"))
        assertEquals(1, VariantSelector.pick(variants, 1, "slim")?.id)
    }

    @Test
    fun `otherwise the wearer's skin model decides`() {
        val variants = listOf(variant(1, "any"), variant(2, "wide"), variant(3, "slim"))
        assertEquals(3, VariantSelector.pick(variants, null, "slim")?.id)
        assertEquals(2, VariantSelector.pick(variants, null, "wide")?.id)
    }

    @Test
    fun `then the first any variant, then the first variant`() {
        assertEquals(2, VariantSelector.pick(listOf(variant(1, "wide"), variant(2, "any"), variant(3, "any")), null, "slim")?.id)
        assertEquals(4, VariantSelector.pick(listOf(variant(4, "wide"), variant(5, "wide")), null, "slim")?.id)
    }

    @Test
    fun `a pinned variant that no longer exists falls back to the automatic choice`() {
        val variants = listOf(variant(1, "wide"), variant(2, "slim"))
        assertEquals(2, VariantSelector.pick(variants, 99, "slim")?.id)
    }

    @Test
    fun `no variants, nothing to draw`() {
        assertNull(VariantSelector.pick(emptyList(), null, "wide"))
        assertNull(VariantSelector.pick(emptyList(), 1, "wide"))
    }

    @Test
    fun `real lookup data picks per skin model`() {
        val profile = Fixtures.ok("lookup", ApiModels.PlayerProfile::readList).single { it.name == "LeiCraft_Steve" }
        val hat = profile.cosmetics.single { it.productId == 2 }
        assertEquals("Slim", VariantSelector.pick(hat.variants, hat.variantId, VariantSelector.SLIM)?.name)
        assertEquals("Wide", VariantSelector.pick(hat.variants, hat.variantId, VariantSelector.WIDE)?.name)

        val crown = profile.cosmetics.single { it.productId == 3 }
        assertEquals(3, VariantSelector.pick(crown.variants, crown.variantId, VariantSelector.SLIM)?.id)
    }
}
