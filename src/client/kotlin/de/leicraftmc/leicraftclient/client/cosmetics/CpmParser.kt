package de.leicraftmc.leicraftclient.client.cosmetics

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.leicraftmc.leicraftclient.client.LeiCraftClient

/**
 * Parses OptiFine "Custom Player Model" (`model.cfg`) files into [CpmModel].
 *
 * These files are written in Blockbench's coordinate space; each node carries an
 * `invertAxis` string naming the axes that must be mirrored to reach Minecraft's
 * model space (Y down, Z south). Blockbench exports `"xy"` on every node, which
 * is a 180 degree turn about Z rather than a true mirror, so winding and UVs are
 * left alone in the common case.
 *
 * `invertAxis` describes the node's own numbers only -- it is NOT inherited,
 * otherwise nested bones (which all carry `"xy"`) would cancel each other out.
 *
 * Parsing is intentionally forgiving: these files are hand-written and a decade
 * old, so a bad node is skipped rather than failing the whole model.
 */
object CpmParser {

    private const val DEFAULT_TEXTURE_WIDTH = 64f
    private const val DEFAULT_TEXTURE_HEIGHT = 32f

    fun parse(json: String): CpmModel? {
        val root = try {
            JsonParser.parseString(json) as? JsonObject ?: return null
        } catch (e: Exception) {
            LeiCraftClient.LOGGER.warn("Malformed CPM model", e)
            return null
        }

        val size = root.floats("textureSize")
        val texW = size?.getOrNull(0)?.takeIf { it > 0f } ?: DEFAULT_TEXTURE_WIDTH
        val texH = size?.getOrNull(1)?.takeIf { it > 0f } ?: DEFAULT_TEXTURE_HEIGHT

        val models = root.array("models") ?: return null
        val attachments = models.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val node = parseNode(obj, texW, texH) ?: return@mapNotNull null
            CpmAttachment(CpmAttachPoint.of(obj.string("attachTo")), node)
        }
        if (attachments.isEmpty()) return null

        return CpmModel(root.bool("usePlayerTexture", false), attachments)
    }

    private fun parseNode(obj: JsonObject, texW: Float, texH: Float): CpmNode? {
        val invert = obj.string("invertAxis").orEmpty().lowercase()
        val sx = if (invert.contains("x")) -1f else 1f
        val sy = if (invert.contains("y")) -1f else 1f
        val sz = if (invert.contains("z")) -1f else 1f

        val translate = obj.floats("translate")
        val rotate = obj.floats("rotate")

        // A rotation is an axial vector: under the mirror S it picks up
        // det(S) / s_axis rather than s_axis itself. For the usual "xy" this
        // works out to (-rx, -ry, +rz).
        val det = sx * sy * sz
        val rx = (rotate?.getOrNull(0) ?: 0f) * (det / sx)
        val ry = (rotate?.getOrNull(1) ?: 0f) * (det / sy)
        val rz = (rotate?.getOrNull(2) ?: 0f) * (det / sz)

        val scale = parseScale(obj)
        val mirrorTexture = obj.string("mirrorTexture").orEmpty().lowercase()

        val quads = ArrayList<CpmQuad>()
        obj.array("boxes")?.forEach { element ->
            val box = element as? JsonObject ?: return@forEach
            try {
                bakeBox(box, texW, texH, sx, sy, sz, mirrorTexture, quads)
            } catch (e: Exception) {
                LeiCraftClient.LOGGER.warn("Skipping malformed CPM box", e)
            }
        }

        val children = ArrayList<CpmNode>()
        obj.array("submodels")?.forEach { element ->
            val sub = element as? JsonObject ?: return@forEach
            parseNode(sub, texW, texH)?.let(children::add)
        }

        if (quads.isEmpty() && children.isEmpty()) return null

        return CpmNode(
            x = (translate?.getOrNull(0) ?: 0f) * sx,
            y = (translate?.getOrNull(1) ?: 0f) * sy,
            z = (translate?.getOrNull(2) ?: 0f) * sz,
            xRot = Math.toRadians(rx.toDouble()).toFloat(),
            yRot = Math.toRadians(ry.toDouble()).toFloat(),
            zRot = Math.toRadians(rz.toDouble()).toFloat(),
            xScale = scale[0],
            yScale = scale[1],
            zScale = scale[2],
            quads = quads.toTypedArray(),
            children = children.toTypedArray(),
        )
    }

    private fun parseScale(obj: JsonObject): FloatArray {
        val element = obj.get("scale") ?: return floatArrayOf(1f, 1f, 1f)
        return try {
            if (element.isJsonArray) {
                val a = obj.floats("scale") ?: return floatArrayOf(1f, 1f, 1f)
                floatArrayOf(
                    a.getOrElse(0) { 1f },
                    a.getOrElse(1) { 1f },
                    a.getOrElse(2) { 1f },
                )
            } else {
                val s = element.asFloat
                floatArrayOf(s, s, s)
            }
        } catch (e: Exception) {
            floatArrayOf(1f, 1f, 1f)
        }
    }

    /** Builds the six faces of one `boxes` entry. */
    @Suppress("LongParameterList")
    private fun bakeBox(
        box: JsonObject,
        texW: Float,
        texH: Float,
        sx: Float,
        sy: Float,
        sz: Float,
        mirrorTexture: String,
        out: MutableList<CpmQuad>,
    ) {
        val c = box.floats("coordinates") ?: return
        if (c.size < 6) return
        val w = c[3]
        val h = c[4]
        val d = c[5]

        // `sizeAdd` grows the box on every side, like vanilla's CubeDeformation.
        // The UV rects keep using the *uninflated* size.
        val grow = box.float("sizeAdd", 0f)
        val x0 = c[0] - grow
        val x1 = c[0] + w + grow
        val y0 = c[1] - grow
        val y1 = c[1] + h + grow
        val z0 = c[2] - grow
        val z1 = c[2] + d + grow

        val perFace = box.has("uvNorth")
        val offset = if (perFace) null else box.floats("textureOffset")
        val u = offset?.getOrNull(0) ?: 0f
        val v = offset?.getOrNull(1) ?: 0f

        fun rect(name: String, fallback: FloatArray): FloatArray =
            if (perFace) box.floats(name) ?: fallback else fallback

        // Standard Minecraft box unwrap, expressed in CPM face names.
        val north = rect("uvNorth", floatArrayOf(u + d, v + d, u + d + w, v + d + h))
        val south = rect("uvSouth", floatArrayOf(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h))
        val east = rect("uvEast", floatArrayOf(u, v + d, u + d, v + d + h))
        val west = rect("uvWest", floatArrayOf(u + w + d, v + d, u + w + 2 * d, v + d + h))
        val up = rect("uvUp", floatArrayOf(u + d, v, u + d + w, v + d))
        val down = rect("uvDown", floatArrayOf(u + d + w, v, u + 2 * w + d, v + d))

        val ctx = FaceContext(texW, texH, sx, sy, sz, mirrorTexture, out)

        // Corners run top-left, top-right, bottom-right, bottom-left of the rect.
        ctx.face(north, 0f, 0f, -1f, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0)
        ctx.face(south, 0f, 0f, 1f, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1)
        ctx.face(east, 1f, 0f, 0f, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1)
        ctx.face(west, -1f, 0f, 0f, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0)
        ctx.face(up, 0f, 1f, 0f, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1)
        ctx.face(down, 0f, -1f, 0f, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0)
    }

    private class FaceContext(
        val texW: Float,
        val texH: Float,
        val sx: Float,
        val sy: Float,
        val sz: Float,
        val mirrorTexture: String,
        val out: MutableList<CpmQuad>,
    ) {
        /** True when the mirror flips handedness and the winding must be reversed. */
        val reversed = sx * sy * sz < 0f

        @Suppress("LongParameterList")
        fun face(
            uvRect: FloatArray,
            nx: Float,
            ny: Float,
            nz: Float,
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float,
            dx: Float, dy: Float, dz: Float,
        ) {
            if (uvRect.size < 4) return

            var u1 = uvRect[0]
            var v1 = uvRect[1]
            var u2 = uvRect[2]
            var v2 = uvRect[3]
            if (mirrorTexture.contains("u")) {
                val t = u1; u1 = u2; u2 = t
            }
            if (mirrorTexture.contains("v")) {
                val t = v1; v1 = v2; v2 = t
            }

            val pos = floatArrayOf(
                ax * sx, ay * sy, az * sz,
                bx * sx, by * sy, bz * sz,
                cx * sx, cy * sy, cz * sz,
                dx * sx, dy * sy, dz * sz,
            )
            val uv = floatArrayOf(
                u1 / texW, v1 / texH,
                u2 / texW, v1 / texH,
                u2 / texW, v2 / texH,
                u1 / texW, v2 / texH,
            )

            if (reversed) {
                reverseQuad(pos, 3)
                reverseQuad(uv, 2)
            }

            out.add(CpmQuad(pos, uv, nx * sx, ny * sy, nz * sz))
        }

        /** Reverses the four vertices of a quad stored as flat [stride]-sized tuples. */
        private fun reverseQuad(data: FloatArray, stride: Int) {
            for (i in 0 until stride) {
                val first = data[i]
                data[i] = data[3 * stride + i]
                data[3 * stride + i] = first
                val second = data[stride + i]
                data[stride + i] = data[2 * stride + i]
                data[2 * stride + i] = second
            }
        }
    }

    // -- lenient JSON helpers -------------------------------------------------

    private fun JsonObject.array(key: String): JsonArray? =
        runCatching { getAsJsonArray(key) }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        runCatching { get(key).asString }.getOrNull()

    private fun JsonObject.float(key: String, fallback: Float): Float =
        runCatching { get(key).asFloat }.getOrNull() ?: fallback

    /** Accepts real booleans and the `"true"` strings the old backend emits. */
    private fun JsonObject.bool(key: String, fallback: Boolean): Boolean {
        val element = get(key) ?: return fallback
        return runCatching { element.asBoolean }.getOrNull()
            ?: runCatching { element.asString.equals("true", ignoreCase = true) }.getOrNull()
            ?: fallback
    }

    private fun JsonObject.floats(key: String): FloatArray? {
        val arr = array(key) ?: return null
        return FloatArray(arr.size()) { i -> runCatching { arr[i].asFloat }.getOrDefault(0f) }
    }
}
