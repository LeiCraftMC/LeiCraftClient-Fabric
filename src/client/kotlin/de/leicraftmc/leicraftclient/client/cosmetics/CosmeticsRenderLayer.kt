package de.leicraftmc.leicraftclient.client.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.entity.player.Player
import org.joml.Quaternionf

/**
 * Draws a player's LeiCraft cosmetics on top of the vanilla player model.
 *
 * Registered through Fabric's render-layer callback, so no mixin is involved.
 * Each cosmetic hangs off a vanilla bone and reuses that bone's own transform
 * via [net.minecraft.client.model.geom.ModelPart.translateAndRotate], which
 * keeps cosmetics glued to the body through every vanilla animation for free.
 *
 * Units follow vanilla: the pose stack works in blocks, so model coordinates are
 * divided by 16 exactly where [net.minecraft.client.model.geom.ModelPart] does.
 */
class CosmeticsRenderLayer(
    parent: RenderLayerParent<AvatarRenderState, PlayerModel>,
) : RenderLayer<AvatarRenderState, PlayerModel>(parent) {

    override fun submit(
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        lightCoords: Int,
        state: AvatarRenderState,
        yRot: Float,
        xRot: Float,
    ) {
        if (state.isInvisible) return

        val username = usernameFor(state) ?: return
        val cosmetics = CosmeticsManager.cosmeticsFor(username)
        if (cosmetics.isEmpty()) return

        val model = parentModel
        for (cosmetic in cosmetics) {
            val texture = if (cosmetic.model.usePlayerTexture) {
                state.skin.body().texturePath()
            } else {
                cosmetic.texture
            }
            val renderType = RenderTypes.entityCutout(texture)

            for (attachment in cosmetic.model.attachments) {
                val bone = when (attachment.attachTo) {
                    CpmAttachPoint.HEAD -> model.head
                    CpmAttachPoint.BODY -> model.body
                    CpmAttachPoint.LEFT_ARM -> model.leftArm
                    CpmAttachPoint.RIGHT_ARM -> model.rightArm
                    CpmAttachPoint.LEFT_LEG -> model.leftLeg
                    CpmAttachPoint.RIGHT_LEG -> model.rightLeg
                }
                if (!bone.visible) continue

                poseStack.pushPose()
                bone.translateAndRotate(poseStack)
                submitNode(poseStack, collector, renderType, lightCoords, attachment.root)
                poseStack.popPose()
            }
        }
    }

    private fun submitNode(
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        renderType: RenderType,
        lightCoords: Int,
        node: CpmNode,
    ) {
        poseStack.pushPose()
        if (node.hasTransform) {
            poseStack.translate(node.x * SCALE, node.y * SCALE, node.z * SCALE)
            if (node.xRot != 0f || node.yRot != 0f || node.zRot != 0f) {
                poseStack.rotate(Quaternionf().rotationZYX(node.zRot, node.yRot, node.xRot))
            }
            if (node.xScale != 1f || node.yScale != 1f || node.zScale != 1f) {
                poseStack.scale(node.xScale, node.yScale, node.zScale)
            }
        }

        val quads = node.quads
        if (quads.isNotEmpty()) {
            // The collector copies the pose, so popping below is safe.
            collector.submitCustomGeometry(poseStack, renderType) { pose, consumer ->
                for (quad in quads) {
                    emit(pose, consumer, quad, lightCoords)
                }
            }
        }

        for (child in node.children) {
            submitNode(poseStack, collector, renderType, lightCoords, child)
        }
        poseStack.popPose()
    }

    private fun emit(pose: PoseStack.Pose, consumer: VertexConsumer, quad: CpmQuad, lightCoords: Int) {
        val pos = quad.pos
        val uv = quad.uv
        for (i in 0 until 4) {
            consumer.addVertex(pose, pos[i * 3] * SCALE, pos[i * 3 + 1] * SCALE, pos[i * 3 + 2] * SCALE)
                .setColor(WHITE)
                .setUv(uv[i * 2], uv[i * 2 + 1])
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(lightCoords)
                .setNormal(pose, quad.nx, quad.ny, quad.nz)
        }
    }

    /**
     * The backend is keyed by name, but the render state only carries the entity
     * id, so resolve it against the client level. That is a hash lookup, and it
     * avoids a second mixin purely to copy the name onto the render state.
     */
    private fun usernameFor(state: AvatarRenderState): String? {
        val level = Minecraft.getInstance().level ?: return null
        val player = level.getEntity(state.id) as? Player ?: return null
        return player.gameProfile.name
    }

    private companion object {
        /** Vanilla model units are sixteenths of a block. */
        const val SCALE = 1f / 16f

        /** Untinted; the texture supplies the colour. */
        const val WHITE = -1
    }
}
