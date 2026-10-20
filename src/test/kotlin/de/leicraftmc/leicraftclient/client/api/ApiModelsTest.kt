package de.leicraftmc.leicraftclient.client.api

import com.google.gson.JsonParser
import de.leicraftmc.leicraftclient.client.Fixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading every response the mod uses, from real API responses. */
class ApiModelsTest {

    private val steve = UUID.fromString("4566e69f-c907-48ee-8d71-d7ba5aa00d20")
    private val alex = UUID.fromString("0f3b1c2d-6e5f-4a1b-9c8d-7e6f5a4b3c2d")

    @Test
    fun `lookup reads capes, cosmetics and variants`() {
        val profiles = Fixtures.ok("lookup", ApiModels.PlayerProfile::readList)
        // Three UUIDs were asked for; the unknown one is simply omitted.
        assertEquals(setOf(steve, alex), profiles.map { it.uuid }.toSet())

        val other = profiles.single { it.uuid == alex }
        assertTrue(other.usesClient)
        assertNull(other.cape)
        assertTrue(other.cosmetics.isEmpty())

        val profile = profiles.single { it.uuid == steve }
        assertEquals("LeiCraft_Steve", profile.name)
        val cape = assertNotNull(profile.cape)
        assertEquals(1, cape.productId)
        assertEquals("92a16c6a82846eda32b8084d6a0759d9b5ea59ae08f43269deccb5e4f3febf56", cape.texture.id)
        assertTrue(cape.texture.url.endsWith("/assets/${cape.texture.id}.png"))

        assertEquals(listOf(2, 3), profile.cosmetics.map { it.productId })
        val hat = profile.cosmetics[0]
        assertNull(hat.variantId)
        assertEquals(listOf("wide", "slim"), hat.variants.map { it.skinModel })
        assertTrue(hat.variants[0].model.url.endsWith(".json"))
        assertTrue(hat.variants[0].texture.url.endsWith(".png"))
        assertEquals(3, profile.cosmetics[1].variantId)
        assertEquals("any", profile.cosmetics[1].variants.single().skinModel)
    }

    @Test
    fun `a malformed profile is dropped, not the whole lookup`() {
        val data = JsonParser.parseString(
            """[{"uuid":"not-a-uuid","name":"x","uses_client":true,"cape":null,"cosmetics":[]},
                {"uuid":"${alex}","name":"Alex_Other","uses_client":false,"cape":null,"cosmetics":[]}]""",
        )
        val profiles = ApiModels.PlayerProfile.readList(data)
        assertEquals(listOf(alex), profiles.map { it.uuid })
    }

    @Test
    fun `verify and session read the account and linked user`() {
        val verified = Fixtures.ok("verify", ApiModels.Verified::read)
        assertTrue(verified.token.startsWith("lcc_client_"))
        assertTrue(verified.expiresAt > 1_700_000_000_000L, "expires_at is epoch milliseconds")
        assertEquals(steve, verified.account.uuid)
        assertEquals("slim", verified.account.skinModel)
        assertTrue(verified.account.usesClient)
        assertNull(verified.account.linkedAt)
        assertNull(verified.user)

        val session = Fixtures.ok("session", ApiModels.Session::read)
        assertEquals(steve, session.account.uuid)
        assertNull(session.user)
    }

    @Test
    fun `link status and created links`() {
        val unlinked = Fixtures.ok("link-status-unlinked", ApiModels.LinkStatus::read)
        assertFalse(unlinked.linked)
        assertNull(unlinked.pendingCodeExpiresAt)

        val pending = Fixtures.ok("link-status-pending", ApiModels.LinkStatus::read)
        assertFalse(pending.linked)
        assertNotNull(pending.pendingCodeExpiresAt)

        val linked = Fixtures.ok("link-status-linked", ApiModels.LinkStatus::read)
        assertTrue(linked.linked)
        assertEquals("Steve", linked.user?.displayName)

        val link = Fixtures.ok("link-create", ApiModels.CreatedLink::read)
        assertTrue(link.url.contains("/link/minecraft?code=${link.code}"))
    }

    @Test
    fun `wardrobe pages read products, pages and ownership`() {
        val capes = Fixtures.ok("wardrobe-capes", ApiModels.WardrobeItem::readList)
        val cape = capes.single()
        assertTrue(cape.product.isCape)
        assertEquals("Founder Cape", cape.product.name)
        assertNotNull(cape.product.texture)
        assertTrue(cape.product.variants.isEmpty())
        assertEquals("LeiCraft Client", cape.page.name)
        assertEquals("official", cape.page.category)

        val cosmetics = Fixtures.ok("wardrobe-cosmetics", ApiModels.WardrobeItem::readList)
        // Owned exclusives come first.
        assertEquals(listOf("Golden Crown", "Top Hat"), cosmetics.map { it.product.name })
        assertEquals(listOf(true, false), cosmetics.map { it.owned })
        assertEquals("exclusive", cosmetics[0].product.availability)
        assertNull(cosmetics[1].product.texture)
        assertEquals(listOf("Wide", "Slim"), cosmetics[1].product.variants.map { it.name })
    }

    @Test
    fun `loadouts read the cape and pinned variants`() {
        val empty = Fixtures.ok("loadout-empty", ApiModels.Loadout::read)
        assertNull(empty.cape)
        assertTrue(empty.cosmetics.isEmpty())

        val loadout = Fixtures.ok("loadout", ApiModels.Loadout::read)
        assertEquals(1, loadout.cape?.id)
        assertEquals(listOf(2 to null, 3 to 3), loadout.cosmetics.map { it.product.id to it.variantId })

        assertEquals(loadout, Fixtures.ok("loadout-put", ApiModels.Loadout::read))
    }

    @Test
    fun `loadout updates always send cape_product_id and variant_id`() {
        val none = ApiModels.LoadoutUpdate(null, emptyList()).toJson().toString()
        assertEquals("""{"cape_product_id":null,"cosmetics":[]}""", none)

        val full = ApiModels.LoadoutUpdate(1, listOf(2 to null, 3 to 3)).toJson().toString()
        assertEquals(
            """{"cape_product_id":1,"cosmetics":[{"product_id":2,"variant_id":null},{"product_id":3,"variant_id":3}]}""",
            full,
        )
    }

    @Test
    fun `wardrobe queries leave out a blank search`() {
        assertEquals("/v1/client/wardrobe?type=cape&limit=50&offset=0", LeiCraftApi.wardrobePath("cape", "  ", 0))
        assertEquals(
            "/v1/client/wardrobe?type=cosmetic&search=top%20hat%26co&limit=50&offset=50",
            LeiCraftApi.wardrobePath("cosmetic", " top hat&co ", 50),
        )
        val long = LeiCraftApi.wardrobePath("cosmetic", "x".repeat(100), 0)
        assertTrue(long.contains("search=" + "x".repeat(LeiCraftApi.MAX_SEARCH_LENGTH) + "&"))
    }
}
