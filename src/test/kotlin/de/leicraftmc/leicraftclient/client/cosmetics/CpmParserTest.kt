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
    private fun vertices(node: CpmModel.Node, ox: Float = 0f, oy: Float = 0f, oz: Float = 0f): List<Triple<Float, Float, Float>> {
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
        assertEquals(CpmModel.AttachPoint.HEAD, attachment.attachTo)

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
        assertEquals(CpmModel.AttachPoint.HEAD, attachment.attachTo)

        val verts = vertices(attachment.root)
        assertTrue(verts.all { it.second < 0f }, "horn should be above the neck")
        // Z grows south (behind the player), so a horn must reach north.
        assertTrue(verts.minOf { it.third } < -3f, "horn should stick out in front")
    }

    @Test
    fun `wand extends forward from the right hand`() {
        val model = load("arozauber")
        val attachment = model.attachments.single()
        assertEquals(CpmModel.AttachPoint.RIGHT_ARM, attachment.attachTo)
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
        assertEquals(CpmModel.AttachPoint.BODY, attachment.attachTo)

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
        assertEquals(CpmModel.AttachPoint.BODY, CpmModel.AttachPoint.of("somethingElse"))
        assertEquals(CpmModel.AttachPoint.BODY, CpmModel.AttachPoint.of(null))
        assertEquals(CpmModel.AttachPoint.RIGHT_ARM, CpmModel.AttachPoint.of("rightArm"))
    }

    @Test
    fun `malformed input is rejected rather than thrown`() {
        assertEquals(null, CpmParser.parse("not json"))
        assertEquals(null, CpmParser.parse("{}"))
        assertEquals(null, CpmParser.parse("""{"models": []}"""))
    }

    @Test
    fun `sizesAdd inflates each axis separately`() {
        val json = box("""{"coordinates": [0, 0, 0, 2, 2, 2], "textureOffset": [0, 0], "sizesAdd": [1, 2, 3]}""")
        val verts = vertices(assertNotNull(CpmParser.parse(json)).attachments.single().root)
        // X and Y mirror under "xy"; Z does not. Spans become -1..3, -2..4, -3..5.
        assertEquals(-3f, verts.minOf { it.first }, 0.001f)
        assertEquals(1f, verts.maxOf { it.first }, 0.001f)
        assertEquals(-4f, verts.minOf { it.second }, 0.001f)
        assertEquals(2f, verts.maxOf { it.second }, 0.001f)
        assertEquals(-3f, verts.minOf { it.third }, 0.001f)
        assertEquals(5f, verts.maxOf { it.third }, 0.001f)
    }

    @Test
    fun `sizeAdd written as a string is accepted`() {
        // OptiFine's own hat_jiingy model writes "sizeAdd": "0.1".
        val json = box("""{"coordinates": [0, 0, 0, 2, 2, 2], "textureOffset": [0, 0], "sizeAdd": "1"}""")
        val verts = vertices(assertNotNull(CpmParser.parse(json)).attachments.single().root)
        assertEquals(-1f, verts.minOf { it.third }, 0.001f)
        assertEquals(3f, verts.maxOf { it.third }, 0.001f)
    }

    @Test
    fun `a singular submodel is read like a submodels list`() {
        val json = """
            {
              "type": "PlayerItem", "textureSize": [64, 64],
              "models": [{
                "type": "ModelBox", "attachTo": "head", "invertAxis": "xy",
                "submodel": {
                  "invertAxis": "xy", "translate": [0, 4, 0],
                  "boxes": [{"coordinates": [0, 0, 0, 1, 1, 1], "textureOffset": [0, 0]}]
                }
              }]
            }
        """.trimIndent()
        val root = assertNotNull(CpmParser.parse(json)).attachments.single().root
        assertEquals(1, root.children.size)
        assertEquals(-4f, root.children.single().y, 0.001f)
    }

    @Test
    fun `per-face uv aliases are accepted`() {
        // OptiFine allows Front/Back/Left/Right as aliases for North/South/West/East.
        val json = box(
            """{"coordinates": [0, 0, 0, 2, 2, 2],
                "uvFront": [0, 0, 8, 8], "uvBack": [0, 0, 8, 8],
                "uvLeft": [0, 0, 8, 8], "uvRight": [0, 0, 8, 8],
                "uvUp": [0, 0, 8, 8], "uvDown": [0, 0, 8, 8]}""",
        )
        val quads = assertNotNull(CpmParser.parse(json)).attachments.single().root.quads
        assertEquals(6, quads.size)
        // 8/64 = 0.125 on every corner; proves the aliases were read rather than
        // the box falling back to a missing textureOffset.
        assertTrue(quads.all { q -> q.uv.all { it == 0f || it == 0.125f } }, "aliases ignored")
    }

    @Test
    fun `a box declaring only uvUp is not treated as textureOffset`() {
        val json = box("""{"coordinates": [0, 0, 0, 2, 2, 2], "uvUp": [0, 0, 64, 32]}""")
        val quads = assertNotNull(CpmParser.parse(json)).attachments.single().root.quads
        val up = quads[4]
        assertEquals(1f, up.uv.max(), 0.001f, "uvUp should map to the full texture")
    }

    @Test
    fun `invertAxis z mirrors only depth`() {
        // OptiFine's mrcheeze model uses "z" alone, so the generic rule has to
        // hold rather than just the Blockbench "xy" default.
        val json = """
            {
              "type": "PlayerItem", "textureSize": [64, 64],
              "models": [{
                "type": "ModelBox", "attachTo": "body", "invertAxis": "z",
                "translate": [1, 2, 3],
                "boxes": [{"coordinates": [0, 0, 4, 1, 1, 1], "textureOffset": [0, 0]}]
              }]
            }
        """.trimIndent()
        val root = assertNotNull(CpmParser.parse(json)).attachments.single().root
        assertEquals(1f, root.x, 0.001f)
        assertEquals(2f, root.y, 0.001f)
        assertEquals(-3f, root.z, 0.001f)

        val verts = vertices(root)
        // X and Y keep their signs, Z mirrors: [4,5] -> [-5,-4], then +(-3).
        assertEquals(1f, verts.minOf { it.first }, 0.001f)
        assertEquals(2f, verts.maxOf { it.first }, 0.001f)
        assertEquals(-8f, verts.minOf { it.third }, 0.001f)
        assertEquals(-7f, verts.maxOf { it.third }, 0.001f)
    }

    @Test
    fun `models may declare their own texture`() {
        val json = """
            {
              "type": "PlayerItem", "texture": "assets/items/x/custom.png", "textureSize": [64, 64],
              "models": [{
                "type": "ModelBox", "attachTo": "head", "invertAxis": "xy",
                "boxes": [{"coordinates": [0, 0, 0, 1, 1, 1], "textureOffset": [0, 0]}]
              }]
            }
        """.trimIndent()
        assertEquals("assets/items/x/custom.png", assertNotNull(CpmParser.parse(json)).texturePath)
    }

    /** Wraps a single box literal in a minimal head-attached model. */
    private fun box(boxJson: String): String = """
        {
          "type": "PlayerItem", "textureSize": [64, 64],
          "models": [{
            "type": "ModelBox", "attachTo": "head", "invertAxis": "xy",
            "boxes": [$boxJson]
          }]
        }
    """.trimIndent()

    @Test
    fun `faces wind so the outward normal is counter-clockwise`() {
        // Vanilla cube faces are wound so the cross product of the first two
        // edges points the same way as the face normal. The layer draws with
        // backface culling, so getting this backwards hides every face --
        // and before it was fixed it made coplanar boxes z-fight instead.
        for (invert in listOf("xy", "xyz", "z", "")) {
            val json = """
                {
                  "type": "PlayerItem", "textureSize": [64, 64],
                  "models": [{
                    "type": "ModelBox", "attachTo": "head", "invertAxis": "$invert",
                    "boxes": [{"coordinates": [1, 2, 3, 2, 3, 4], "textureOffset": [0, 0]}]
                  }]
                }
            """.trimIndent()
            val quads = assertNotNull(CpmParser.parse(json)).attachments.single().root.quads
            assertEquals(6, quads.size)
            for ((i, q) in quads.withIndex()) {
                val e1 = floatArrayOf(q.pos[3] - q.pos[0], q.pos[4] - q.pos[1], q.pos[5] - q.pos[2])
                val e2 = floatArrayOf(q.pos[6] - q.pos[3], q.pos[7] - q.pos[4], q.pos[8] - q.pos[5])
                val cx = e1[1] * e2[2] - e1[2] * e2[1]
                val cy = e1[2] * e2[0] - e1[0] * e2[2]
                val cz = e1[0] * e2[1] - e1[1] * e2[0]
                val dot = cx * q.nx + cy * q.ny + cz * q.nz
                assertTrue(dot > 0f, "invertAxis=\"$invert\" face $i winds inward (dot=$dot)")
            }
        }
    }

    @Test
    fun `the sword bakes to the texels its atlas tile occupies`() {
        // dia_sword is the densest model served: 31 coplanar boxes, each taking a
        // small tile out of a tightly packed 32x32 atlas, so a UV that is off by a
        // single texel shows a neighbouring tile's colours instead.
        val model = load("dia_sword")
        val root = model.attachments.single().root
        val bone = root.children.single()

        assertEquals(-0.04839f, bone.x, 1e-5f)
        assertEquals(7.04839f, bone.y, 1e-5f)
        assertEquals(2.5f, bone.z, 1e-5f)
        assertEquals(Math.PI.toFloat(), bone.zRot.let { bone.xRot }, 1e-5f)
        assertEquals(31 * 6, bone.quads.size)

        // First box: coordinates [-8.95161, -8.95161, -0.75, 3, 3, 1] at
        // textureOffset [0, 8], so its front face is the 3x3 tile at (1,9)-(4,12).
        val front = bone.quads[0]
        assertEquals(-1f, front.nz, 1e-6f)
        val texels = front.uv.toList().chunked(2).map { (it[0] * 32f) to (it[1] * 32f) }
        assertEquals(setOf(1f to 12f, 4f to 12f, 4f to 9f, 1f to 9f), texels.toSet())
    }
}
