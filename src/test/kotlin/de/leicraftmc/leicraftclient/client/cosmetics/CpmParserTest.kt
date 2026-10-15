package de.leicraftmc.leicraftclient.client.cosmetics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for the OptiFine CPM transform, using real cosmetics from the
 * live backend as fixtures.
 *
 * The maths here was derived rather than copied, so these assertions pin down
 * the parts that would otherwise fail silently and only show up as a cosmetic
 * floating in the wrong place.
 */
class CpmParserTest {

    private fun load(name: String): CpmModel {
        val json = checkNotNull(javaClass.getResourceAsStream("/models/$name.cfg")) {
            "missing fixture $name"
        }.bufferedReader().readText()
        return assertNotNull(CpmParser.parse(json), "failed to parse $name")
    }

    /** Every vertex of a node tree, in Minecraft model space. */
    private fun vertices(node: CpmNode, ox: Float = 0f, oy: Float = 0f, oz: Float = 0f): List<Triple<Float, Float, Float>> {
        // Fixtures have no rotation on the nodes being measured, so accumulating
        // translations is enough to place the geometry.
        val x = ox + node.x
        val y = oy + node.y
        val z = oz + node.z
        val out = mutableListOf<Triple<Float, Float, Float>>()
        for (quad in node.quads) {
            for (i in 0 until 4) {
                out += Triple(x + quad.pos[i * 3], y + quad.pos[i * 3 + 1], z + quad.pos[i * 3 + 2])
            }
        }
        for (child in node.children) {
            out += vertices(child, x, y, z)
        }
        return out
    }

    @Test
    fun `christmas hat sits on top of the head`() {
        val model = load("christmas_hat")
        val attachment = model.attachments.single()
        assertEquals(CpmAttachPoint.HEAD, attachment.attachTo)

        // The head bone's pivot is the neck and Y grows downward, so a hat must
        // be entirely above it -- every vertex at a negative Y. The vanilla head
        // cube spans -8..0, so the brim should sit at about -8.
        val ys = vertices(attachment.root).map { it.second }
        assertTrue(ys.all { it < 0f }, "hat reaches below the neck: max Y was ${ys.max()}")
        assertEquals(-8f, ys.max(), 0.001f, "hat brim should rest on the head")
    }

    @Test
    fun `unicorn horn points forward and up`() {
        val model = load("unicorn")
        val attachment = model.attachments.single()
        assertEquals(CpmAttachPoint.HEAD, attachment.attachTo)

        val verts = vertices(attachment.root)
        assertTrue(verts.all { it.second < 0f }, "horn should be above the neck")
        // Z grows south (behind the player), so a horn must reach north.
        assertTrue(verts.minOf { it.third } < -3f, "horn should stick out in front")
    }

    @Test
    fun `wand extends forward from the right hand`() {
        val model = load("arozauber")
        val attachment = model.attachments.single()
        assertEquals(CpmAttachPoint.RIGHT_ARM, attachment.attachTo)
        assertTrue(model.usePlayerTexture, "arozauber is drawn with the player's own skin")

        // Raw CPM box is [0.5, -8.5, -14] size [1, 1, 12] with invertAxis "xy",
        // so X and Y mirror while Z is left alone.
        val verts = vertices(attachment.root)
        assertEquals(-1.5f, verts.minOf { it.first }, 0.001f)
        assertEquals(-0.5f, verts.maxOf { it.first }, 0.001f)
        assertEquals(7.5f, verts.minOf { it.second }, 0.001f)
        assertEquals(8.5f, verts.maxOf { it.second }, 0.001f)
        // Z is untouched by "xy": the wand reaches forward from the hand.
        assertEquals(-14f, verts.minOf { it.third }, 0.001f)
        assertEquals(-2f, verts.maxOf { it.third }, 0.001f)
    }

    @Test
    fun `a box becomes six quads with normalised uvs`() {
        val model = load("unicorn")
        val node = model.attachments.single().root
        val quads = node.children.single().quads
        assertEquals(6, quads.size, "one box should bake into six faces")
        for (quad in quads) {
            assertEquals(12, quad.pos.size)
            assertEquals(8, quad.uv.size)
            assertTrue(quad.uv.all { it in 0f..1f }, "uv outside 0..1: ${quad.uv.toList()}")
        }
    }

    @Test
    fun `per-face uv boxes are supported`() {
        // `chain` uses uvNorth/uvEast/... instead of textureOffset, and has
        // zero-depth boxes, which is why the layer renders without culling.
        val model = load("chain")
        val quads = vertices(model.attachments.single().root)
        assertTrue(quads.isNotEmpty())

        val node = model.attachments.single().root
        val flat = node.children.flatMap { it.quads.toList() }
        assertTrue(flat.isNotEmpty())
        assertTrue(flat.all { it.uv.all { uv -> uv in 0f..1f } }, "per-face uvs must normalise")
    }

    @Test
    fun `nested invertAxis does not compound`() {
        // clientuse has invertAxis "xyz" on the root and "xy" on its children.
        // If the mirrors compounded, the children would land back in Blockbench
        // space and the badge would sit far below the feet.
        val model = load("clientuse")
        val attachment = model.attachments.single()
        assertEquals(CpmAttachPoint.BODY, attachment.attachTo)

        val ys = vertices(attachment.root).map { it.second }
        // The body bone pivots at the neck and the torso spans 0..12, so a badge
        // on the chest and shoulders lands at roughly -2..5.
        //
        // If the mirrors compounded, the root's "xyz" and a child's "xy" would
        // combine to (1, 1, -1): the children would keep their raw +18 offsets
        // on top of the root's +24 and the whole thing would sit near y = 42,
        // well below the feet. The upper bound is what guards against that.
        assertTrue(ys.max() < 14f, "badge fell below the body: max Y was ${ys.max()}")
        assertTrue(ys.min() > -6f, "badge floated above the shoulders: min Y was ${ys.min()}")
    }

    @Test
    fun `sizeAdd inflates a box on every side`() {
        val json = """
            {
              "type": "PlayerItem",
              "textureSize": [64, 64],
              "models": [{
                "type": "ModelBox", "attachTo": "head", "invertAxis": "xy",
                "boxes": [{"coordinates": [0, 0, 0, 2, 2, 2], "textureOffset": [0, 0], "sizeAdd": 1}]
              }]
            }
        """.trimIndent()
        val model = assertNotNull(CpmParser.parse(json))
        val verts = vertices(model.attachments.single().root)
        // Box spans 0..2 inflated by 1 on each side -> -1..3, then X and Y mirror.
        assertEquals(-3f, verts.minOf { it.first }, 0.001f)
        assertEquals(1f, verts.maxOf { it.first }, 0.001f)
        assertEquals(-1f, verts.minOf { it.third }, 0.001f)
        assertEquals(3f, verts.maxOf { it.third }, 0.001f)
    }

    @Test
    fun `unknown attach points fall back to the body`() {
        assertEquals(CpmAttachPoint.BODY, CpmAttachPoint.of("somethingElse"))
        assertEquals(CpmAttachPoint.BODY, CpmAttachPoint.of(null))
        assertEquals(CpmAttachPoint.RIGHT_ARM, CpmAttachPoint.of("rightArm"))
    }

    @Test
    fun `malformed input is rejected rather than thrown`() {
        assertEquals(null, CpmParser.parse("not json"))
        assertEquals(null, CpmParser.parse("{}"))
        assertEquals(null, CpmParser.parse("""{"models": []}"""))
    }
}
