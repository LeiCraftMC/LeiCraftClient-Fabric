package de.leicraftmc.leicraftclient.client.assets

import de.leicraftmc.leicraftclient.client.Fixtures
import de.leicraftmc.leicraftclient.client.cosmetics.CpmParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Content addressing: an asset is only accepted if its bytes hash to its id. */
class AssetIntegrityTest {

    /** A cape texture and a cosmetic model as the API stores and serves them. */
    private val capeId = "92a16c6a82846eda32b8084d6a0759d9b5ea59ae08f43269deccb5e4f3febf56"
    private val modelId = "d574b5a67cc29db6993bb25baa9ea1630b6283ad05c7eab5b7e64d385858d0dc"

    @Test
    fun `served assets hash to their ids`() {
        val cape = Fixtures.bytes("assets/$capeId.png")
        assertEquals(capeId, AssetIntegrity.sha256Hex(cape))
        assertTrue(AssetIntegrity.matches(capeId, cape))
        assertTrue(AssetIntegrity.matches(capeId.uppercase(), cape), "hex case does not matter")

        assertTrue(AssetIntegrity.matches(modelId, Fixtures.bytes("assets/$modelId.json")))
    }

    @Test
    fun `tampered or truncated bytes are rejected`() {
        val cape = Fixtures.bytes("assets/$capeId.png")
        val tampered = cape.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFalse(AssetIntegrity.matches(capeId, tampered))
        assertFalse(AssetIntegrity.matches(capeId, cape.copyOf(cape.size - 1)))
        assertFalse(AssetIntegrity.matches(modelId, cape), "right bytes, wrong id")
    }

    @Test
    fun `only 64 hex digit ids are accepted, so ids are safe file names`() {
        assertTrue(AssetIntegrity.isValidId(capeId))
        for (bad in listOf("", "abc", capeId + "0", capeId.dropLast(1) + "g", "../" + capeId.drop(3), capeId.dropLast(4) + ".png")) {
            assertFalse(AssetIntegrity.isValidId(bad), bad)
            assertFalse(AssetIntegrity.matches(bad, ByteArray(0)), bad)
        }
        assertEquals("$capeId.png", AssetIntegrity.fileName(capeId.uppercase(), "png"))
        assertFailsWith<IllegalArgumentException> { AssetIntegrity.fileName("../evil", "png") }
    }

    @Test
    fun `served cosmetic models parse with the CPM parser`() {
        val model = assertNotNull(CpmParser.parse(Fixtures.text("assets/$modelId.json")))
        assertFalse(model.usePlayerTexture)
        assertTrue(model.attachments.isNotEmpty())
    }
}
