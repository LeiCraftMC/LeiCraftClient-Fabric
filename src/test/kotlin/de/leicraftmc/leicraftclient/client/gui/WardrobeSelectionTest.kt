package de.leicraftmc.leicraftclient.client.gui

import de.leicraftmc.leicraftclient.client.Fixtures
import de.leicraftmc.leicraftclient.client.api.ApiModels
import de.leicraftmc.leicraftclient.client.profiles.OwnPlayer
import de.leicraftmc.leicraftclient.client.profiles.VariantSelector
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WardrobeSelectionTest {

    private val loadout = Fixtures.ok("loadout", ApiModels.Loadout::read)
    private val wardrobe = Fixtures.ok("wardrobe-cosmetics", ApiModels.WardrobeItem::readList)
    private val hat = wardrobe.single { it.product.name == "Top Hat" }.product
    private val crown = wardrobe.single { it.product.name == "Golden Crown" }.product

    private fun cosmetic(id: Int) = hat.copy(id = id, name = "Cosmetic $id")

    @Test
    fun `starts from the saved loadout and is clean`() {
        val selection = WardrobeSelection(loadout)
        assertEquals(1, selection.cape?.id)
        assertTrue(selection.isEquipped(2))
        assertTrue(selection.isEquipped(3))
        assertFalse(selection.isDirty)
        assertEquals(ApiModels.LoadoutUpdate(1, listOf(2 to null, 3 to 3)), selection.toUpdate())
    }

    @Test
    fun `choosing no cape and back`() {
        val selection = WardrobeSelection(loadout)
        val cape = loadout.cape
        selection.selectCape(null)
        assertTrue(selection.isCapeSelected(null))
        assertTrue(selection.isDirty)
        assertNull(selection.toUpdate().capeProductId)

        selection.selectCape(cape)
        assertFalse(selection.isDirty, "back to what is saved")
    }

    @Test
    fun `cosmetics toggle, up to ten`() {
        val selection = WardrobeSelection(ApiModels.Loadout(null, emptyList()))
        for (id in 1..WardrobeSelection.MAX_COSMETICS) {
            assertEquals(WardrobeSelection.Toggle.ADDED, selection.toggle(cosmetic(id)))
        }
        assertEquals(WardrobeSelection.Toggle.LIMIT_REACHED, selection.toggle(cosmetic(11)))
        assertFalse(selection.isEquipped(11))

        assertEquals(WardrobeSelection.Toggle.REMOVED, selection.toggle(cosmetic(5)))
        assertEquals(WardrobeSelection.Toggle.ADDED, selection.toggle(cosmetic(11)))
        assertEquals(WardrobeSelection.MAX_COSMETICS, selection.toUpdate().cosmetics.size)
    }

    @Test
    fun `the variant button cycles Auto then each variant`() {
        val selection = WardrobeSelection(ApiModels.Loadout(null, emptyList()))
        selection.toggle(hat)
        assertNull(selection.variantOf(hat.id), "a new cosmetic starts on Auto")

        assertEquals("Wide", selection.cycleVariant(hat.id)?.name)
        assertEquals("Slim", selection.cycleVariant(hat.id)?.name)
        assertEquals(listOf(hat.id to hat.variants[1].id), selection.toUpdate().cosmetics)
        assertNull(selection.cycleVariant(hat.id), "wraps around to Auto")
        assertEquals(listOf(hat.id to null), selection.toUpdate().cosmetics)

        assertNull(selection.cycleVariant(crown.id), "not equipped: nothing to cycle")
    }

    @Test
    fun `the preview profile renders the selection`() {
        val selection = WardrobeSelection(loadout)
        selection.toggle(crown) // unequip the crown
        selection.cycleVariant(2) // pin the hat to "Wide"

        val uuid = UUID.randomUUID()
        val preview = OwnPlayer.profileOf(uuid, "LeiCraft_Steve", selection.toLoadout())
        assertEquals(uuid, preview.uuid)
        assertEquals(loadout.cape?.texture, preview.cape?.texture)
        val hatPreview = preview.cosmetics.single()
        assertEquals(2, hatPreview.productId)
        // Pinned to "Wide", so even a slim wearer sees the wide variant.
        assertEquals("Wide", VariantSelector.pick(hatPreview.variants, hatPreview.variantId, VariantSelector.SLIM)?.name)

        selection.selectCape(null)
        assertNull(OwnPlayer.profileOf(uuid, "x", selection.toLoadout()).cape)
    }
}
