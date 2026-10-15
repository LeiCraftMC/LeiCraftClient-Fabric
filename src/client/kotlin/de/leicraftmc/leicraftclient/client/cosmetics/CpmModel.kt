package de.leicraftmc.leicraftclient.client.cosmetics

/**
 * A baked OptiFine "Custom Player Model".
 *
 * Everything here is already in Minecraft model space (Y points down, Z points
 * south) and ready to hand to a [com.mojang.blaze3d.vertex.VertexConsumer] --
 * no per-frame maths beyond the node transforms.
 *
 * Related types are nested rather than scattered across the package, matching
 * the namespace-for-types convention in the LeiCraftMC style guide.
 */
class CpmModel(
    /** Render with the player's own skin instead of the cosmetic's texture. */
    val usePlayerTexture: Boolean,
    /** Backend-relative texture path declared by the model, if it overrides the default. */
    val texturePath: String?,
    val attachments: List<Attachment>,
) {

    /** The player-model bone a cosmetic hangs off. */
    enum class AttachPoint {
        HEAD,
        BODY,
        LEFT_ARM,
        RIGHT_ARM,
        LEFT_LEG,
        RIGHT_LEG,
        ;

        companion object {
            /** Maps OptiFine's `attachTo` strings; unknown values fall back to [BODY]. */
            fun of(raw: String?): AttachPoint = when (raw?.lowercase()) {
                "head" -> HEAD
                "body" -> BODY
                "leftarm", "left_arm" -> LEFT_ARM
                "rightarm", "right_arm" -> RIGHT_ARM
                "leftleg", "left_leg" -> LEFT_LEG
                "rightleg", "right_leg" -> RIGHT_LEG
                else -> BODY
            }
        }
    }

    class Attachment(val attachTo: AttachPoint, val root: Node)

    /**
     * One bone of the model hierarchy.
     *
     * Transform order matches vanilla [net.minecraft.client.model.geom.ModelPart]:
     * translate, then rotate Z, Y, X, then scale.
     */
    class Node(
        @JvmField val x: Float,
        @JvmField val y: Float,
        @JvmField val z: Float,
        /** Radians. */
        @JvmField val xRot: Float,
        @JvmField val yRot: Float,
        @JvmField val zRot: Float,
        @JvmField val xScale: Float,
        @JvmField val yScale: Float,
        @JvmField val zScale: Float,
        @JvmField val quads: Array<Quad>,
        @JvmField val children: Array<Node>,
    ) {
        val hasTransform: Boolean
            get() = x != 0f || y != 0f || z != 0f ||
                xRot != 0f || yRot != 0f || zRot != 0f ||
                xScale != 1f || yScale != 1f || zScale != 1f
    }

    /**
     * A single textured quad, wound TL -> TR -> BR -> BL across its texture rect.
     *
     * [pos] holds 4 vertices as 12 floats; [uv] holds 4 pairs as 8 floats already
     * normalised to 0..1 against the model's declared texture size.
     */
    class Quad(
        @JvmField val pos: FloatArray,
        @JvmField val uv: FloatArray,
        @JvmField val nx: Float,
        @JvmField val ny: Float,
        @JvmField val nz: Float,
    )
}
