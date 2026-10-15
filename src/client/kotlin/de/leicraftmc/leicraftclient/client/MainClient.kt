package de.leicraftmc.leicraftclient.client

import de.leicraftmc.leicraftclient.client.config.ClientConfig
import de.leicraftmc.leicraftclient.client.cosmetics.CosmeticsRenderLayer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.state.AvatarRenderState

class MainClient : ClientModInitializer {

    override fun onInitializeClient() {
        ClientConfig.load()

        LivingEntityRenderLayerRegistrationCallback.EVENT.register { _, renderer, helper, _ ->
            // Fires for every living entity renderer; only the player one carries
            // a PlayerModel, which is what cosmetics attach to.
            if (renderer.model !is PlayerModel) return@register
            @Suppress("UNCHECKED_CAST")
            val parent = renderer as RenderLayerParent<AvatarRenderState, PlayerModel>
            helper.register(CosmeticsRenderLayer(parent))
        }

        LeiCraftClient.LOGGER.info("LeiCraftClient ready, backend {}", ClientConfig.apiBaseUrl)
    }
}
