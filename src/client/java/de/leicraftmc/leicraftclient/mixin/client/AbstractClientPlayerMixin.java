package de.leicraftmc.leicraftclient.mixin.client;

import de.leicraftmc.leicraftclient.client.cape.CapeManager;
import java.util.Optional;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swaps a LeiCraft cape into the player's skin.
 *
 * Patching the skin rather than cloning {@code CapeLayer} keeps the cape subject
 * to everything vanilla does with it -- the cape toggle in skin customisation,
 * elytra swapping, chest-equipment offsets -- so there is nothing of vanilla's
 * cape handling to keep in sync on a Minecraft update.
 *
 * Mixins must be Java: the Mixin processor does not handle Kotlin classes
 * reliably, so this delegates straight into {@link CapeManager}.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void leicraftclient$applyCustomCape(final CallbackInfoReturnable<PlayerSkin> cir) {
		final PlayerSkin skin = cir.getReturnValue();
		if (skin == null) {
			return;
		}

		final AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
		final Identifier cape = CapeManager.INSTANCE.capeFor(self.getGameProfile().name());
		if (cape == null) {
			return;
		}

		// Both components must be passed explicitly: the single-argument
		// constructor treats its argument as an asset id and rewrites the path to
		// "textures/<id>.png", which would miss the texture we registered.
		cir.setReturnValue(skin.with(PlayerSkin.Patch.create(
			Optional.empty(),
			Optional.of(new ClientAsset.ResourceTexture(cape, cape)),
			Optional.empty(),
			Optional.empty()
		)));
	}
}
